package diw.server

import diw.wallet.core.util.DiwJson
import diw.wallet.core.util.ProtocolException
import diw.wallet.core.util.str
import diw.wallet.core.util.strList
import diw.wallet.core.zk.ZkResult
import diw.wallet.core.zk.ZkTemplatePack
import diw.wallet.core.zk.native.bindings.ZkException
import diw.wallet.core.zktls.NativeZkTlsEngine
import diw.wallet.core.zktls.WebProofCredentials
import diw.wallet.core.zktls.WebSocketChannel
import diw.wallet.core.zktls.ZkTlsCheck
import diw.wallet.core.zktls.ZkTlsKey
import diw.wallet.core.zktls.ZkTlsOutcome
import diw.wallet.core.zktls.ZkTlsProfile
import diw.wallet.core.zktls.ZkTlsProfiles
import diw.wallet.core.zktls.ZkTlsReceipts
import diw.wallet.core.zktls.ZkTlsSessionRequest
import diw.wallet.core.zktls.ZkTlsTransport
import diw.wallet.core.zktls.ZkTlsVerification
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.crypto.EcPublicKey
import org.multipaz.util.toBase64Url
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Sessions at once, sessions one address may hold, and proofs checked at the same time (each holds the MPC state). */
data class ZkTlsLimits(val maxSessions: Int = 16, val perAddress: Int = 4, val concurrent: Int = 1)

private val log = LoggerFactory.getLogger("diw.server.zktls")

/**
 * The signed page templates this host offers, the same pack the wallet installs: the newest valid one of the copy
 * kept in [dataDir], [file] when the operator names one, and the project's release (unless [file] is set). Only
 * packs signed with one of [keys] (the release key pinned in the wallet's core) are installed, into the shared
 * [ZkTlsProfiles]; a request names an installed page and never carries its own definition.
 */
class ZkTemplates(
    private val http: HttpClient,
    dataDir: File,
    private val file: File?,
    private val keys: List<EcPublicKey> = ZkTemplatePack.trustedKeys(),
) {
    private val cached = File(dataDir, ZkTemplatePack.FILE)

    val version: Long? get() = ZkTlsProfiles.packVersion

    private suspend fun install(jws: String, source: String): Boolean = try {
        val pack = ZkTemplatePack.verify(jws, keys, ZkTlsProfiles.packVersion ?: 0)
        val newer = pack.version > (ZkTlsProfiles.packVersion ?: 0)
        ZkTlsProfiles.install(pack)
        if (newer) {
            cached.writeText(jws.trim())
            log.info("Template pack {} from {}: {} pages", pack.version, source, pack.zktls.size)
        }
        true
    } catch (e: ProtocolException) {
        log.warn("Template pack from {} not installed: {}", source, e.message)
        false
    }

    suspend fun refresh() {
        if (cached.isFile && ZkTlsProfiles.packVersion == null) install(cached.readText(), "the kept copy")
        if (file != null) {
            if (file.isFile) install(file.readText(), file.path) else log.warn("zktls_templates_file {} not found", file.path)
            return
        }
        for ((name, url) in ZkTemplatePack.SOURCES) {
            val text = runCatching { http.get(url).takeIf { it.status.isSuccess() }?.bodyAsText() }.getOrNull() ?: continue
            install(text, name)
        }
    }

    /** Loads the pack now and again every twelve hours. */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { refresh() }.onFailure { log.warn("Template pack refresh failed: {}", it.message) }
                delay(12.hours)
            }
        }
    }
}

/**
 * The zkTLS verifier host (DESIGN 3.2): an authorised client creates a session for an installed page; the holder
 * connects to `/zktls/sessions/{sid}/ws` and proves the page here in proxy mode, so this host connects only to the
 * page's own host and port. Requests and web-proof receipts are signed with [identity]. Sessions live in memory;
 * outcomes are kept until a few minutes after the request expires and are never logged. [roots] are the server
 * roots TLSNotary accepts besides the public web roots (the GPKI anchors, as in the wallet).
 */
