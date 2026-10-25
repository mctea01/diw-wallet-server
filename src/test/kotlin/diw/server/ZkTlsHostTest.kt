package diw.server

import diw.testservers.zktls.ZkTlsRecordPage
import diw.testservers.zktls.ZkTlsTarget
import diw.wallet.core.did.DidResolver
import diw.wallet.core.zk.ZkBackend
import diw.wallet.core.zk.ZkResult
import diw.wallet.core.zk.ZkTemplatePack
import diw.wallet.core.zk.native.ZkNative
import diw.wallet.core.zktls.NativeZkTlsEngine
import diw.wallet.core.zktls.ZkTlsPlanner
import diw.wallet.core.zktls.ZkTlsProfiles
import diw.wallet.core.zktls.ZkTlsSessionRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.close
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** The zkTLS verifier host: access tokens, the signing key, template packs and, with TLSNotary built in, a whole proof. */
class ZkTlsHostTest {
    private val token = "test-token-0123456789"
    private val tokenHash = SigningIdentity.sha256Hex(token.encodeToByteArray())
    private val packKey = runBlocking { Crypto.createEcPrivateKey(EcCurve.P256) }

    private fun dir(): File = Files.createTempDirectory("diw-zktls-test").toFile()

    private fun config(url: String = "https://diw.example.org", data: File = dir(), extra: Map<String, String> = emptyMap()) = ServerConfig.from(
        mapOf("public_url" to url, "features" to "zktls", "zktls_token_sha256" to tokenHash, "data_dir" to data.path) + extra,
    )

    /** A pack holding the synthetic record page on [port], signed with the test key, as a file. */
    private suspend fun packFile(port: Int, directory: File = dir()): File {
        val pack = ZkTemplatePack(VERSION.incrementAndGet(), Clock.System.now(), listOf(ZkTlsRecordPage.profile(port)), emptyList())
        return File(directory, "pack.jwt").apply { writeText(ZkTemplatePack.sign(pack, AsymmetricKey.AnonymousExplicit(packKey, Algorithm.ES256))) }
    }

    private suspend fun server(c: ServerConfig, roots: List<ByteArray> = emptyList(), available: Boolean? = true) =
        Server(c, zkTlsRoots = roots, templateKeys = listOf(packKey.publicKey), zkTlsAvailable = available).also { it.templates!!.refresh() }

