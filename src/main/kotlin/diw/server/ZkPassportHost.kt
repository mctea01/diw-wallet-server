package diw.server

import diw.wallet.core.util.DiwJson
import diw.wallet.core.zkpassport.ZkPassportProver
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Proves one zkPassport request from chip files, reporting progress; cancelled with its coroutine. */
fun interface ZkPassportProving {
    suspend fun prove(
        chip: ZkPassportProver.Chip,
        job: ZkPassportProver.Job,
        progress: (ZkPassportProver.Progress) -> Unit,
    ): ZkPassportProver.Proved
}

/**
 * The owner's own zkPassport prover (DESIGN 3.9): the wallet sends a passport's SOD and DG1 with the request it
 * answers and fetches the proofs it then sends through zkPassport's bridge itself. Proving is the wallet core's
 * [ZkPassportProver], so the same registry and key checks run as on the phone. Chip data and results live only in
 * memory, one job proves at a time, and a job is dropped once its outcome has been read or after [keep].
 */
class ZkPassportHost(
    private val proving: ZkPassportProving,
    private val queue: Int,
    private val rpc: (devMode: Boolean) -> String?,
    private val available: () -> Boolean,
    private val keep: Duration = 10.minutes,
    private val clock: () -> Instant = { Clock.System.now() },
) {
    private class Entry(val chip: ZkPassportProver.Chip, val job: ZkPassportProver.Job, val created: Instant) {
        @Volatile var state: JsonObject = buildJsonObject { put("state", "queued") }
        @Volatile var finished: Instant? = null
        @Volatile var worker: Job? = null
    }

    private val jobs = LinkedHashMap<String, Entry>()
    private val running = Mutex()
    private val random = SecureRandom()

    val isAvailable: Boolean get() = available()

    sealed interface Submitted {
        data class Ok(val id: String) : Submitted
        data object Invalid : Submitted
        data object Busy : Submitted
    }

    /** Starts proving [request] in [scope]; [Submitted.Busy] when [queue] jobs already wait. */
    fun submit(request: JsonObject, scope: CoroutineScope): Submitted {
        val (chip, job) = parse(request) ?: return Submitted.Invalid
        val id = synchronized(jobs) {
            prune()
            val waiting = jobs.values.count { it.finished == null }
            if (waiting > queue) return Submitted.Busy
            val id = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            jobs[id] = Entry(chip, job, clock())
            id
        }
        val entry = synchronized(jobs) { jobs.getValue(id) }
        entry.worker = scope.launch {
            try {
                running.withLock {
                    if (!isActive) return@withLock
                    val proved = proving.prove(entry.chip, entry.job) { p -> entry.state = progress(p) }
                    finish(entry, buildJsonObject {
                        put("state", "done")
                        put("result", result(proved))
                    })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ZkPassportProver.Failure) {
                finish(entry, failed(e.code))
            } catch (e: Exception) {
                finish(entry, failed("other"))
            } finally {
                synchronized(jobs) { if (entry.finished == null) entry.finished = clock() }
            }
        }
        return Submitted.Ok(id)
    }

    /** Sets the outcome and marks the job finished together, so a read never sees one without the other. */
    private fun finish(entry: Entry, state: JsonObject) = synchronized(jobs) {
        entry.state = state
        entry.finished = clock()
    }

    /** The job's state; a finished job is dropped once its outcome is read. Null for an unknown id. */
    fun status(id: String): JsonObject? = synchronized(jobs) {
        prune()
        val entry = jobs[id] ?: return null
        val state = entry.state
        if (entry.finished != null) jobs.remove(id)
        state
    }

    /** Cancels and drops the job; false for an unknown id. */
    fun cancel(id: String): Boolean {
        val entry = synchronized(jobs) { jobs.remove(id) } ?: return false
        entry.worker?.cancel()
        return true
    }

    /** Drops jobs whose outcome nobody fetched in time, and cancels any still running past it. */
    private fun prune() {
        val now = clock()
        val stale = jobs.filterValues { e -> now - (e.finished ?: e.created) > keep }.keys
        for (id in stale) jobs.remove(id)?.worker?.cancel()
    }

    /** Periodic pruning, so an unread outcome does not stay in memory until the next request. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(30.seconds)
                synchronized(jobs) { prune() }
            }
        }
    }

    private fun parse(o: JsonObject): Pair<ZkPassportProver.Chip, ZkPassportProver.Job>? {
        fun text(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val decoder = Base64.getDecoder()
        val sod = text("sod")?.let { runCatching { decoder.decode(it) }.getOrNull() }?.takeIf { it.size in 1..MAX_FILE } ?: return null
        val dg1 = text("dg1")?.let { runCatching { decoder.decode(it) }.getOrNull() }?.takeIf { it.size in 1..MAX_FILE } ?: return null
        val query = o["query"] as? JsonObject ?: return null
        val domain = text("domain")?.takeIf { DOMAIN.matches(it) } ?: return null
        val scope = text("scope")?.takeIf { it.length <= 256 && it.none(Char::isISOControl) }
        if (o["scope"] != null && scope == null) return null
        val devMode = (o["dev_mode"] as? JsonPrimitive)?.booleanOrNull ?: false
        val version = text("circuit_version")?.takeIf { VERSION.matches(it) } ?: ZkPassportProver.CIRCUIT_VERSION
        // The server's clock dates the proofs; the request cannot choose another day.
        val job = ZkPassportProver.Job(
            query = query, domain = domain, scope = scope, devMode = devMode, circuitVersion = version,
            rpc = rpc(devMode), nowSeconds = clock().epochSeconds,
        )
        // DG2 stays on the phone: its hash comes from the SOD.
        return ZkPassportProver.Chip(sod, dg1, null) to job
    }

    private fun progress(p: ZkPassportProver.Progress): JsonObject = buildJsonObject {
        when (p) {
            ZkPassportProver.Progress.Registry -> put("state", "registry")
            is ZkPassportProver.Progress.Circuits -> {
                put("state", "proving")
                put("index", 0)
                put("total", p.count)
            }
            is ZkPassportProver.Progress.Proving -> {
                put("state", "proving")
                put("index", p.index)
                put("total", p.total)
                put("name", p.name)
            }
        }
    }

    private fun failed(code: String) = buildJsonObject {
        put("state", "failed")
        put("code", code.filter { it in ' '..'~' }.take(160))
    }

    companion object {
        /** Room for two base64 chip files, sealed once more by a paired wallet. */
        const val MAX_BODY = 512 shl 10
        private const val MAX_FILE = 64 shl 10
        private val DOMAIN = Regex("^[A-Za-z0-9.:-]{1,253}$")
        private val VERSION = Regex("^[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}$")
        private val ID = Regex("^[0-9a-f]{32}$")

        fun validId(id: String?): Boolean = id != null && ID.matches(id)

        /** [ZkPassportProver.Proved] as the wallet reads it back (ZkPassportRemoteProver in the wallet core). */
        fun result(p: ZkPassportProver.Proved): JsonObject = buildJsonObject {
            put("proofs", buildJsonArray {
                for (proof in p.proofs) add(buildJsonObject {
                    put("name", proof.name)
                    put("proof", proof.proof)
                    put("vkey", proof.vkey)
                    put("vkey_hash", proof.vkeyHash)
                    put("version", proof.version)
                    proof.committedInputs?.let { put("committed_inputs", it) }
                })
            })
            put("messages", JsonArray(p.messages.map { (method, params) ->
                buildJsonObject {
                    put("method", method)
                    put("params", params)
                }
            }))
            put("query_result", p.queryResult)
        }
    }
}

