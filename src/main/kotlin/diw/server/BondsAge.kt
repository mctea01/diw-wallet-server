package diw.server

import diw.wallet.core.did.DidResolver
import diw.wallet.core.profile.bonds.BondsAgePackage
import diw.wallet.core.profile.bonds.BondsAgeRequest
import diw.wallet.core.profile.bonds.BondsAgeVerdict
import diw.wallet.core.profile.bonds.BondsAgeVerifier
import diw.wallet.core.profile.bonds.BondsCredentialSource
import diw.wallet.core.trust.DidTrust
import diw.wallet.core.trust.TrustTier
import diw.wallet.core.util.str
import diw.wallet.core.zk.OpenAcAgeVerifier
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Limits of the Bonds age verifier: sessions at once, sessions one address may hold, and a posted package's size. */
data class BondsAgeLimits(
    val maxSessions: Int = 64,
    val perAddress: Int = 8,
    val maxPackageBytes: Int = 6 shl 20,
)

/**
 * A Bonds-compatible web age verifier (the `/api/zkp` API of Bonds' verifier site, wallet docs/research/bonds.md
 * section 7.1). In "relay" mode the session's creator, a wallet acting as verifier, fetches the package with its token,
 * verifies it on its own device and posts the verdict, which goes back to the holder still waiting on its POST. In
 * "server" mode [engine] checks the proofs here, one at a time; a government card's issuer must be at T2 or better in
 * [didTrust]. Sessions live in memory only and nothing is logged.
 */
