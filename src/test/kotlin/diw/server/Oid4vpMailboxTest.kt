package diw.server

import diw.wallet.core.verifier.Oid4vpMailbox
import diw.wallet.core.verifier.Oid4vpMailboxClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The OpenID4VP mailbox: configuration, the wallet core client against the routes, and the session rules. */
class Oid4vpMailboxTest {
    private val token = "oid4vp-test-token-0123456789"
    private val tokenHash = SigningIdentity.sha256Hex(token.encodeToByteArray())
    private val requestObject = "eyJhbGciOiJFUzI1NiJ9.eyJub25jZSI6Im4ifQ.c2ln"

    private fun config(extra: Map<String, String> = emptyMap()) = ServerConfig.from(
        mapOf(
            "public_url" to "https://diw.example.org", "features" to "oid4vp", "oid4vp_token_sha256" to tokenHash,
            "data_dir" to kotlin.io.path.createTempDirectory("diw-oid4vp").toString(),
        ) + extra,
    )

    private fun path(uri: String): String = URI(uri).rawPath

    @Test
    fun theFeatureNeedsATokenHash() {
        val base = mapOf("public_url" to "https://diw.example.org", "features" to "oid4vp")
        assertFailsWith<IllegalArgumentException> { ServerConfig.from(base) }
        assertFailsWith<IllegalArgumentException> { config(mapOf("oid4vp_max_sessions" to "0")) }
        // Without its own hashes the feature takes the zkTLS ones: the wallet sends one token to its server.
        val shared = ServerConfig.from(base + mapOf("features" to "zktls,oid4vp", "zktls_token_sha256" to tokenHash))
        assertTrue(shared.oid4vpTokenHashes.single().contentEquals(shared.zkTlsTokenHashes.single()))
        assertEquals(32L shl 20, config().oid4vp.maxStoredBytes)
    }

    @Test
    fun aVerifierReceivesTheHoldersResponseOnce() = testApplication {
        val server = Server(config())
        application { module(server) }
        val info = Json.parseToJsonElement(client.get("/info").bodyAsText()).jsonObject
        assertTrue(info["features"]!!.jsonArray.any { it.jsonPrimitive.content == "oid4vp" })

        // Without the token no session opens.
        assertEquals(HttpStatusCode.Unauthorized, client.post(Oid4vpMailbox.SESSIONS) { setBody("{}") }.status)
        assertFailsWith<diw.wallet.core.util.ProtocolException> { Oid4vpMailboxClient(client, "", null).open() }

        val mailbox = Oid4vpMailboxClient(client, "", token)
        val s = mailbox.open()
        assertTrue(s.requestUri.startsWith("https://diw.example.org" + Oid4vpMailbox.REQUEST + "/"))
        assertTrue(s.responseUri.startsWith("https://diw.example.org" + Oid4vpMailbox.RESPONSE + "/"))
        assertTrue(Oid4vpMailbox.validId(s.id))

        // The holder finds nothing until the verifier puts its request, and cannot answer before.
        assertEquals(HttpStatusCode.NotFound, client.get(path(s.requestUri)).status)
        assertEquals(HttpStatusCode.NotFound, client.post(path(s.responseUri)) { form("response=early") }.status)
        // Only the session secret puts the request.
        val wrong = client.put(Oid4vpMailbox.SESSIONS + "/" + s.id + "/request") {
            header("Authorization", "Bearer $token")
            setBody(requestObject)
        }
        assertEquals(HttpStatusCode.NotFound, wrong.status)
        mailbox.putRequest(s, requestObject)

        val fetched = client.get(path(s.requestUri))
        assertEquals(HttpStatusCode.OK, fetched.status)
        assertEquals(requestObject, fetched.bodyAsText())
        assertEquals("application/oauth-authz-req+jwt", fetched.headers["Content-Type"]?.substringBefore(';'))
        // request_uri_method=post is answered the same way.
        assertEquals(requestObject, client.post(path(s.requestUri)) { form("wallet_nonce=x") }.bodyAsText())

        val answered = client.post(path(s.responseUri)) { form("response=eyJ.jwe.body&state=st&other=dropped") }
        assertEquals(HttpStatusCode.OK, answered.status)
        assertEquals("{}", answered.bodyAsText())
        assertEquals(HttpStatusCode.Conflict, client.post(path(s.responseUri)) { form("response=second") }.status)

        val response = mailbox.awaitResponse(s)
        assertEquals(mapOf("response" to "eyJ.jwe.body", "state" to "st"), response)
        // Handed over once: the session is gone.
        assertNull(mailbox.awaitResponse(s))
        assertEquals(HttpStatusCode.NotFound, client.get(path(s.requestUri)).status)
        assertEquals(0, server.oid4vp!!.size)
    }

