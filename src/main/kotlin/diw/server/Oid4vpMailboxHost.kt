package diw.server

import diw.wallet.core.verifier.Oid4vpMailbox
import diw.wallet.core.verifier.OnlineVerifierRequests
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** Sessions the mailbox keeps at once, per client address, and the bytes of requests and responses it holds in all. */
data class MailboxLimits(val maxSessions: Int = 64, val perAddress: Int = 8, val maxStoredBytes: Long = 32L shl 20)

/**
 * The OpenID4VP mailbox (feature oid4vp, wallet docs/design/p5-verifier.md section 6.2): a phone acting as verifier
 * opens a session with its access token, puts its signed request object, and fetches the response the holder posted.
 * The response is the holder's encrypted direct_post.jwt, so the server sees neither the disclosed values nor the
 * request's key. Everything stays in memory: a session ends when its response is fetched, it is closed, or
 * [Oid4vpMailbox.LIFETIME] passes.
 */
class Oid4vpMailboxHost(
    publicUrl: String,
    private val limits: MailboxLimits = MailboxLimits(),
    private val clock: () -> Instant = { Clock.System.now() },
) {
    private val base = publicUrl.trimEnd('/')

    private class Session(val id: String, val secretHash: ByteArray, val address: String, val expiresAt: Instant) {
        var request: String? = null
        var response: Map<String, String>? = null
        val answered = CompletableDeferred<Unit>()
        val size: Long get() = (request?.length ?: 0).toLong() + (response?.entries?.sumOf { it.key.length + it.value.length } ?: 0)
    }

    private val sessions = HashMap<String, Session>()

    /** A new session: id, secret, request_uri, response_uri and lifetime in seconds. */
    data class Opened(val id: String, val secret: String, val requestUri: String, val responseUri: String, val expiresIn: Long)

    /** The live session count, for tests and health. */
    val size: Int get() = synchronized(sessions) { purge(); sessions.size }

    /** Opens a session for [address]; null when the server or that address has as many as it may keep. */
    fun open(address: String): Opened? = synchronized(sessions) {
        purge()
        if (sessions.size >= limits.maxSessions || sessions.values.count { it.address == address } >= limits.perAddress) return null
        var id: String
        do id = Oid4vpMailbox.newSecret() while (id in sessions)
        val secret = Oid4vpMailbox.newSecret()
        sessions[id] = Session(id, sha256(secret), address, clock() + Oid4vpMailbox.LIFETIME)
        Opened(id, secret, "$base${Oid4vpMailbox.REQUEST}/$id", "$base${Oid4vpMailbox.RESPONSE}/$id", Oid4vpMailbox.LIFETIME.inWholeSeconds)
    }

    enum class Put { OK, NOT_FOUND, FULL }

    /** Stores the signed request object the holder will fetch; a second put replaces the first. */
    fun putRequest(id: String, secret: String?, requestObject: String): Put = synchronized(sessions) {
        val s = owned(id, secret) ?: return Put.NOT_FOUND
        val other = stored() - s.size
        if (other + requestObject.length > limits.maxStoredBytes) return Put.FULL
        s.request = requestObject
        Put.OK
    }

    /** The request object of session [id], for the holder; null when there is none. */
    fun request(id: String): String? = synchronized(sessions) { live(id)?.request }

    enum class Post { OK, NOT_FOUND, ANSWERED, FULL }

    /** Keeps the first response the holder posts to session [id]; only the fields of a direct_post answer are kept. */
    fun postResponse(id: String, form: Map<String, String>): Post = synchronized(sessions) {
        val s = live(id) ?: return Post.NOT_FOUND
        if (s.request == null) return Post.NOT_FOUND
        if (s.response != null) return Post.ANSWERED
        val kept = form.filterKeys { it in RESPONSE_FIELDS }
        if (stored() + kept.entries.sumOf { it.key.length + it.value.length } > limits.maxStoredBytes) return Post.FULL
        s.response = kept
        s.answered.complete(Unit)
        Post.OK
    }

    sealed interface Fetch {
        data class Answer(val form: Map<String, String>) : Fetch
        data object Waiting : Fetch
        data object NotFound : Fetch
    }

    /**
     * Waits up to [wait] for the response of session [id]; once handed over, the session ends, so the server keeps
     * the response no longer than one fetch.
     */
    suspend fun awaitResponse(id: String, secret: String?, wait: Duration = Oid4vpMailbox.POLL_WAIT): Fetch {
        val s = synchronized(sessions) { owned(id, secret) } ?: return Fetch.NotFound
        val remaining = s.expiresAt - clock()
        withTimeoutOrNull(minOf(wait, remaining).coerceAtLeast(Duration.ZERO)) { s.answered.await() }
        return synchronized(sessions) {
            val form = s.response
            when {
                form != null -> {
                    sessions.remove(s.id)
                    Fetch.Answer(form)
                }
                sessions[s.id] !== s || clock() >= s.expiresAt -> Fetch.NotFound
                else -> Fetch.Waiting
            }
        }
    }

    /** Ends session [id] at once. */
    fun close(id: String, secret: String?): Boolean = synchronized(sessions) {
        val s = owned(id, secret) ?: return false
        sessions.remove(s.id)
        s.answered.cancel()
        true
    }

    private fun live(id: String): Session? {
        if (!Oid4vpMailbox.validId(id)) return null
        val s = sessions[id] ?: return null
        if (clock() >= s.expiresAt) {
            sessions.remove(id)
            s.answered.cancel()
            return null
        }
        return s
    }

    private fun owned(id: String, secret: String?): Session? {
        val s = live(id) ?: return null
        if (secret == null || !MessageDigest.isEqual(s.secretHash, sha256(secret))) return null
        return s
    }

    private fun stored(): Long = sessions.values.sumOf { it.size }

    private fun purge() {
        val now = clock()
        sessions.values.filter { now >= it.expiresAt }.forEach {
            sessions.remove(it.id)
            it.answered.cancel()
        }
    }

    companion object {
        /** What a holder's direct_post or direct_post.jwt answer carries (OpenID4VP 1.0 sections 8.2 and 8.3). */
        val RESPONSE_FIELDS = setOf("response", "vp_token", "state", "error", "error_description")

        private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.encodeToByteArray())
    }
}

