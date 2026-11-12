package diw.server

import diw.wallet.core.zkpassport.ZkPassportProver
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One-time QR pairing for a temporary prover (DESIGN 3.10), driven as the wallet would; fictional chip data. */
class ZkPassportPairingTest {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()

    private fun config(pairing: Boolean = true) = ServerConfig.from(
        mapOf(
            "public_url" to "https://192.168.1.20:8090", "features" to "zkpassport",
            "listen_host" to "127.0.0.1", "trust_forwarded" to "false",
            "data_dir" to kotlin.io.path.createTempDirectory("diw-pairing").toString(),
        ),
        pairing,
    )

    @Test
    fun onlyPrivateAddressesAndATokenUnlessPairing() {
        for (a in listOf("127.0.0.1", "::1", "192.168.1.2", "10.1.2.3", "172.16.0.9", "100.100.1.1", "169.254.3.4", "fd12::1", "[fe80::1%eth0]")) {
            assertTrue(ZkPassportPairing.privateAddress(a), a)
        }
        for (a in listOf("8.8.8.8", "100.128.0.1", "2001:db8::1", "example.org", "", "192.168.1.2.example.org")) {
            assertFalse(ZkPassportPairing.privateAddress(a), a)
        }
        config()
        assertFailsWith<IllegalArgumentException> { config(pairing = false) }
    }

    @Test
    fun aWalletPairsOnceAndEveryCallIsSealed() = testApplication {
        val proving = ZkPassportProving { _, _, _ ->
            ZkPassportProver.Proved(
                listOf(ZkPassportProver.Proof("disclose_bytes", "0xcd", "vk", "0x02", "0.22.0", null)),
                listOf("done" to buildJsonObject { }),
                buildJsonObject { },
            )
        }
        val pin = ByteArray(32) { 7 }
        val server = Server(config(), zkPassportProving = proving, zkPassportAvailable = true, zkPassportCertificateSha256 = pin)
        application { module(server) }
        fun json(r: String) = Json.parseToJsonElement(r).jsonObject

        val link = server.zkPassportPairing!!.newLink()
        val query = URI(link).rawSchemeSpecificPart.substringAfter('?').split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        assertEquals("https://192.168.1.20:8090", query["u"])
        assertTrue(pin.contentEquals(b64d.decode(query["c"])))
        val secret = b64d.decode(query["p"])

        val nonce = ByteArray(16) { it.toByte() }
        suspend fun pair(mac: ByteArray): HttpResponse = client.post("/zkpassport/v1/pair") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("nonce", b64.encodeToString(nonce)); put("mac", b64.encodeToString(mac)) }.toString())
        }
        assertEquals(HttpStatusCode.Unauthorized, pair(ByteArray(32)).status)
        val good = ZkPassportPairing.hmac(secret, "diw-prover pair v1".encodeToByteArray(), nonce)
        val paired = pair(good)
        assertEquals(HttpStatusCode.OK, paired.status)
        assertEquals(HttpStatusCode.Gone, pair(good).status, "a code pairs once")
        val p = json(paired.bodyAsText())
        val session = p["session"]!!.jsonPrimitive.content
        val key = SecretKeySpec(
            ZkPassportPairing.hmac(secret, "diw-prover session v1".encodeToByteArray(), nonce + b64d.decode(p["nonce"]!!.jsonPrimitive.content)), "AES",
        )

        var counter = 0L
        fun auth(method: String, path: String, n: Long = ++counter): Pair<String, Long> {
            val claims = buildJsonObject { put("n", n); put("m", method); put("p", path) }.toString().encodeToByteArray()
            return "DIW-Session $session." + ZkPassportPairing.encrypt(key, claims, "diw-prover auth v1 $session") to n
        }
        fun open(body: String, n: Long): JsonObject = json(
            ZkPassportPairing.decrypt(key, json(body)["sealed"]!!.jsonPrimitive.content, "$session $n response")!!.decodeToString(),
        )
        val request = buildJsonObject {
            put("sod", Base64.getEncoder().encodeToString(byteArrayOf(0x77, 0x01)))
            put("dg1", Base64.getEncoder().encodeToString(byteArrayOf(0x61, 0x03)))
            put("query", buildJsonObject { })
            put("domain", "demo.example.org")
        }.toString().encodeToByteArray()

        val (header, n) = auth("POST", "/zkpassport/v1/jobs")
        suspend fun submit(h: String, sealedFor: Long) = client.post("/zkpassport/v1/jobs") {
            header("Authorization", h)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("sealed", ZkPassportPairing.encrypt(key, request, "$session $sealedFor request")) }.toString())
        }
        val created = submit(header, n)
        assertEquals(HttpStatusCode.Accepted, created.status, created.bodyAsText())
        val id = open(created.bodyAsText(), n)["id"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.Unauthorized, submit(header, n).status, "a captured call cannot be replayed")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/zkpassport/v1/jobs/$id") { header("Authorization", auth("GET", "/zkpassport/v1/jobs/other").first) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/zkpassport/v1/jobs/$id").status)
        val (sealedOnce, m) = auth("POST", "/zkpassport/v1/jobs")
        assertEquals(HttpStatusCode.BadRequest, submit(sealedOnce, m + 1).status, "a body sealed for another call is refused")

        var state: JsonObject = buildJsonObject { }
        for (i in 0 until 250) {
            val (h, k) = auth("GET", "/zkpassport/v1/jobs/$id")
            val r = client.get("/zkpassport/v1/jobs/$id") { header("Authorization", h) }
            assertEquals(HttpStatusCode.OK, r.status)
            state = open(r.bodyAsText(), k)
            if (state["state"]!!.jsonPrimitive.content == "done") break
            withContext(Dispatchers.Default) { delay(20) }
        }
        assertEquals("done", state["state"]!!.jsonPrimitive.content)

        val (end, e) = auth("DELETE", "/zkpassport/v1/pair")
        val ended = client.delete("/zkpassport/v1/pair") { header("Authorization", end) }
        assertEquals("ended", open(ended.bodyAsText(), e)["state"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/zkpassport/v1/jobs/$id") { header("Authorization", auth("GET", "/zkpassport/v1/jobs/$id").first) }.status)
    }

    @Test
    fun theTlsFrontServesTheCertificateTheCodePins() {
        val tls = ProverTls.create("https://127.0.0.1:1")
        val listener = tls.serve(0, 1)
        try {
            val trustAll = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            }
            val context = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
            val socket = context.socketFactory.createSocket("127.0.0.1", listener.localPort) as javax.net.ssl.SSLSocket
            socket.use {
                it.startHandshake()
                val leaf = it.session.peerCertificates.first().encoded
                assertTrue(java.security.MessageDigest.getInstance("SHA-256").digest(leaf).contentEquals(tls.certificateSha256))
            }
        } finally {
            listener.close()
        }
    }

    @Test
    fun theQrCodeIsDrawnWithPlainEscapes() {
        val qr = terminalQr("diw-prover://v1?u=https%3A%2F%2F192.168.1.20%3A8090&p=AAAA&c=AAAA")
        assertTrue(qr.lines().size > 20)
        assertTrue(qr.all { it.code < 128 })
    }

    @Test
    fun theSvgCodeHasEveryDarkModule() {
        val svg = svgQr("diw-prover://v1?u=https%3A%2F%2F192.168.1.20%3A8090&p=AAAA&c=AAAA")
        assertTrue(svg.startsWith("<svg") && svg.trimEnd().endsWith("</svg>"))
        assertTrue(Regex("M\\d+ \\d+h1v1h-1z").findAll(svg).count() > 100)
    }
}
