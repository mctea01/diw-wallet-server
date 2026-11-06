package diw.server

import diw.wallet.core.zk.ZkResult
import diw.wallet.core.zk.native.ZkNative
import diw.wallet.core.zk.native.bindings.nativeInfo
import diw.wallet.core.zk.native.bindings.oprfBlind
import diw.wallet.core.zk.native.bindings.oprfFinalize
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The project OPRF: configuration, the token and rate limits, and, with the native OPRF, a whole blinded round. */
class OprfHostTest {
    private val token = "oprf-test-token-0123456789"
    private val tokenHash = SigningIdentity.sha256Hex(token.encodeToByteArray())
    private val key = "0x1234567890abcdef1234567890abcdef"

    private fun config(extra: Map<String, String> = emptyMap()) = ServerConfig.from(
        mapOf(
            "public_url" to "https://diw.example.org", "features" to "oprf", "oprf_key" to key,
            "oprf_token_sha256" to tokenHash, "data_dir" to kotlin.io.path.createTempDirectory("diw-oprf").toString(),
        ) + extra,
    )

    private fun nativeOprf(): Boolean {
        val directory = kotlin.io.path.createTempDirectory("diw-oprf-native").toFile()
        return ZkNative.load(directory) is ZkResult.Ok && "oprf" in nativeInfo().features
    }

    @Test
    fun theFeatureNeedsAKeyAndATokenHash() {
        val base = mapOf("public_url" to "https://diw.example.org", "features" to "oprf")
        assertFailsWith<IllegalArgumentException> { ServerConfig.from(base + mapOf("oprf_token_sha256" to tokenHash)) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.from(base + mapOf("oprf_key" to key)) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.from(base + mapOf("oprf_key" to "0", "oprf_token_sha256" to tokenHash)) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.from(base + mapOf("oprf_key" to "0xzz", "oprf_token_sha256" to tokenHash)) }
        assertFailsWith<IllegalArgumentException> { config(mapOf("oprf_key_id" to "a b")) }
        assertEquals("1", config().oprfKeyId)
        // Without its own hashes the feature takes the zkTLS ones: the wallet sends one token to its server.
        val shared = ServerConfig.from(base + mapOf("features" to "zktls,oprf", "zktls_token_sha256" to tokenHash, "oprf_key" to key))
        assertTrue(shared.oprfTokenHashes.single().contentEquals(shared.zkTlsTokenHashes.single()))
        assertTrue(OprfHost.validKey(OprfHost.generateKey()))
        assertTrue(OprfHost.generateKey() != OprfHost.generateKey())
    }

    @Test
    fun allowancesResetEachWindow() {
        var now = Instant.fromEpochSeconds(1_800_000_000)
        val host = OprfHost(key, "1", OprfLimits(perAddress = 2, total = 3), clock = { now })
        assertTrue(host.admit("a"))
        assertTrue(host.admit("a"))
        assertFalse(host.admit("a"))
        assertTrue(host.admit("b"))
        assertFalse(host.admit("c"), "the server's allowance is used up")
        now += 61.minutes
        assertTrue(host.admit("a"))
    }

    @Test
    fun aWalletGetsAVerifiableAnswer() = testApplication {
        assumeTrue(nativeOprf(), "native library without the OPRF")
        val server = Server(config(mapOf("oprf_per_address" to "2")))
        application { module(server) }
        val info = Json.parseToJsonElement(client.get("/info").bodyAsText()).jsonObject
        val published = Json.parseToJsonElement(client.get("/oprf/v1/key").bodyAsText()).jsonObject
        assertEquals("1", published["key_id"]!!.jsonPrimitive.content)
        assertEquals(published["pk"], info["oprf"]!!.jsonObject["pk"])

        val query = "0x25d736ccb33e663ca64bf23add154cb740c5fa863b518da3c1a584f856b48986"
        val blinded = Json.parseToJsonElement(oprfBlind(query)).jsonObject
        suspend fun evaluate(auth: String?, body: String): HttpResponse = client.post("/oprf/v1/evaluate") {
            auth?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val request = buildJsonObject { put("key_id", "1"); put("blinded_query", blinded["blinded_query"]!!) }.toString()
        assertEquals(HttpStatusCode.Unauthorized, evaluate(null, request).status)
        assertEquals(HttpStatusCode.NotFound, evaluate("Bearer $token", request.replace("\"key_id\":\"1\"", "\"key_id\":\"2\"")).status)
        assertEquals(HttpStatusCode.BadRequest, evaluate("Bearer $token", """{"blinded_query":{"x":"0x1","y":"0x2"}}""").status)
        val answered = evaluate("Bearer $token", request)
        assertEquals(HttpStatusCode.OK, answered.status, answered.bodyAsText())
        val answer = Json.parseToJsonElement(answered.bodyAsText()).jsonObject
        val finalize = buildJsonObject {
            put("query", query)
            put("beta", blinded["beta"]!!)
            put("pk", buildJsonObject { val pk = published["pk"] as JsonObject; put("x", pk["x"]!!); put("y", pk["y"]!!) })
            listOf("response_blinded", "dlog_e", "dlog_s").forEach { put(it, answer[it]!!) }
        }
        assertTrue(Json.parseToJsonElement(oprfFinalize(finalize.toString())).jsonObject.containsKey("output"))
        assertEquals(HttpStatusCode.TooManyRequests, evaluate("Bearer $token", request).status)
    }
}