private fun RoutingCall.bearer(): String? =
    request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }

fun Route.oid4vpMailboxRoutes(host: Oid4vpMailboxHost, tokenHashes: List<ByteArray>) {
    post(Oid4vpMailbox.SESSIONS) {
        if (!bearerMatches(call.request.header(HttpHeaders.Authorization), tokenHashes)) {
            call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "這台服務端的線上查驗轉送需要存取權杖")
            return@post
        }
        call.receiveLimited(1 shl 10)
        val s = host.open(call.request.origin.remoteAddress)
        if (s == null) {
            call.respondError(HttpStatusCode.TooManyRequests, "rate_limited", "Too many open sessions")
            return@post
        }
        call.respondJson(buildJsonObject {
            put("id", s.id)
            put("secret", s.secret)
            put("request_uri", s.requestUri)
            put("response_uri", s.responseUri)
            put("expires_in", s.expiresIn)
        }, HttpStatusCode.Created)
    }
    put(Oid4vpMailbox.SESSIONS + "/{id}/request") {
        val id = call.parameters["id"].orEmpty()
        val body = call.receiveLimited(Oid4vpMailbox.MAX_REQUEST_BYTES)?.trim()
        if (body == null || body.count { it == '.' } != 2) {
            call.respondError(HttpStatusCode.BadRequest, "invalid_request", "The body must be one signed request object")
            return@put
        }
        when (host.putRequest(id, call.bearer(), body)) {
            Oid4vpMailboxHost.Put.OK -> call.respond(HttpStatusCode.NoContent)
            Oid4vpMailboxHost.Put.NOT_FOUND -> call.respondError(HttpStatusCode.NotFound, "not_found", "No such session")
            Oid4vpMailboxHost.Put.FULL -> call.respondError(HttpStatusCode.ServiceUnavailable, "busy", "The mailbox is full")
        }
    }
    get(Oid4vpMailbox.SESSIONS + "/{id}/response") {
        when (val r = host.awaitResponse(call.parameters["id"].orEmpty(), call.bearer())) {
            is Oid4vpMailboxHost.Fetch.Answer -> call.respondJson(buildJsonObject { r.form.forEach { (k, v) -> put(k, v) } })
            Oid4vpMailboxHost.Fetch.Waiting -> call.respond(HttpStatusCode.NoContent)
            Oid4vpMailboxHost.Fetch.NotFound -> call.respondError(HttpStatusCode.NotFound, "not_found", "No such session")
        }
    }
    delete(Oid4vpMailbox.SESSIONS + "/{id}") {
        host.close(call.parameters["id"].orEmpty(), call.bearer())
        call.respond(HttpStatusCode.NoContent)
    }
    // The holder's side: the request_uri (GET, or POST under request_uri_method=post) and the response_uri.
    val serveRequest: suspend RoutingCall.() -> Unit = {
        val jws = host.request(parameters["id"].orEmpty())
        if (jws == null) respondError(HttpStatusCode.NotFound, "not_found", "No such request")
        else {
            response.header(HttpHeaders.CacheControl, "no-store")
            respondText(jws, ContentType.parse("application/oauth-authz-req+jwt"))
        }
    }
    get(Oid4vpMailbox.REQUEST + "/{id}") { call.serveRequest() }
    post(Oid4vpMailbox.REQUEST + "/{id}") {
        call.receiveLimited(64 shl 10)
        call.serveRequest()
    }
    post(Oid4vpMailbox.RESPONSE + "/{id}") {
        val body = call.receiveLimited(Oid4vpMailbox.MAX_RESPONSE_BYTES)
        if (body == null) {
            call.respondError(HttpStatusCode.PayloadTooLarge, "invalid_request", "The response is too large")
            return@post
        }
        val form = OnlineVerifierRequests.form(body.encodeToByteArray())
        if (form["response"] == null && form["vp_token"] == null && form["error"] == null) {
            call.respondError(HttpStatusCode.BadRequest, "invalid_request", "No response")
            return@post
        }
        when (host.postResponse(call.parameters["id"].orEmpty(), form)) {
            Oid4vpMailboxHost.Post.OK -> call.respondJson(JsonObject(emptyMap()))
            Oid4vpMailboxHost.Post.NOT_FOUND -> call.respondError(HttpStatusCode.NotFound, "not_found", "No such request")
            Oid4vpMailboxHost.Post.ANSWERED -> call.respondError(HttpStatusCode.Conflict, "invalid_request", "This request was already answered")
            Oid4vpMailboxHost.Post.FULL -> call.respondError(HttpStatusCode.ServiceUnavailable, "busy", "The mailbox is full")
        }
    }
}
