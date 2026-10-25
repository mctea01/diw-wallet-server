package diw.server

import diw.wallet.core.did.DidResolver
import diw.wallet.core.profile.twdiw.TwdiwTrustList
import diw.wallet.core.trust.DidTrust
import diw.wallet.core.zk.OpenAcAgeVerifier
import diw.wallet.core.zk.ZkBackend
import diw.wallet.core.zk.ZkResult
import diw.wallet.core.zk.native.FileZkAssetStore
import diw.wallet.core.zk.native.OpenAcAgeNativeVerifier
import diw.wallet.core.zk.native.ZkNative
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("diw.server")

/** Version from the jar manifest; "dev" when run from the build directory. */
val serverVersion: String = ServerConfig::class.java.`package`?.implementationVersion ?: "dev"

/** What the server runs, built once from [config]. */
class Server(
    val config: ServerConfig,
    /** Replaces the native Bonds age verifier (tests). */
    ageVerifier: OpenAcAgeVerifier? = null,
) {
    private val http by lazy { HttpClient(Java) }

    val relay: Relay? = if (ServerConfig.FEATURE_RELAY in config.features) Relay(config.relay) else null

    private val native = run {
        val directory = File(config.dataDir, "native").apply { mkdirs() }
        (ZkNative.load(directory) as? ZkResult.Ok)?.value?.backends.orEmpty()
    }

    /** Native proof backends found at start, by name; empty when the library is absent (the relay needs none). */
    val nativeBackends: List<String> = native.map { it.name.lowercase() }.sorted()

    /** The server-side age verifier, set once its two verifying keys are installed. */
    @Volatile private var nativeAgeVerifier: OpenAcAgeVerifier? = ageVerifier

    val bondsAge: BondsAge? = if (ServerConfig.FEATURE_BONDS_AGE !in config.features) null else
        BondsAge(config.publicUrl, config.bondsAge, DidResolver(http), DidTrust(listOf(TwdiwTrustList(http)))) { nativeAgeVerifier }

    /**
     * With bonds_age_verify, installs the OpenAC verifying keys under the data directory (pinned hashes, fetched once)
     * and then verifies here; until then, or without the native library, the age verifier only relays.
     */
    fun prepare(scope: CoroutineScope) {
        if (bondsAge == null || !config.bondsAgeVerify || nativeAgeVerifier != null) return
        if (ZkBackend.OPENAC_SPARTAN2 !in native) {
            log.warn("bonds_age_verify is set but the native library has no OpenAC backend; relaying only")
            return
        }
        scope.launch(Dispatchers.IO) {
            val store = FileZkAssetStore.bundled(File(config.dataDir, "zk-assets"), http)
            for (id in OpenAcAgeNativeVerifier.KEYS) {
                if (store.path(id) != null) continue
                log.info("Installing {}", id)
                if (store.download(id) { _, _ -> } !is ZkResult.Ok) {
                    log.warn("Could not install {}; relaying only", id)
                    return@launch
                }
            }
            nativeAgeVerifier = OpenAcAgeNativeVerifier(store, File(config.dataDir, "zk-verify"))
            log.info("Bonds age proofs are verified here")
        }
    }
}

fun Application.module(server: Server) {
    if (server.config.trustForwarded) install(XForwardedHeaders)
    install(WebSockets) {
        maxFrameSize = server.config.relay.maxFrame.toLong() + 1024
        pingPeriod = 20.seconds
        timeout = 60.seconds
    }
    routing {
        get("/healthz") { call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.OK) }
        get("/info") {
            val body = buildJsonObject {
                put("name", "diw-wallet-server")
                put("version", serverVersion)
                putJsonArray("features") { server.config.features.sorted().forEach { add(JsonPrimitive(it)) } }
                putJsonArray("native") { server.nativeBackends.forEach { add(JsonPrimitive(it)) } }
            }
            call.respondText(body.toString(), ContentType.Application.Json)
        }
        server.relay?.let { relayRoutes(it) }
        server.bondsAge?.let { bondsAgeRoutes(it, server.config.bondsAge) }
    }
}

fun main() {
    val config = try {
        ServerConfig.load()
    } catch (e: IllegalArgumentException) {
        System.err.println("diw-server: ${e.message}")
        exitProcess(2)
    }
    if (!config.dataDir.isDirectory && !config.dataDir.mkdirs()) {
        System.err.println("diw-server: cannot create data directory ${config.dataDir}")
        exitProcess(2)
    }
    val server = Server(config)
    server.prepare(CoroutineScope(SupervisorJob()))
    log.info("diw-wallet-server {} on {}:{} for {}, features {}, native {}", serverVersion, config.listenHost,
        config.listenPort, config.publicUrl, config.features.sorted(), server.nativeBackends)
    embeddedServer(Netty, port = config.listenPort, host = config.listenHost) { module(server) }.start(wait = true)
}