class ZkTlsHost(
    private val publicUrl: String,
    private val identity: SigningIdentity,
    private val limits: ZkTlsLimits,
    private val roots: () -> List<ByteArray>,
    private val available: () -> Boolean,
) {
    class Session(
        val id: String,
        val token: String,
        val request: ZkTlsSessionRequest,
        val jws: String,
        val profile: ZkTlsProfile,
        val key: ZkTlsKey,
        val receipts: ZkTlsReceipts,
        val creator: String,
    ) {
        @Volatile var status: String = "waiting"
        @Volatile var receiptIssued: Boolean = false
        @Volatile var reason: String? = null
        @Volatile var outcome: ZkTlsOutcome? = null
    }

    sealed interface Created {
        data class Ok(val session: Session) : Created
        data class Refused(val status: HttpStatusCode, val error: String, val message: String) : Created
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()
    private val running = Semaphore(limits.concurrent)
    /** Makes the limit checks and the insertion one step, so concurrent requests cannot exceed the limits. */
    private val admission = Mutex()

    fun offered(): List<ZkTlsProfile> = ZkTlsProfiles.installed

    private fun expired(s: Session, now: Instant) = now > s.request.expiresAt + KEPT && s.status != "verifying"

    private fun prune() {
        val now = Clock.System.now()
        sessions.values.removeIf { expired(it, now) }
    }

    /** Drops expired sessions and their outcomes every minute, also when nobody calls the host. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                delay(1.minutes)
                prune()
            }
        }
    }

    suspend fun create(profileId: String, fields: List<String>, purpose: String, creator: String): Created {
        if (!available()) return Created.Refused(HttpStatusCode.ServiceUnavailable, "unsupported", "這台查驗主機沒有 zkTLS（TLSNotary）原生程式庫")
        val profile = ZkTlsProfiles.find(profileId)
            ?: return Created.Refused(HttpStatusCode.BadRequest, "invalid_request", "查驗頁 $profileId 不在這台主機已安裝的版型包中")
        return admission.withLock { admit(profile, fields, purpose, creator) }
    }

    private suspend fun admit(profile: ZkTlsProfile, fields: List<String>, purpose: String, creator: String): Created {
        prune()
        if (sessions.size >= limits.maxSessions || sessions.values.count { it.creator == creator } >= limits.perAddress) {
            return Created.Refused(HttpStatusCode.TooManyRequests, "busy", "查驗請求太多，請稍後再試")
        }
        val id = ByteArray(16).also(random::nextBytes).toBase64Url()
        val key = ZkTlsKey()
        val base = Url(publicUrl)
        val scheme = if (base.protocol.name == "https") "wss" else "ws"
        val port = if (base.port == base.protocol.defaultPort) "" else ":${base.port}"
        val transport = ZkTlsTransport.Wss("$scheme://${base.host}$port${base.encodedPath.trimEnd('/')}/zktls/sessions/$id/ws")
        val request = try {
            ZkTlsSessionRequest.create(listOf(transport), key.publicKey, profile, fields, purpose, "proxy", Clock.System.now(), sessionId = id)
        } catch (e: ProtocolException) {
            return Created.Refused(HttpStatusCode.BadRequest, e.code, e.message ?: "")
        }
        val jws = ZkTlsSessionRequest.sign(request, identity.key, chain = identity.chain)
        // Receipts are signed with the key that signed the request, so the holder can tie one to the other.
        val receipts = WebProofCredentials.receipts(request, profile, publicUrl, identity.key, identity.chain)
        val session = Session(id, ByteArray(32).also(random::nextBytes).toBase64Url(), request, jws, profile, key, receipts, creator)
        sessions[id] = session
        return Created.Ok(session)
    }

    /** The session for a poll; after its retention window it is gone even if no prune has run yet. */
    fun session(id: String, token: String?): Session? = sessions[id]?.takeIf {
        token != null && MessageDigest.isEqual(it.token.encodeToByteArray(), token.encodeToByteArray()) &&
            !expired(it, Clock.System.now())
    }

    /**
     * Verifies the holder on [channel] once. A second connection to the same session is refused; while another
     * proof is being checked the holder is told to try again and the session keeps waiting.
     */
    suspend fun run(id: String, channel: WebSocketChannel): String? {
        val s = sessions[id]?.takeIf { it.status == "waiting" && it.request.isFresh(Clock.System.now()) } ?: return "No such session"
        if (!running.tryAcquire()) return "Busy, try again"
        var started = false
        try {
            synchronized(s) {
                if (s.status != "waiting") return "No such session"
                s.status = "verifying"
                started = true
            }
            val result = withContext(Dispatchers.IO) {
                try {
                    ZkTlsVerification.verify(channel, s.jws, s.request, s.profile, s.key, roots()) { seen, jwk ->
                        s.receipts.issue(seen, jwk)?.also { s.receiptIssued = true }
                    }
                } catch (e: ZkException) {
                    NativeZkTlsEngine.failure(e)
                }
            }
            when (result) {
                is ZkResult.Ok -> {
                    s.outcome = ZkTlsCheck.check(s.request, s.profile, result.value)
                    s.status = "done"
                }
                is ZkResult.Failed -> {
                    s.reason = result.reason
                    s.status = "failed"
                }
                ZkResult.Cancelled -> {
                    s.reason = "查驗已取消"
                    s.status = "failed"
                }
            }
            return null
        } finally {
            // A dropped connection or a cancelled handler must not leave the session counting against the limits.
            if (started && s.status == "verifying") {
                s.reason = "查驗中斷"
                s.status = "failed"
            }
            running.release()
        }
    }

    fun state(s: Session): JsonObject = buildJsonObject {
        put("status", s.status)
        s.reason?.let { put("reason", it) }
        s.outcome?.let { put("outcome", it.toJson()) }
        if (s.receiptIssued) put("receipt", true)
    }

    companion object {
        /** How long a finished session stays readable after its request expires. */
        val KEPT = 5.minutes
    }
}

/** True when [header] is `Bearer <token>` for one of [hashes] (SHA-256 of the token). */
internal fun bearerMatches(header: String?, hashes: List<ByteArray>): Boolean {
    val given = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val digest = MessageDigest.getInstance("SHA-256").digest(given.encodeToByteArray())
    var ok = false
    for (h in hashes) ok = MessageDigest.isEqual(h, digest) || ok
    return ok
}

fun Route.zkTlsRoutes(host: ZkTlsHost, tokenHashes: List<ByteArray>) {
    get("/zktls/profiles") {
        val list = buildJsonArray { host.offered().forEach { add(DiwJson.encodeToJsonElement(ZkTlsProfile.serializer(), it)) } }
        call.respondJson(buildJsonObject { put("profiles", list) })
    }
    post("/zktls/sessions") {
        if (!bearerMatches(call.request.header("Authorization"), tokenHashes)) {
            call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "這台查驗主機需要存取權杖")
            return@post
        }
        val o = call.receiveLimited(16 shl 10)?.let { runCatching { DiwJson.parseToJsonElement(it) as JsonObject }.getOrNull() }
        if (o == null) {
            call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Malformed request")
            return@post
        }
        when (val created = host.create(o.str("profile").orEmpty(), o.strList("fields"), o.str("purpose").orEmpty().take(100), call.request.origin.remoteAddress)) {
            is ZkTlsHost.Created.Refused -> call.respondError(created.status, created.error, created.message)
            is ZkTlsHost.Created.Ok -> {
                val s = created.session
                call.respondJson(buildJsonObject {
                    put("sessionId", s.id)
                    put("token", s.token)
                    put("request", ZkTlsSessionRequest.link(s.jws))
                    put("expiresAt", s.request.expiresAt.epochSeconds)
                })
            }
        }
    }
    get("/zktls/sessions/{sid}") {
        val s = host.session(call.parameters["sid"].orEmpty(), call.request.queryParameters["token"])
        if (s == null) call.respondError(HttpStatusCode.NotFound, "not_found", "No such session")
        else call.respondJson(host.state(s))
    }
    webSocket("/zktls/sessions/{sid}/ws") {
        val refused = host.run(call.parameters["sid"].orEmpty(), WebSocketChannel(this))
        if (refused != null) close(CloseReason(if (refused.startsWith("Busy")) CloseReason.Codes.TRY_AGAIN_LATER else CloseReason.Codes.CANNOT_ACCEPT, refused))
    }
}