class BondsAge(
    private val publicUrl: String,
    private val limits: BondsAgeLimits,
    private val resolver: DidResolver,
    private val didTrust: DidTrust,
    /** The server-side verifier, or a provider that is null until its assets are installed. */
    private val engine: () -> OpenAcAgeVerifier?,
) {
    class Session(val id: String, val token: String, val request: BondsAgeRequest, val relay: Boolean, val creator: String) {
        @Volatile var pkg: String? = null
        val verdict = CompletableDeferred<BondsAgeVerdict>()
        val deadline: Instant get() = request.expiresAt + GRACE
    }

    sealed interface Created {
        data class Ok(val session: Session) : Created
        data class Refused(val status: HttpStatusCode, val error: String, val description: String) : Created
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()
    private val verifying = Semaphore(1)

    val serverVerification: Boolean get() = engine() != null

    /** Sessions waiting or in use. */
    val size: Int get() = sessions.size

    fun create(source: BondsCredentialSource, minimumAge: Int, purpose: String, relay: Boolean, address: String): Created {
        if (!relay && !serverVerification) {
            return Created.Refused(HttpStatusCode.ServiceUnavailable, "unavailable", "This server does not verify proofs; use relay")
        }
        val now = Clock.System.now()
        synchronized(sessions) {
            sessions.values.removeIf { now > it.deadline }
            if (sessions.size >= limits.maxSessions || sessions.values.count { it.creator == address } >= limits.perAddress) {
                return Created.Refused(HttpStatusCode.TooManyRequests, "busy", "Too many open sessions")
            }
            val id = token(16)
            val request = BondsAgeRequest.create(minimumAge, source, purpose, "$publicUrl/api/zkp/response/$id", now)
            return Created.Ok(Session(id, token(32), request, relay, address).also { sessions[id] = it })
        }
    }

    fun session(id: String, token: String?): Session? = sessions[id]?.takeIf {
        token != null && MessageDigest.isEqual(it.token.encodeToByteArray(), token.encodeToByteArray()) && Clock.System.now() <= it.deadline
    }

    /** The holder's POST: the HTTP status and verdict to answer with. */
    suspend fun respond(id: String, body: String): Pair<HttpStatusCode, BondsAgeVerdict> {
        val s = sessions[id]?.takeIf { Clock.System.now() <= it.deadline }
            ?: return HttpStatusCode.Gone to failed("查驗請求不存在或已過期")
        if (!s.request.isFresh(Clock.System.now())) return HttpStatusCode.Gone to failed("查驗請求已過期")
        val pkg = runCatching { BondsAgePackage.parse(body, s.request) }.getOrNull()
            ?: return HttpStatusCode.BadRequest to failed("證明回應缺少必要欄位")
        val conflict = HttpStatusCode.Conflict to failed("這個查驗請求已收到證明")
        if (s.relay) {
            if (!claim(s, body)) return conflict
            try {
                val verdict = withTimeoutOrNull(RELAY_WAIT) { s.verdict.await() }
                    ?: return HttpStatusCode.GatewayTimeout to failed("查驗方沒有在時限內回覆")
                return HttpStatusCode.OK to verdict
            } finally {
                // Answered or abandoned: the creator can no longer fetch it or decide.
                s.pkg = ANSWERED
            }
        }
        val engine = engine() ?: return HttpStatusCode.ServiceUnavailable to failed("查驗端沒有零知識驗證引擎")
        // Each check loads the 432 MB Prepare verifying key; a second holder retries rather than doubling memory.
        if (!verifying.tryAcquire()) return HttpStatusCode.ServiceUnavailable to failed("查驗端忙碌中，請稍後再試")
        try {
            if (!claim(s, ANSWERED)) return conflict
            val verdict = try {
                BondsAgeVerifier.verify(pkg, engine, resolver, didTrust, TrustTier.T2).verdict()
            } catch (_: Exception) {
                // The engine failed, not the proof: the holder may post again.
                s.pkg = null
                return HttpStatusCode.BadGateway to failed("驗證後端發生錯誤")
            }
            s.verdict.complete(verdict)
            return HttpStatusCode.OK to verdict
        } finally {
            verifying.release()
        }
    }

    /** Marks [s] as answered with [value] (the package for a relay); false when a package already came. */
    private fun claim(s: Session, value: String): Boolean = synchronized(s) {
        if (s.pkg != null) false else { s.pkg = value; true }
    }

    /** The verdict the relaying creator reached on its own device; false when the session has none waiting. */
    fun decide(s: Session, verdict: BondsAgeVerdict): Boolean =
        s.relay && s.pkg.let { it != null && it != ANSWERED } && s.verdict.complete(verdict)

    private fun token(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    private fun failed(reason: String) = BondsAgeVerdict("failed", false, reason, null, null)

    companion object {
        val RELAY_WAIT = 75.seconds
        val GRACE = 60.seconds
        private const val ANSWERED = ""
    }
}

fun Route.bondsAgeRoutes(service: BondsAge, limits: BondsAgeLimits) {
    get("/api/zkp/config") {
        val server = service.serverVerification
        call.respondJson(buildJsonObject {
            put("configured", true)
            put("backend", if (server) "native" else "relay")
            putJsonArray("modes") {
                if (server) add(JsonPrimitive("server"))
                add(JsonPrimitive("relay"))
            }
            put("requestLifetimeMs", BondsAgeRequest.LIFETIME.inWholeMilliseconds)
            putJsonArray("minimumAgeRange") { add(JsonPrimitive(1)); add(JsonPrimitive(120)) }
        })
    }
    post("/api/zkp/sessions") {
        val body = call.receiveLimited(16 shl 10)?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "JSON body required")
        val source = when (body.str("credentialSource")) {
            "selfIssued", "s" -> BondsCredentialSource.SELF_ISSUED
            "government", "g", null -> BondsCredentialSource.TWDIW
            else -> return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "credentialSource is selfIssued or government")
        }
        val age = (body["minimumAge"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 18
        if (age !in 1..120) return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "minimumAge out of range")
        val relay = when (body.str("mode")) {
            "relay" -> true
            "server", null -> false
            else -> return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "mode is server or relay")
        }
        when (val created = service.create(source, age, body.str("purpose").orEmpty(), relay, call.request.origin.remoteAddress)) {
            is BondsAge.Created.Refused -> call.respondError(created.status, created.error, created.description)
            is BondsAge.Created.Ok -> {
                val s = created.session
                call.respondJson(buildJsonObject {
                    put("sessionId", s.id)
                    put("token", s.token)
                    put("request", s.request.encode())
                    put("responseUrl", s.request.responseUrl)
                    put("expiresAt", s.request.expiresAt.toEpochMilliseconds())
                    put("mode", if (relay) "relay" else "server")
                })
            }
        }
    }
    post("/api/zkp/response/{id}") {
        val body = call.receiveLimited(limits.maxPackageBytes)
            ?: return@post call.respondText(BondsAgeVerdict("failed", false, "證明回應過大", null, null).encode(),
                ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
        val (status, verdict) = service.respond(call.parameters["id"].orEmpty(), body)
        call.respondText(verdict.encode(), ContentType.Application.Json, status)
    }
    get("/api/zkp/sessions/{id}") {
        val s = service.session(call.parameters["id"].orEmpty(), call.request.queryParameters["token"])
            ?: return@get call.respondError(HttpStatusCode.NotFound, "not_found", "Unknown or expired session")
        val verdict = s.verdict.takeIf { it.isCompleted }?.getCompleted()
        val pkg = s.pkg?.takeIf { it.isNotEmpty() }
        call.respondJson(buildJsonObject {
            put("state", if (verdict != null) "done" else if (s.pkg != null) "received" else "waiting")
            if (s.relay && verdict == null) pkg?.let { put("package", it) }
            verdict?.let { put("verdict", Json.parseToJsonElement(it.encode())) }
        })
    }
    post("/api/zkp/sessions/{id}/verdict") {
        val s = service.session(call.parameters["id"].orEmpty(), call.request.queryParameters["token"])
            ?: return@post call.respondError(HttpStatusCode.NotFound, "not_found", "Unknown or expired session")
        val verdict = call.receiveLimited(16 shl 10)?.let { BondsAgeVerdict.parse(it) }
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Verdict JSON with status and accepted required")
        if (!service.decide(s, verdict)) return@post call.respondError(HttpStatusCode.Conflict, "conflict", "No package is waiting for a verdict")
        call.respondJson(buildJsonObject { put("ok", true) })
    }
}