    @Test
    fun theFeatureNeedsATokenHash() {
        assertFailsWith<IllegalArgumentException> { ServerConfig.from(mapOf("public_url" to "https://diw.example.org", "features" to "zktls")) }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.from(mapOf("public_url" to "https://diw.example.org", "features" to "zktls", "zktls_token_sha256" to "abc"))
        }
        assertEquals(1, config().zkTlsTokenHashes.size)
    }

    @Test
    fun theSigningKeyIsKeptAndCanComeFromASecret() = runBlocking<Unit> {
        val data = dir()
        val first = SigningIdentity.load(File(data, "signing.pem"), "https://diw.example.org", generate = true)
        val again = SigningIdentity.load(File(data, "signing.pem"), "https://diw.example.org", generate = false)
        assertEquals(first.rootSha256, again.rootSha256)
        assertEquals(2, first.chain.size)
        val fromSecret = SigningIdentity.load(File(data, "missing.pem"), "https://diw.example.org", generate = false,
            pem = SigningIdentity.pem(first))
        assertEquals(first.rootSha256, fromSecret.rootSha256)
        assertFailsWith<IllegalArgumentException> { SigningIdentity.load(File(data, "missing.pem"), "https://diw.example.org", generate = false) }
        // The verifier key in one secret: its DID is the one written.
        val verifier = TwdiwVerifier.Identity.create()
        assertEquals(verifier.did, TwdiwVerifier.Identity.parse(TwdiwVerifier.Identity.pem(verifier)).did)
    }

    @Test
    fun sessionsNeedTheTokenAndUseAnInstalledPage() = testApplication {
        val data = dir()
        val server = server(config(data = data, extra = mapOf("zktls_templates_file" to packFile(8443).path)))
        application { module(server) }
        val info = Json.parseToJsonElement(client.get("/info").bodyAsText()).jsonObject
        val signing = info["signing"]!!.jsonObject
        assertEquals(server.signing!!.rootSha256, signing["rootSha256"]!!.jsonPrimitive.content)
        assertTrue(signing["root"]!!.jsonPrimitive.content.startsWith("-----BEGIN CERTIFICATE-----"))
        assertTrue(client.get("/zktls/profiles").bodyAsText().contains(ZkTlsRecordPage.PROFILE_ID))

        suspend fun create(auth: String?, profile: String = ZkTlsRecordPage.PROFILE_ID): HttpResponse = client.post("/zktls/sessions") {
            auth?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody("""{"profile":"$profile","fields":["name"],"purpose":"入學查核"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, create(null).status)
        assertEquals(HttpStatusCode.Unauthorized, create("Bearer wrong-token-0000000").status)
        assertEquals(HttpStatusCode.BadRequest, create("Bearer $token", "unknown/1").status)
        val created = create("Bearer $token")
        assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
        val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        val link = body["request"]!!.jsonPrimitive.content
        val verified = ZkTlsSessionRequest.verify(ZkTlsSessionRequest.jwsOf(link), DidResolver(HttpClient(Java)))
        // Signed with the server's chain, naming the installed page only, with a wss transport back to this host.
        assertEquals(server.signing!!.root, verified.chain!!.last())
        assertEquals(null, verified.request.profileDefinition)
        assertTrue(verified.request.transports.single().toString().contains("wss://diw.example.org/zktls/sessions/"))
        val sid = body["sessionId"]!!.jsonPrimitive.content
        val poll = body["token"]!!.jsonPrimitive.content
        assertEquals("waiting", Json.parseToJsonElement(client.get("/zktls/sessions/$sid?token=$poll").bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, client.get("/zktls/sessions/$sid?token=nope").status)
        // The pack outlives a restart: the kept copy is installed again.
        assertTrue(File(data, ZkTemplatePack.FILE).isFile)
    }

    @Test
    fun aPackSignedWithAnotherKeyIsNotInstalled() = runBlocking<Unit> {
        val before = ZkTlsProfiles.packVersion
        val other = Crypto.createEcPrivateKey(EcCurve.P256)
        val pack = ZkTemplatePack(VERSION.incrementAndGet() + 1_000_000, Clock.System.now(), listOf(ZkTlsRecordPage.profile(9443)), emptyList())
        val file = File(dir(), "other.jwt").apply { writeText(ZkTemplatePack.sign(pack, AsymmetricKey.AnonymousExplicit(other, Algorithm.ES256))) }
        ZkTemplates(HttpClient(Java), dir(), file, listOf(packKey.publicKey)).refresh()
        assertEquals(before, ZkTlsProfiles.packVersion)
    }

    @Test
    fun withoutTlsNotaryHealthFailsAndSessionsAreRefused() = testApplication {
        val server = server(config(extra = mapOf("zktls_templates_file" to packFile(8443).path)), available = false)
        application { module(server) }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/healthz").status)
        val r = client.post("/zktls/sessions") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"profile":"${ZkTlsRecordPage.PROFILE_ID}","fields":["name"],"purpose":"x"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
    }

    @Test
    fun concurrentRequestsStayWithinTheLimits() = runBlocking<Unit> {
        val server = server(config(extra = mapOf("zktls_templates_file" to packFile(8443).path, "zktls_max_sessions" to "5", "zktls_per_address" to "100")))
        val host = server.zkTls!!
        val results = (1..20).map { async(Dispatchers.Default) { host.create(ZkTlsRecordPage.PROFILE_ID, listOf("name"), "x", "203.0.113.1") } }.awaitAll()
        assertEquals(5, results.count { it is ZkTlsHost.Created.Ok })
        assertTrue(results.filterIsInstance<ZkTlsHost.Created.Refused>().all { it.status == HttpStatusCode.TooManyRequests })
    }

    @Test
    fun aDroppedHolderEndsTheSession() = testApplication {
        val server = server(config(extra = mapOf("zktls_templates_file" to packFile(8443).path)))
        application { module(server) }
        val host = server.zkTls!!
        val created = assertIs<ZkTlsHost.Created.Ok>(host.create(ZkTlsRecordPage.PROFILE_ID, listOf("name"), "x", "203.0.113.1"))
        val ws = createClient { install(WebSockets) }
        runCatching { ws.webSocket("/zktls/sessions/${created.session.id}/ws") { close() } }
        val status = withTimeout(30_000) {
            while (host.state(created.session)["status"]!!.jsonPrimitive.content in setOf("waiting", "verifying")) delay(50)
            host.state(created.session)["status"]!!.jsonPrimitive.content
        }
        // Not left "verifying", which would hold its place against the limits for good.
        assertEquals("failed", status)
    }

    private fun tlsn(): Boolean {
        val status = (ZkNative.load(Files.createTempDirectory("diw-zk-native").toFile()) as? ZkResult.Ok)?.value
        return status != null && ZkBackend.TLSN in status.backends
    }

    /** A holder proves the synthetic record page through this host over wss, and asks for a web-proof receipt. */
    @Test
    fun aHolderProvesAPageThroughTheHost() = runBlocking<Unit> {
        assumeTrue(tlsn(), "Native library built without tlsn")
        ZkTlsTarget(responder = ZkTlsRecordPage::respond).use { target ->
            val port = ServerSocket(0).use { it.localPort }
            val url = "http://localhost:$port"
            val server = server(config(url, extra = mapOf("zktls_templates_file" to packFile(target.port).path)), listOf(target.caDer), available = null)
            val engine = embeddedServer(Netty, port = port, host = "127.0.0.1") { module(server) }.start(wait = false)
            val http = HttpClient(Java)
            try {
                val created = http.post("$url/zktls/sessions") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"profile":"${ZkTlsRecordPage.PROFILE_ID}","fields":["name","date"],"purpose":"入學查核"}""")
                }
                assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
                val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                val insecure = setOf("localhost:$port")
                val verified = ZkTlsSessionRequest.verify(ZkTlsSessionRequest.jwsOf(body["request"]!!.jsonPrimitive.content), DidResolver(http), insecureHosts = insecure)
                val profile = ZkTlsProfiles.resolve(verified.request, allowInline = false)
                val request = ZkTlsPlanner.request(profile, mapOf("serial" to ZkTlsRecordPage.SERIAL, "code" to ZkTlsRecordPage.CODE))
                val proved = NativeZkTlsEngine(http, { emptyList() }).prove(verified, profile, request, listOf(target.caDer)) {}
                assertTrue(proved is ZkResult.Ok, proved.toString())
                assertTrue(proved.value.outcome.passed)
                val sid = body["sessionId"]!!.jsonPrimitive.content
                val poll = body["token"]!!.jsonPrimitive.content
                val state = withTimeout(30_000) {
                    var s: JsonObject
                    do {
                        delay(100)
                        s = Json.parseToJsonElement(http.get("$url/zktls/sessions/$sid?token=$poll").bodyAsText()).jsonObject
                    } while (s["status"]!!.jsonPrimitive.content in setOf("waiting", "verifying"))
                    s
                }
                assertEquals("done", state["status"]!!.jsonPrimitive.content, state.toString())
                val outcome = assertNotNull(state["outcome"]).toString()
                assertTrue(ZkTlsRecordPage.NAME in outcome && ZkTlsRecordPage.DATE in outcome)
                assertFalse(ZkTlsRecordPage.HIDDEN in outcome)
                assertFalse(ZkTlsRecordPage.SERIAL in state.toString())
                // Peak memory of this test JVM (server and holder together), for DESIGN 6.
                File("/proc/self/status").takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith("VmHWM") }?.let { println("zkTLS host test peak: $it") }
            } finally {
                http.close()
                engine.stop(100, 500)
            }
        }
    }

    companion object {
        /** Template pack versions only grow across the tests of one JVM (the installed pack is shared). */
        private val VERSION = AtomicLong(Clock.System.now().epochSeconds)
    }
}
