package diw.server

import diw.wallet.core.document.sign.TwFidoClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mobile citizen certificate signing through this server's SP account, against a local stand-in for TW FidO. */
class TwFidoHostTest {
    private val token = "test-token-0123456789"
    private val tokenHash = SigningIdentity.sha256Hex(token.encodeToByteArray())
    private val spKey = ByteArray(32) { (it * 7).toByte() }
    private val holder = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val certificate = JcaX509CertificateConverter().getCertificate(
        JcaX509v3CertificateBuilder(
            X500Name("CN=Test Holder"), BigInteger.TEN, Date(), Date(System.currentTimeMillis() + 86_400_000),
            X500Name("CN=Test Holder"), holder.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(holder.private)),
    )
    private val id = "A123456789"

    /** The stand-in: tickets for push and app-to-app, one pending answer, then the signature over what was sent. */
    private inner class FakeTwFido {
        val port = ServerSocket(0).use { it.localPort }
        val signData = ConcurrentHashMap<String, ByteArray>()
        val polls = ConcurrentHashMap<String, Int>()
        @Volatile var forgeChecksum = false
        val engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
            embeddedServer(Netty, port = port, host = "127.0.0.1") {
                routing {
                    for (path in listOf(TwFidoClient.PATH_PUSH, TwFidoClient.PATH_APP2APP)) post("/$path") {
                        val o = Json.parseToJsonElement(call.receiveText()).jsonObject
                        val txn = o["transaction_id"]!!.jsonPrimitive.content
                        signData[txn] = Base64.getDecoder().decode(o["sign_info"]!!.jsonObject["sign_data"]!!.jsonPrimitive.content)
                        val ticketJson = """{"transaction_id":"$txn","sp_ticket_id":"t-$txn"}"""
                        val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(ticketJson.toByteArray()) + ".digest"
                        call.respondText(buildJsonObject {
                            put("error_code", "0")
                            putJsonObject("result") { put("sp_ticket", ticket) }
                        }.toString(), ContentType.Application.Json)
                    }
                    post("/${TwFidoClient.PATH_RESULT}") {
                        val o = Json.parseToJsonElement(call.receiveText()).jsonObject
                        val txn = o["transaction_id"]!!.jsonPrimitive.content
                        val n = polls.merge(txn, 1, Int::plus)!!
                        val body = if (n == 1) buildJsonObject { put("error_code", "20002") } else {
                            val signed = Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withRSA").run {
                                initSign(holder.private)
                                update(signData[txn]!!)
                                sign()
                            })
                            val hashed = "hashed-id"
                            val checksum = TwFidoClient.spChecksum(txn + "0" + hashed + signed, if (forgeChecksum) ByteArray(32) else spKey, ByteArray(12))
                            buildJsonObject {
                                put("error_code", "0")
                                putJsonObject("result") {
                                    put("hashed_id_num", hashed)
                                    put("signed_response", signed)
                                    put("cert", Base64.getEncoder().encodeToString(certificate.encoded))
                                    put("idp_checksum", checksum)
                                }
                            }
                        }
                        call.respondText(body.toString(), ContentType.Application.Json)
                    }
                }
            }.start(wait = false)
    }

    private val fakes = ArrayList<FakeTwFido>()

    @AfterTest
    fun stop() = fakes.forEach { it.engine.stop(100, 500) }

    private fun config(extra: Map<String, String> = emptyMap()) = ServerConfig.from(
        mapOf(
            "public_url" to "https://diw.example.org", "features" to "twfido", "twfido_environment" to "uat",
            "twfido_service_id" to "sp-test", "twfido_aes_key" to Base64.getEncoder().encodeToString(spKey),
            "twfido_token_sha256" to tokenHash, "data_dir" to kotlin.io.path.createTempDirectory("diw-twfido").toString(),
        ) + extra,
    )

    @Test
    fun theFeatureNeedsTheSpAccountAndATokenHash() {
        val base = mapOf("public_url" to "https://diw.example.org", "features" to "twfido")
        assertFailsWith<IllegalArgumentException> { ServerConfig.from(base) }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.from(base + mapOf("twfido_service_id" to "sp", "twfido_aes_key" to "c2hvcnQ=", "twfido_token_sha256" to tokenHash))
        }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.from(base + mapOf("twfido_service_id" to "sp", "twfido_aes_key" to Base64.getEncoder().encodeToString(spKey)))
        }
        assertEquals("UAT", config().twFidoEnvironment.name)
        // Without its own hashes the feature takes the zkTLS ones: the wallet sends one token to its server.
        val shared = ServerConfig.from(
            base + mapOf("features" to "zktls,twfido", "zktls_token_sha256" to tokenHash, "twfido_service_id" to "sp",
                "twfido_aes_key" to Base64.getEncoder().encodeToString(spKey)),
        )
        assertTrue(shared.twFidoTokenHashes.single().contentEquals(shared.zkTlsTokenHashes.single()))
    }

    @Test
    fun aHolderSignsThroughTheServersSpAccount() = testApplication {
        val fake = FakeTwFido().also { fakes += it }
        val server = Server(config(), twFidoBaseUrl = "http://127.0.0.1:${fake.port}/")
        application { module(server) }
        assertEquals("UAT", Json.parseToJsonElement(client.get("/info").bodyAsText()).jsonObject["twfido"]!!.jsonObject["environment"]!!.jsonPrimitive.content)

        val data = "signed attributes".toByteArray()
        suspend fun create(auth: String?, idNumber: String = id, mode: String = "push"): HttpResponse = client.post("/twfido/sign") {
            auth?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"$mode","idNumber":"$idNumber","hint":"文件簽署","data":"${Base64.getEncoder().encodeToString(data)}","timeLimit":60}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, create(null).status)
        assertEquals(HttpStatusCode.BadRequest, create("Bearer $token", "A123456780").status)
        val created = create("Bearer $token", mode = "app2app")
        assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
        val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        val ticket = TwFidoClient.parseTicket(body["spTicket"]!!.jsonPrimitive.content)
        assertTrue(fake.signData.containsKey(ticket.transactionId))
        val sid = body["sessionId"]!!.jsonPrimitive.content
        val poll = body["token"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.NotFound, client.get("/twfido/sign/$sid?token=nope").status)

        val state = withTimeout(30_000) {
            var s: JsonObject
            do {
                delay(200)
                s = Json.parseToJsonElement(client.get("/twfido/sign/$sid?token=$poll").bodyAsText()).jsonObject
            } while (s["status"]!!.jsonPrimitive.content == "pending")
            s
        }
        assertEquals("done", state["status"]!!.jsonPrimitive.content, state.toString())
        // However often the wallet polls, the service is asked at most every few seconds.
        assertEquals(2, fake.polls[ticket.transactionId])
        val signature = Base64.getDecoder().decode(state["signature"]!!.jsonPrimitive.content)
        assertTrue(Signature.getInstance("SHA256withRSA").run {
            initVerify(certificate.publicKey)
            update(data)
            verify(signature)
        })
        assertTrue(Base64.getDecoder().decode(state["certificate"]!!.jsonPrimitive.content).contentEquals(certificate.encoded))
        // Neither the ID number nor the signed bytes are echoed back.
        assertFalse(id in state.toString() || id in body.toString())
    }

    @Test
    fun aForgedAnswerFailsAndRequestsPerHolderAreBounded() = runBlocking<Unit> {
        val fake = FakeTwFido().also { fakes += it; it.forgeChecksum = true }
        val server = Server(config(mapOf("twfido_per_id" to "1")), twFidoBaseUrl = "http://127.0.0.1:${fake.port}/")
        val host = server.twFido!!
        val first = host.create("push", id, "文件簽署", byteArrayOf(1), 60, "203.0.113.1") as TwFidoHost.Created.Ok
        val second = host.create("push", id.lowercase(), "文件簽署", byteArrayOf(1), 60, "203.0.113.2")
        assertEquals(HttpStatusCode.TooManyRequests, (second as TwFidoHost.Created.Refused).status)
        val state = withTimeout(30_000) {
            var s: JsonObject
            do {
                delay(500)
                s = host.poll(first.session)
            } while (s["status"]!!.jsonPrimitive.content == "pending")
            s
        }
        assertEquals("failed", state["status"]!!.jsonPrimitive.content)
        assertTrue("idp_checksum" in state["reason"]!!.jsonPrimitive.content)
        assertFalse("signature" in state)
        // Once the first request is over, the same holder may ask again.
        assertTrue(host.create("push", id, "文件簽署", byteArrayOf(1), 60, "203.0.113.1") is TwFidoHost.Created.Ok)
    }
}
