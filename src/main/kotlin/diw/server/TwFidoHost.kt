package diw.server

import diw.wallet.core.document.sign.MobileMoicaCredentials
import diw.wallet.core.document.sign.MobileMoicaEnvironment
import diw.wallet.core.document.sign.MobileMoicaException
import diw.wallet.core.document.sign.MobileMoicaResult
import diw.wallet.core.document.sign.MobileMoicaTicket
import diw.wallet.core.document.sign.TwFidoClient
import diw.wallet.core.profile.bonds.MyDataProfile
import diw.wallet.core.util.DiwJson
import diw.wallet.core.util.str
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.multipaz.util.toBase64Url
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Limits of the mobile citizen certificate signing service: requests at once, per address, per ID number. */
data class TwFidoLimits(val maxSessions: Int = 16, val perAddress: Int = 4, val perId: Int = 2)

/**
 * Mobile citizen certificate (TW FidO) signing with this server's SP account, so the SP key stays here instead of in
 * every app (DESIGN 9). The wallet sends the ID number, a hint and the bytes to sign; this host asks the Ministry of
 * the Interior's service, checks idp_checksum with the SP key and hands back the signature and certificate. The
 * wallet checks the signature against the certificate and the certificate against MOICA itself. ID numbers, hints,
 * signed bytes and results stay in memory only, are never logged, and are dropped a few minutes after expiry.
 */
