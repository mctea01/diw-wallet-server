package diw.server

import diw.wallet.core.util.DiwJson
import diw.wallet.core.zk.native.bindings.ZkException
import diw.wallet.core.zk.native.bindings.nativeInfo
import diw.wallet.core.zk.native.bindings.oprfEvaluate
import diw.wallet.core.zk.native.bindings.oprfPublicKey
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.security.SecureRandom
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Evaluations allowed per client address in each [window], and in all per window. */
data class OprfLimits(val perAddress: Int = 20, val total: Int = 2000, val window: Duration = 1.hours)

/**
 * The project OPRF for zkPassport's salted unique identifier (wallet docs/design/zkpassport.md 5.5). The key is a
 * BabyJubJub scalar from a platform secret (oprf_key); the server answers blinded queries and proves it used the key
 * whose public half `/oprf/v1/key` publishes, so it never sees a private nullifier or document data. Answering needs
 * a server token and is limited per address; nothing about a query is logged or kept.
 */
class OprfHost(
    private val secret: String,
    val keyId: String,
    private val limits: OprfLimits,
    private val clock: () -> Instant = { Clock.System.now() },
) {
    /** `{x, y, hash}`, or null when the native library has no OPRF (the feature then answers 503). */
    val publicKey: JsonObject? = runCatching {
        if ("oprf" !in nativeInfo().features) null else DiwJson.parseToJsonElement(oprfPublicKey(secret)).jsonObject
    }.getOrNull()

    private val counts = HashMap<String, Int>()
    private var total = 0
    private var windowStart = clock()

    /** Takes one evaluation from [address]'s allowance; false when it or the server's is used up. */
    fun admit(address: String): Boolean = synchronized(counts) {
        val now = clock()
        if (now - windowStart >= limits.window) {
            counts.clear()
            total = 0
            windowStart = now
        }
        val n = counts[address] ?: 0
        if (n >= limits.perAddress || total >= limits.total) return false
        counts[address] = n + 1
        total++
        true
    }

    sealed interface Answer {
        data class Ok(val body: JsonObject) : Answer
        data object Invalid : Answer
        data object Unavailable : Answer
    }

    /** The answer to a blinded query `{x, y}`. */
    fun evaluate(blindedQuery: JsonObject): Answer {
        if (publicKey == null) return Answer.Unavailable
        val request = buildJsonObject { put("blinded_query", blindedQuery) }.toString()
        val answer = try {
            DiwJson.parseToJsonElement(oprfEvaluate(secret, request)).jsonObject
        } catch (e: ZkException) {
            return Answer.Invalid
        }
        return Answer.Ok(JsonObject(answer + ("key_id" to JsonPrimitive(keyId))))
    }

    companion object {
        private val SCALAR = Regex("^(0x[0-9a-fA-F]{1,64}|[0-9]{1,78})$")

        /** Whether [value] is written as a scalar the native library reads (its range is checked there). */
        fun validKey(value: String): Boolean = SCALAR.matches(value.trim()) && value.trim().let {
            val n = if (it.startsWith("0x")) BigInteger(it.drop(2), 16) else BigInteger(it)
            n.signum() > 0
        }

        /** A fresh key below the BabyJubJub subgroup order, in hex. */
        fun generateKey(random: SecureRandom = SecureRandom()): String {
            while (true) {
                val n = BigInteger(1, ByteArray(31).also(random::nextBytes))
                if (n.signum() > 0) return "0x" + n.toString(16)
            }
        }
    }
}

fun Route.oprfRoutes(host: OprfHost, tokenHashes: List<ByteArray>) {
    get("/oprf/v1/key") {
        val pk = host.publicKey
        if (pk == null) call.respondError(HttpStatusCode.ServiceUnavailable, "unavailable", "The native library has no OPRF")
        else call.respondJson(buildJsonObject { put("key_id", host.keyId); put("pk", pk) })
    }
    post("/oprf/v1/evaluate") {
        if (!bearerMatches(call.request.header("Authorization"), tokenHashes)) {
            call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "這台服務端的 OPRF 需要存取權杖")
            return@post
        }
        val o = call.receiveLimited(4 shl 10)?.let { runCatching { DiwJson.parseToJsonElement(it) as JsonObject }.getOrNull() }
        val blinded = o?.get("blinded_query") as? JsonObject
        val keyId = (o?.get("key_id") as? JsonPrimitive)?.contentOrNull
        if (o == null || blinded == null) {
            call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Malformed request")
            return@post
        }
        if (keyId != null && keyId != host.keyId) {
            call.respondError(HttpStatusCode.NotFound, "unknown_key", "No such key")
            return@post
        }
        if (!host.admit(call.request.origin.remoteAddress)) {
            call.respondError(HttpStatusCode.TooManyRequests, "rate_limited", "Too many requests")
            return@post
        }
        when (val answer = host.evaluate(blinded)) {
            is OprfHost.Answer.Ok -> call.respondJson(answer.body)
            OprfHost.Answer.Invalid -> call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Not a point of the group")
            OprfHost.Answer.Unavailable -> call.respondError(HttpStatusCode.ServiceUnavailable, "unavailable", "The native library has no OPRF")
        }
    }
}
