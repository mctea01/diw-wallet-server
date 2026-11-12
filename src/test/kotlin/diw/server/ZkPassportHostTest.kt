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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The owner's zkPassport prover: the token, the job lifecycle and what reaches the prover (fictional chip data). */
class ZkPassportHostTest {
    private val token = "zkpassport-test-token-0123456789"
    private val tokenHash = SigningIdentity.sha256Hex(token.encodeToByteArray())
    private val b64 = Base64.getEncoder()

    private fun config(extra: Map<String, String> = emptyMap()) = ServerConfig.from(
        mapOf(
            "public_url" to "https://diw.example.org", "features" to "zkpassport", "zkpassport_token_sha256" to tokenHash,
            "zkpassport_queue" to "1", "zkpassport_rpc" to "https://rpc.example.org",
            "data_dir" to kotlin.io.path.createTempDirectory("diw-zkpassport").toString(),
        ) + extra,
    )

    private fun request(domain: String = "demo.example.org") = buildJsonObject {
        put("sod", b64.encodeToString(byteArrayOf(0x77, 0x01, 0x02)))
        put("dg1", b64.encodeToString(byteArrayOf(0x61, 0x03)))
        put("query", buildJsonObject { put("age", buildJsonObject { put("gte", 18) }) })
        put("domain", domain)
        put("scope", "demo")
        put("dev_mode", false)
    }.toString()

    /** A real pause: testApplication may skip delay() in virtual time while the job runs on real threads. */
    private suspend fun pause() = withContext(Dispatchers.Default) { delay(20) }

