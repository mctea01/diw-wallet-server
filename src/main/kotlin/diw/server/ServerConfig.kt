package diw.server

import java.io.File
import java.net.URI
import java.util.Properties
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Limits of the relay: rooms at once, a room's lifetime and traffic, one frame, and connections from one address. */
data class RelayLimits(
    val maxRooms: Int = 64,
    val lifetime: Duration = 15.minutes,
    val maxBytes: Long = 256L shl 20,
    val maxFrame: Int = 128 shl 10,
    val perAddress: Int = 8,
)

/**
 * Server settings. Read from a `key=value` file (default `/etc/diw-server/server.conf`, or `DIW_CONFIG`), then
 * overridden by environment variables named `DIW_` plus the upper-case key ([KEYS]), so a container needs only
 * environment. Unknown keys in the file are an error.
 */
data class ServerConfig(
    val publicUrl: String,
    val listenHost: String = "127.0.0.1",
    val listenPort: Int = 8080,
    val features: Set<String> = setOf(FEATURE_RELAY),
    val dataDir: File = File("/var/lib/diw-server"),
    val relay: RelayLimits = RelayLimits(),
    /**
     * Take the client address from the reverse proxy's forwarded headers. Defaults to on only when listening on the
     * loopback interface; a container sets it explicitly because its port is reachable only from the proxy.
     */
    val trustForwarded: Boolean = listenHost == "127.0.0.1" || listenHost == "::1" || listenHost == "localhost",
) {
    companion object {
        const val FEATURE_RELAY = "relay"
        val FEATURES = setOf(FEATURE_RELAY)
        const val DEFAULT_FILE = "/etc/diw-server/server.conf"
        val KEYS = setOf(
            "public_url", "listen_host", "listen_port", "features", "data_dir", "trust_forwarded",
            "relay_max_rooms", "relay_lifetime_minutes", "relay_max_mb", "relay_max_frame_kb", "relay_per_address",
        )

        fun load(env: Map<String, String> = System.getenv()): ServerConfig {
            val named = env["DIW_CONFIG"]?.takeIf { it.isNotBlank() }
            val file = File(named ?: DEFAULT_FILE)
            val values = HashMap<String, String>()
            if (file.isFile) {
                val properties = Properties()
                file.reader(Charsets.UTF_8).use(properties::load)
                properties.stringPropertyNames().forEach { values[it.trim().lowercase()] = properties.getProperty(it).trim() }
            } else if (named != null) {
                throw IllegalArgumentException("Config file not found: ${file.path}")
            }
            // Only known keys: other DIW_ variables (the wallet build's, the start script's DIW_SERVER_OPTS) are not settings.
            KEYS.forEach { key -> env["DIW_" + key.uppercase()]?.let { values[key] = it.trim() } }
            // Cloud Run and similar platforms name the port to listen on in PORT.
            if ("listen_port" !in values) env["PORT"]?.takeIf { it.isNotBlank() }?.let { values["listen_port"] = it.trim() }
            return from(values)
        }

        fun from(values: Map<String, String>): ServerConfig {
            values.keys.firstOrNull { it !in KEYS }?.let { throw IllegalArgumentException("Unknown setting: $it") }
            fun int(key: String, default: Int, range: IntRange): Int {
                val v = values[key] ?: return default
                val n = v.toIntOrNull() ?: throw IllegalArgumentException("$key must be a number")
                require(n in range) { "$key must be between ${range.first} and ${range.last}" }
                return n
            }
            val publicUrl = values["public_url"]?.trimEnd('/')
                ?: throw IllegalArgumentException("public_url is required (for example https://diw.example.org)")
            val uri = runCatching { URI(publicUrl) }.getOrNull()
            require(uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrEmpty() && uri.query == null) {
                "public_url must be an http(s) URL without a query"
            }
            val features = values["features"]?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet()
                ?: setOf(FEATURE_RELAY)
            require(features.isNotEmpty()) { "features must name at least one feature" }
            (features - FEATURES).firstOrNull()?.let { throw IllegalArgumentException("Unknown feature: $it") }
            val listenHost = values["listen_host"] ?: "127.0.0.1"
            val trustForwarded = when (values["trust_forwarded"]?.lowercase()) {
                null -> listenHost == "127.0.0.1" || listenHost == "::1" || listenHost == "localhost"
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("trust_forwarded must be true or false")
            }
            return ServerConfig(
                publicUrl = publicUrl,
                listenHost = listenHost,
                trustForwarded = trustForwarded,
                listenPort = int("listen_port", 8080, 1..65535),
                features = features,
                dataDir = File(values["data_dir"] ?: "/var/lib/diw-server"),
                relay = RelayLimits(
                    maxRooms = int("relay_max_rooms", 64, 1..10_000),
                    lifetime = int("relay_lifetime_minutes", 15, 1..120).minutes,
                    maxBytes = int("relay_max_mb", 256, 1..4096).toLong() shl 20,
                    maxFrame = int("relay_max_frame_kb", 128, 1..1024) shl 10,
                    perAddress = int("relay_per_address", 8, 1..1000),
                ),
            )
        }
    }
}