// Pairing checks the connection's own peer address; diw-server prover's TLS front already admits private peers only.
/** How a call got in: a token answers in plain JSON, a paired session in sealed JSON (DESIGN 3.10). */
private class Caller(val seal: ZkPassportPairing.Seal?) {
    fun open(body: JsonObject): JsonObject? = if (seal == null) body else seal.open(body)
    fun out(body: JsonObject): JsonObject = seal?.seal(body) ?: body
}

fun Route.zkPassportRoutes(host: ZkPassportHost, tokenHashes: List<ByteArray>, pairing: ZkPassportPairing?, scope: CoroutineScope) {
    suspend fun ApplicationCall.caller(): Caller? {
        val header = request.header("Authorization")
        if (bearerMatches(header, tokenHashes)) return Caller(null)
        if (pairing != null && ZkPassportPairing.privateAddress(request.local.remoteAddress)) {
            pairing.authorize(header, request.httpMethod.value, request.path())?.let { return Caller(it) }
        }
        respondError(HttpStatusCode.Unauthorized, "unauthorized", "這台服務端的 zkPassport 證明器需要存取權杖或配對")
        return null
    }
    suspend fun ApplicationCall.unavailable(): Boolean {
        if (host.isAvailable) return false
        respondError(HttpStatusCode.ServiceUnavailable, "unavailable", "The native library has no UltraHonk prover")
        return true
    }
    suspend fun ApplicationCall.body(): JsonObject? =
        receiveLimited(ZkPassportHost.MAX_BODY)?.let { runCatching { DiwJson.parseToJsonElement(it) as JsonObject }.getOrNull() }

    if (pairing != null) {
        post("/zkpassport/v1/pair") {
            if (!ZkPassportPairing.privateAddress(call.request.local.remoteAddress)) {
                call.respondError(HttpStatusCode.Forbidden, "forbidden", "Pairing is only open to private networks")
                return@post
            }
            val o = call.body()
            val nonce = ZkPassportPairing.decode((o?.get("nonce") as? JsonPrimitive)?.contentOrNull)
            val mac = ZkPassportPairing.decode((o?.get("mac") as? JsonPrimitive)?.contentOrNull)
            if (nonce == null || mac == null) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Malformed request")
                return@post
            }
            when (val r = pairing.pair(nonce, mac)) {
                is ZkPassportPairing.Paired.Ok -> call.respondJson(buildJsonObject {
                    put("session", r.session)
                    put("nonce", r.nonce)
                })
                ZkPassportPairing.Paired.Refused -> call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "Wrong pairing code")
                ZkPassportPairing.Paired.Gone -> call.respondError(HttpStatusCode.Gone, "pairing_used", "The pairing code was used or expired; show a new one")
            }
        }
        delete("/zkpassport/v1/pair") {
            val caller = call.caller() ?: return@delete
            pairing.end(call.request.header("Authorization"))
            call.respondJson(caller.out(buildJsonObject { put("state", "ended") }))
        }
    }
    post("/zkpassport/v1/jobs") {
        val caller = call.caller() ?: return@post
        if (call.unavailable()) return@post
        val o = call.body()?.let(caller::open)
        if (o == null) {
            call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Malformed request")
            return@post
        }
        when (val r = host.submit(o, scope)) {
            is ZkPassportHost.Submitted.Ok -> call.respondJson(caller.out(buildJsonObject { put("id", r.id) }), HttpStatusCode.Accepted)
            ZkPassportHost.Submitted.Invalid -> call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Malformed request")
            ZkPassportHost.Submitted.Busy -> call.respondError(HttpStatusCode.TooManyRequests, "busy", "Too many jobs waiting")
        }
    }
    get("/zkpassport/v1/jobs/{id}") {
        val caller = call.caller() ?: return@get
        val id = call.parameters["id"]
        val state = id?.takeIf(ZkPassportHost::validId)?.let(host::status)
        if (state == null) call.respondError(HttpStatusCode.NotFound, "unknown_job", "No such job")
        else call.respondJson(caller.out(state))
    }
    delete("/zkpassport/v1/jobs/{id}") {
        val caller = call.caller() ?: return@delete
        val id = call.parameters["id"]
        if (id != null && ZkPassportHost.validId(id) && host.cancel(id)) call.respondJson(caller.out(buildJsonObject { put("state", "cancelled") }))
        else call.respondError(HttpStatusCode.NotFound, "unknown_job", "No such job")
    }
}