    @Test
    fun theFeatureNeedsATokenHash() {
        val base = mapOf("public_url" to "https://diw.example.org", "features" to "zkpassport")
        assertFailsWith<IllegalArgumentException> { ServerConfig.from(base) }
        // The zkTLS token may be shared with other wallets, so it neither stands in for nor equals the prover's token.
        assertFailsWith<IllegalArgumentException> { ServerConfig.from(base + mapOf("features" to "zktls,zkpassport", "zktls_token_sha256" to tokenHash)) }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.from(base + mapOf("features" to "zktls,zkpassport", "zktls_token_sha256" to tokenHash, "zkpassport_token_sha256" to tokenHash))
        }
        val other = SigningIdentity.sha256Hex("zktls-shared-token-0123456789".encodeToByteArray())
        val both = ServerConfig.from(base + mapOf("features" to "zktls,zkpassport", "zktls_token_sha256" to other, "zkpassport_token_sha256" to tokenHash))
        assertTrue(both.zkPassportTokenHashes.single().contentEquals(both.zkTlsTokenHashes.single()).not())
        assertFailsWith<IllegalArgumentException> { config(mapOf("zkpassport_rpc" to "ftp://rpc.example.org")) }
    }

    @Test
    fun aJobIsProvedOnceAndItsOutcomeReadOnce() = testApplication {
        val release = CompletableDeferred<Unit>()
        var seen: Pair<ZkPassportProver.Chip, ZkPassportProver.Job>? = null
        val proving = ZkPassportProving { chip, job, progress ->
            seen = chip to job
            progress(ZkPassportProver.Progress.Proving(1, 4, "sig_check_dsc"))
            release.await()
            ZkPassportProver.Proved(
                listOf(ZkPassportProver.Proof("sig_check_dsc", "0xab", "vk", "0x01", "0.22.0", null)),
                listOf("proof" to buildJsonObject { put("name", "sig_check_dsc") }, "done" to buildJsonObject { }),
                buildJsonObject { put("age", buildJsonObject { put("gte", buildJsonObject { put("result", true) }) }) },
            )
        }
        val server = Server(config(), zkPassportProving = proving, zkPassportAvailable = true)
        application { module(server) }
        suspend fun call(method: String, path: String, auth: String? = "Bearer $token", body: String? = null): HttpResponse = when (method) {
            "POST" -> client.post(path) { auth?.let { header("Authorization", it) }; contentType(ContentType.Application.Json); setBody(body ?: "") }
            "DELETE" -> client.delete(path) { auth?.let { header("Authorization", it) } }
            else -> client.get(path) { auth?.let { header("Authorization", it) } }
        }
        fun json(r: String) = Json.parseToJsonElement(r).jsonObject

        val info = json(client.get("/info").bodyAsText())
        assertEquals(JsonPrimitive(true), info["zkpassport"]!!.jsonObject["available"])
        assertEquals(HttpStatusCode.Unauthorized, call("POST", "/zkpassport/v1/jobs", auth = null, body = request()).status)
        assertEquals(HttpStatusCode.Unauthorized, call("POST", "/zkpassport/v1/jobs", auth = "Bearer wrong", body = request()).status)
        assertEquals(HttpStatusCode.BadRequest, call("POST", "/zkpassport/v1/jobs", body = request(domain = "bad domain")).status)
        assertEquals(HttpStatusCode.BadRequest, call("POST", "/zkpassport/v1/jobs", body = "{}").status)

        val created = call("POST", "/zkpassport/v1/jobs", body = request())
        assertEquals(HttpStatusCode.Accepted, created.status, created.bodyAsText())
        val id = json(created.bodyAsText())["id"]!!.jsonPrimitive.content
        // One job proves and one may wait (zkpassport_queue=1); a third is refused.
        val second = json(call("POST", "/zkpassport/v1/jobs", body = request()).bodyAsText())["id"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.TooManyRequests, call("POST", "/zkpassport/v1/jobs", body = request()).status)
        assertEquals(HttpStatusCode.OK, call("DELETE", "/zkpassport/v1/jobs/$second").status)

        var state = json(call("GET", "/zkpassport/v1/jobs/$id").bodyAsText())
        for (i in 0 until 250) {
            if (state["state"]!!.jsonPrimitive.content == "proving") break
            pause()
            state = json(call("GET", "/zkpassport/v1/jobs/$id").bodyAsText())
        }
        assertEquals("proving", state["state"]!!.jsonPrimitive.content)
        assertEquals("sig_check_dsc", state["name"]!!.jsonPrimitive.content)
        val (chip, job) = seen!!
        assertTrue(chip.sod.contentEquals(byteArrayOf(0x77, 0x01, 0x02)))
        assertNull(chip.dg2, "DG2 stays on the phone")
        assertEquals("demo.example.org", job.domain)
        assertEquals("https://rpc.example.org", job.rpc, "the server's node, not one the request names")
        assertNull(job.oprf)

        release.complete(Unit)
        for (i in 0 until 250) {
            state = json(call("GET", "/zkpassport/v1/jobs/$id").bodyAsText())
            if (state["state"]!!.jsonPrimitive.content == "done") break
            pause()
        }
        assertEquals("done", state["state"]!!.jsonPrimitive.content)
        val result = state["result"] as JsonObject
        assertEquals("0x01", result["proofs"]!!.jsonArray.single().jsonObject["vkey_hash"]!!.jsonPrimitive.content)
        assertEquals(listOf("proof", "done"), result["messages"]!!.jsonArray.map { it.jsonObject["method"]!!.jsonPrimitive.content })
        assertEquals(HttpStatusCode.NotFound, call("GET", "/zkpassport/v1/jobs/$id").status, "the outcome is read once")
        assertEquals(HttpStatusCode.NotFound, call("GET", "/zkpassport/v1/jobs/not-an-id").status)
    }

    @Test
    fun aFailureKeepsOnlyItsCode() = testApplication {
        val server = Server(config(), zkPassportProving = { _, _, _ -> throw ZkPassportProver.Failure("proof:x:acir_input") }, zkPassportAvailable = true)
        application { module(server) }
        val id = Json.parseToJsonElement(client.post("/zkpassport/v1/jobs") {
            header("Authorization", "Bearer $token"); contentType(ContentType.Application.Json); setBody(request())
        }.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        var state: JsonObject = buildJsonObject { }
        for (i in 0 until 250) {
            state = Json.parseToJsonElement(client.get("/zkpassport/v1/jobs/$id") { header("Authorization", "Bearer $token") }.bodyAsText()).jsonObject
            if (state["state"]?.jsonPrimitive?.content == "failed") break
            pause()
        }
        assertEquals("proof:x:acir_input", state["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun withoutUltraHonkTheProverAnswers503() = testApplication {
        val server = Server(config(), zkPassportProving = { _, _, _ -> error("not called") }, zkPassportAvailable = false)
        application { module(server) }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/healthz").status)
        val r = client.post("/zkpassport/v1/jobs") { header("Authorization", "Bearer $token"); contentType(ContentType.Application.Json); setBody(request()) }
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
    }
}