class TwFidoHost(
    http: HttpClient,
    val environment: MobileMoicaEnvironment,
    credentials: MobileMoicaCredentials,
    private val limits: TwFidoLimits,
    baseUrl: String = environment.baseUrl,
) {
    private val client = TwFidoClient(http, environment, credentials, baseUrl)

    class Session(
        val id: String,
        val token: String,
        val ticket: MobileMoicaTicket,
        val creator: String,
        /** SHA-256 of the ID number, to bound requests per holder without keeping the number. */
        val holder: String,
        val expiresAt: Instant,
    ) {
        val lock = Mutex()
        @Volatile var status: String = "pending"
        @Volatile var result: MobileMoicaResult? = null
        @Volatile var code: String? = null
        @Volatile var reason: String? = null
        @Volatile var lastPoll: Instant? = null
    }

    sealed interface Created {
        data class Ok(val session: Session) : Created
        data class Refused(val status: HttpStatusCode, val error: String, val message: String) : Created
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()
    private val admission = Mutex()

    /** A request being placed with the service; identity, not equality, tells two of them apart. */
    private class Reservation(val creator: String, val holder: String)

    private val reserved = ArrayList<Reservation>()

    private fun expired(s: Session, now: Instant) = now > s.expiresAt + KEPT

    private fun prune() {
        val now = Clock.System.now()
        sessions.values.removeIf { expired(it, now) }
    }

    /** Drops expired requests and their results every minute. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                delay(1.minutes)
                prune()
            }
        }
    }

    suspend fun create(mode: String, idNumber: String, hint: String, data: ByteArray, timeLimit: Int, creator: String): Created {
        val id = MyDataProfile.normalizeId(idNumber)
        if (!MyDataProfile.isValidId(id)) return Created.Refused(HttpStatusCode.BadRequest, "invalid_request", "身分證統一編號格式不正確")
        if (mode != MODE_PUSH && mode != MODE_APP2APP) return Created.Refused(HttpStatusCode.BadRequest, "invalid_request", "mode 必須是 push 或 app2app")
        if (hint.isBlank() || hint.length > MAX_HINT || hint.any { it.isISOControl() }) {
            return Created.Refused(HttpStatusCode.BadRequest, "invalid_request", "提示文字需為 1 到 $MAX_HINT 個字")
        }
        if (data.isEmpty() || data.size > MAX_DATA) return Created.Refused(HttpStatusCode.BadRequest, "invalid_request", "要簽署的資料需為 1 到 $MAX_DATA 位元組")
        if (timeLimit !in TwFidoClient.TIME_LIMITS) {
            return Created.Refused(HttpStatusCode.BadRequest, "invalid_request", "等待時間必須在 ${TwFidoClient.TIME_LIMITS.first} 到 ${TwFidoClient.TIME_LIMITS.last} 秒之間")
        }
        val holder = SigningIdentity.sha256Hex(id.encodeToByteArray())
        // The limits count requests still being placed with the service, so the call itself runs outside the lock.
        val reservation = Reservation(creator, holder)
        admission.withLock {
            prune()
            val open = sessions.values.filter { it.status == "pending" }.map { Reservation(it.creator, it.holder) } + reserved
            if (sessions.size + reserved.size >= limits.maxSessions || open.count { it.creator == creator } >= limits.perAddress ||
                open.count { it.holder == holder } >= limits.perId
            ) {
                return Created.Refused(HttpStatusCode.TooManyRequests, "busy", "簽署請求太多，請稍後再試")
            }
            reserved += reservation
        }
        var session: Session? = null
        try {
            val ticket = try {
                if (mode == MODE_PUSH) client.requestPush(id, hint, data, timeLimit) else client.requestAppToApp(id, hint, data, timeLimit)
            } catch (e: MobileMoicaException) {
                return Created.Refused(HttpStatusCode.BadGateway, "upstream", e.message ?: "行動自然人憑證服務拒絕要求")
            }
            session = Session(
                ByteArray(16).also(random::nextBytes).toBase64Url(), ByteArray(32).also(random::nextBytes).toBase64Url(),
                ticket, creator, holder, Clock.System.now() + timeLimit.seconds + GRACE,
            )
            return Created.Ok(session)
        } finally {
            // Also on cancellation, so a dropped request never keeps its place against the limits.
            withContext(NonCancellable) {
                admission.withLock {
                    reserved -= reservation
                    session?.let { sessions[it.id] = it }
                }
            }
        }
    }

    fun session(id: String, token: String?): Session? = sessions[id]?.takeIf {
        token != null && MessageDigest.isEqual(it.token.encodeToByteArray(), token.encodeToByteArray()) && !expired(it, Clock.System.now())
    }

    /** One poll by the wallet; the service is asked at most every [MIN_POLL], however often the wallet polls. */
    suspend fun poll(s: Session): JsonObject = s.lock.withLock {
        val now = Clock.System.now()
        if (s.status == "pending" && now > s.expiresAt) {
            s.status = "failed"
            s.reason = "等待逾時：沒有在時限內於行動自然人憑證 App 完成簽署"
        }
        if (s.status == "pending" && s.lastPoll.let { it == null || now - it >= MIN_POLL }) {
            s.lastPoll = now
            try {
                client.result(s.ticket)?.let {
                    s.result = it
                    s.status = "done"
                }
            } catch (e: MobileMoicaException) {
                s.code = e.code
                s.reason = e.message
                s.status = "failed"
            }
        }
        state(s)
    }

    private fun state(s: Session): JsonObject = buildJsonObject {
        put("status", s.status)
        s.result?.let {
            put("signature", Base64.getEncoder().encodeToString(it.signature))
            put("certificate", Base64.getEncoder().encodeToString(it.certificate))
            put("hashedIdNumber", it.hashedIdNumber)
        }
        s.code?.let { put("code", it) }
        s.reason?.let { put("reason", it) }
    }

    companion object {
        const val MODE_PUSH = "push"
        const val MODE_APP2APP = "app2app"
        const val MAX_HINT = 60
        /** The bytes signed are a CMS signed-attributes block or a short text, never a whole document. */
        const val MAX_DATA = 8 shl 10
        val GRACE = 10.seconds
        val KEPT = 2.minutes
        val MIN_POLL = 3.seconds
    }
}

fun Route.twFidoRoutes(host: TwFidoHost, tokenHashes: List<ByteArray>) {
    post("/twfido/sign") {
        if (!bearerMatches(call.request.header("Authorization"), tokenHashes)) {
            call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "這台服務端的行動自然人憑證簽署需要存取權杖")
            return@post
        }
        val o = call.receiveLimited(32 shl 10)?.let { runCatching { DiwJson.parseToJsonElement(it) as JsonObject }.getOrNull() }
        val data = o?.str("data")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        if (o == null || data == null) {
            call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Malformed request")
            return@post
        }
        val timeLimit = (o["timeLimit"] as? JsonPrimitive)?.intOrNull ?: 180
        when (val created = host.create(o.str("mode").orEmpty(), o.str("idNumber").orEmpty(), o.str("hint").orEmpty(), data, timeLimit,
            call.request.origin.remoteAddress)) {
            is TwFidoHost.Created.Refused -> call.respondError(created.status, created.error, created.message)
            is TwFidoHost.Created.Ok -> {
                val s = created.session
                call.respondJson(buildJsonObject {
                    put("sessionId", s.id)
                    put("token", s.token)
                    // For app-to-app the wallet opens the official app with this ticket; it holds no key material.
                    put("spTicket", s.ticket.spTicket)
                    put("environment", host.environment.name)
                    put("expiresAt", s.expiresAt.epochSeconds)
                })
            }
        }
    }
    get("/twfido/sign/{sid}") {
        val s = host.session(call.parameters["sid"].orEmpty(), call.request.queryParameters["token"])
        if (s == null) call.respondError(HttpStatusCode.NotFound, "not_found", "No such request")
        else call.respondJson(host.poll(s))
    }
}