    @Test
    fun aClosedSessionIsGone() = testApplication {
        val server = Server(config())
        application { module(server) }
        val mailbox = Oid4vpMailboxClient(client, "", token)
        val s = mailbox.open()
        mailbox.putRequest(s, requestObject)
        mailbox.close(s)
        assertEquals(HttpStatusCode.NotFound, client.get(path(s.requestUri)).status)
        assertEquals(HttpStatusCode.NotFound, client.post(path(s.responseUri)) { form("response=x") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post(Oid4vpMailbox.RESPONSE + "/" + s.id) { form("nothing=here") }.status)
    }

    @Test
    fun aWaitingFetchEndsWhenTheResponseArrives() = runBlocking<Unit> {
        val host = Oid4vpMailboxHost("https://diw.example.org")
        val s = host.open("a")!!
        assertEquals(Oid4vpMailboxHost.Put.OK, host.putRequest(s.id, s.secret, requestObject))
        assertEquals(Oid4vpMailboxHost.Fetch.Waiting, host.awaitResponse(s.id, s.secret, 50.milliseconds))
        val waiting = async { host.awaitResponse(s.id, s.secret, 10.seconds) }
        delay(100)
        assertEquals(Oid4vpMailboxHost.Post.OK, host.postResponse(s.id, mapOf("error" to "access_denied")))
        val r = waiting.await()
        assertEquals(Oid4vpMailboxHost.Fetch.Answer(mapOf("error" to "access_denied")), r)
        assertEquals(Oid4vpMailboxHost.Fetch.NotFound, host.awaitResponse(s.id, s.secret, 50.milliseconds))
        assertEquals(Oid4vpMailboxHost.Fetch.NotFound, host.awaitResponse("short", s.secret))
    }

    @Test
    fun sessionsAreBoundedAndExpire() {
        var now = Instant.fromEpochSeconds(1_800_000_000)
        val host = Oid4vpMailboxHost("https://diw.example.org", MailboxLimits(maxSessions = 3, perAddress = 2, maxStoredBytes = 100), clock = { now })
        val a1 = assertNotNull(host.open("a"))
        assertNotNull(host.open("a"))
        assertNull(host.open("a"), "one address keeps at most two")
        assertNotNull(host.open("b"))
        assertNull(host.open("c"), "the server keeps at most three")
        assertEquals(Oid4vpMailboxHost.Put.NOT_FOUND, host.putRequest(a1.id, "wrong", requestObject))
        assertEquals(Oid4vpMailboxHost.Put.FULL, host.putRequest(a1.id, a1.secret, "x".repeat(101)))
        assertEquals(Oid4vpMailboxHost.Put.OK, host.putRequest(a1.id, a1.secret, requestObject))
        assertEquals(Oid4vpMailboxHost.Post.FULL, host.postResponse(a1.id, mapOf("response" to "y".repeat(80))))
        now += Oid4vpMailbox.LIFETIME + 1.minutes
        assertNull(host.request(a1.id))
        assertEquals(0, host.size)
        assertNotNull(host.open("c"))
    }

    private fun io.ktor.client.request.HttpRequestBuilder.form(body: String) {
        contentType(ContentType.Application.FormUrlEncoded)
        setBody(body)
    }
}
