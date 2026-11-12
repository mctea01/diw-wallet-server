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

    private fun config(extra: Map<String, String> = emptyMap()) = ServerConfig.from(
        mapOf(
            "public_url" to "http://192.168.1.20:8090", "features" to "zkpassport", "zkpassport_pairing" to "true",
            "listen_host" to "0.0.0.0", "trust_forwarded" to "false",
            "data_dir" to kotlin.io.path.createTempDirectory("diw-pairing").toString(),
        ) + extra,
    )

    @Test
    fun onlyPrivateAddressesAndDirectConnections() {
        for (a in listOf("127.0.0.1", "::1", "192.168.1.2", "10.1.2.3", "172.16.0.9", "100.100.1.1", "169.254.3.4", "fd12::1", "[fe80::1%eth0]")) {
            assertTrue(ZkPassportPairing.privateAddress(a), a)
        }
        for (a in listOf("8.8.8.8", "100.128.0.1", "2001:db8::1", "example.org", "", "192.168.1.2.example.org")) {
            assertFalse(ZkPassportPairing.privateAddress(a), a)
        }
        config()
        assertFailsWith<IllegalArgumentException> { config(mapOf("trust_forwarded" to "true")) }
        assertFailsWith<IllegalArgumentException> { config(mapOf("zkpassport_pairing" to "false")) }
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
        val server = Server(config(), zkPassportProving = proving, zkPassportAvailable = true)
        application { module(server) }
        fun json(r: String) = Json.parseToJsonElement(r).jsonObject

        val link = json(client.post("/zkpassport/v1/links").bodyAsText())["link"]!!.jsonPrimitive.content
        val query = URI(link).rawSchemeSpecificPart.substringAfter('?').split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        assertEquals("http://192.168.1.20:8090", query["u"])
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
    fun theQrCodeIsDrawnWithPlainEscapes() {
        val qr = terminalQr("diw-prover://v1?u=http%3A%2F%2F192.168.1.20%3A8090&p=AAAA")
        assertTrue(qr.lines().size > 20)
        assertTrue(qr.all { it.code < 128 })
    }
}
