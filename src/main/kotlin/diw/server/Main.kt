package diw.server

import diw.wallet.core.zk.ZkResult
import diw.wallet.core.zk.native.ZkNative
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
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("diw.server")

/** Version from the jar manifest; "dev" when run from the build directory. */
val serverVersion: String = ServerConfig::class.java.`package`?.implementationVersion ?: "dev"

/** What the server runs, built once from [config]. */
class Server(val config: ServerConfig) {
    val relay: Relay? = if (ServerConfig.FEATURE_RELAY in config.features) Relay(config.relay) else null

    /** Native proof backends found at start, by name; empty when the library is absent (the relay needs none). */
    val nativeBackends: List<String> = run {
        val directory = File(config.dataDir, "native").apply { mkdirs() }
        when (val status = ZkNative.load(directory)) {
            is ZkResult.Ok -> status.value.backends.map { it.name.lowercase() }.sorted()
            else -> emptyList()
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
    log.info("diw-wallet-server {} on {}:{} for {}, features {}, native {}", serverVersion, config.listenHost,
        config.listenPort, config.publicUrl, config.features.sorted(), server.nativeBackends)
    embeddedServer(Netty, port = config.listenPort, host = config.listenHost) { module(server) }.start(wait = true)
}
