package diw.server

import diw.wallet.core.did.DidKeys
import diw.wallet.core.profile.bonds.BondsAgePackage
import diw.wallet.core.profile.bonds.BondsAgeRequest
import diw.wallet.core.profile.bonds.BondsAgeVerdict
import diw.wallet.core.profile.bonds.BondsAgeVerifier
import diw.wallet.core.zk.OpenAcAgeCheck
import diw.wallet.core.zk.OpenAcAgeVerifier
import diw.wallet.core.zk.ZkResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** The Bonds-compatible age verifier: relay round trip, server verification, limits and the trust rule. */
class BondsAgeTest {
    private val seen = mutableListOf<OpenAcAgeCheck>()
    private val fake = object : OpenAcAgeVerifier {
        override suspend fun verify(check: OpenAcAgeCheck): ZkResult<Boolean> {
            seen += check
            return ZkResult.Ok(check.prepareProof.contentEquals(byteArrayOf(1, 2, 3)))
        }
    }

    private fun server(engine: OpenAcAgeVerifier?, limits: BondsAgeLimits = BondsAgeLimits()) = Server(
        ServerConfig(
            publicUrl = "https://diw.example.org",
            features = setOf(ServerConfig.FEATURE_BONDS_AGE),
            dataDir = Files.createTempDirectory("diw-server-test").toFile(),
            bondsAge = limits,
        ),
        ageVerifier = engine,
    )

    private suspend fun HttpClient.session(source: String, mode: String): Pair<HttpStatusCode, JsonObject?> {
        val r = post("/api/zkp/sessions") {
            contentType(ContentType.Application.Json)
            setBody("""{"credentialSource":"$source","minimumAge":20,"purpose":"test","mode":"$mode"}""")
        }
        return r.status to (if (r.status == HttpStatusCode.OK) Json.parseToJsonElement(r.bodyAsText()).jsonObject else null)
    }

    private fun JsonObject.text(name: String) = this[name]!!.jsonPrimitive.content

    private fun request(s: JsonObject) = BondsAgeRequest.parse(s.text("request"))

    private suspend fun issuerDid() = DidKeys.didKeyJwkJcs(Crypto.createEcPrivateKey(EcCurve.P256).publicKey)

    private fun pkg(request: BondsAgeRequest, issuer: String, prepare: ByteArray = byteArrayOf(1, 2, 3)) =
        BondsAgePackage(request, "birthdate", BondsAgeRequest.FORMAT_ROC, issuer, prepare, byteArrayOf(4, 5), 1200, 300, Clock.System.now())

    /** Posts to the response URL's path on the test host (the URL names the public address). */
    private suspend fun HttpClient.answer(request: BondsAgeRequest, body: String): HttpResponse =
        post(Url(request.responseUrl!!).encodedPath) { contentType(ContentType.Application.Json); setBody(body) }

    @Test
    fun relayReturnsTheCreatorsVerdictToTheHolder() = testApplication {
        val server = server(null)
        application { module(server) }
        val config = client.get("/api/zkp/config").bodyAsText()
        assertTrue("\"relay\"" in config && "\"server\"" !in config)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.session("selfIssued", "server").first)

        val s = client.session("selfIssued", "relay").second!!
        val request = request(s)
        assertTrue(request.responseUrl!!.startsWith("https://diw.example.org/api/zkp/response/"))
        val status = "/api/zkp/sessions/${s.text("sessionId")}?token=${s.text("token")}"
        coroutineScope {
        val holder = async { client.answer(request, pkg(request, issuerDid()).encode()) }
        var posted: String? = null
        repeat(50) {
            if (posted == null) {
                posted = Json.parseToJsonElement(client.get(status).bodyAsText()).jsonObject["package"]?.jsonPrimitive?.content
                if (posted == null) delay(100)
            }
        }
        assertTrue(BondsAgePackage.parse(posted!!, request).prepareProof.contentEquals(byteArrayOf(1, 2, 3)))
        val verdictUrl = "/api/zkp/sessions/${s.text("sessionId")}/verdict?token=${s.text("token")}"
        assertEquals(HttpStatusCode.NotFound, client.post(verdictUrl.replaceAfter("token=", "wrong")) { setBody("{}") }.status)
        val answer = BondsAgeVerdict("accepted", true, null, 20, 4321)
        assertEquals(HttpStatusCode.OK, client.post(verdictUrl) { setBody(answer.encode()) }.status)
        val response = holder.await()
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(answer, BondsAgeVerdict.parse(response.bodyAsText()))
        assertEquals(HttpStatusCode.Conflict, client.post(verdictUrl) { setBody(answer.encode()) }.status)
        assertNull(Json.parseToJsonElement(client.get(status).bodyAsText()).jsonObject["package"], "an answered package is dropped")
        }
    }

    @Test
    fun serverModeChecksProofsAndNeedsAListedGovernmentIssuer() = testApplication {
        application { module(server(fake)) }
        assertTrue("\"server\"" in client.get("/api/zkp/config").bodyAsText())

        val self = request(client.session("selfIssued", "server").second!!)
        val other = BondsAgeRequest.create(20, self.source, "x", self.responseUrl)
        assertEquals(HttpStatusCode.BadRequest, client.answer(self, pkg(other, issuerDid()).encode()).status)
        val ok = client.answer(self, pkg(self, issuerDid()).encode())
        assertTrue(BondsAgeVerdict.parse(ok.bodyAsText())!!.accepted)
        assertEquals(self.nonce, seen.single().nonce)
        assertEquals(HttpStatusCode.Conflict, client.answer(self, pkg(self, issuerDid()).encode()).status)

        val bad = request(client.session("government", "server").second!!)
        val badVerdict = BondsAgeVerdict.parse(client.answer(bad, pkg(bad, issuerDid(), byteArrayOf(9)).encode()).bodyAsText())!!
        assertEquals(BondsAgeVerifier.PROOF_INVALID, badVerdict.reason)

        // Production accepts a government card only from an issuer on the TWDIW trust list (T2 or better).
        val gov = request(client.session("government", "server").second!!)
        val unlisted = BondsAgeVerdict.parse(client.answer(gov, pkg(gov, issuerDid()).encode()).bodyAsText())!!
        assertFalse(unlisted.accepted)
        assertEquals(BondsAgeVerifier.UNTRUSTED_ISSUER, unlisted.reason)
    }

    @Test
    fun sessionsAndPackagesAreBounded() = testApplication {
        application { module(server(fake, BondsAgeLimits(maxSessions = 3, perAddress = 2, maxPackageBytes = 64))) }
        repeat(2) { assertEquals(HttpStatusCode.OK, client.session("selfIssued", "relay").first) }
        assertEquals(HttpStatusCode.TooManyRequests, client.session("selfIssued", "relay").first)
        val r = client.post("/api/zkp/response/unknown") { setBody("x".repeat(65)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
        assertEquals(HttpStatusCode.Gone, client.post("/api/zkp/response/unknown") { setBody("{}") }.status)
    }
}
