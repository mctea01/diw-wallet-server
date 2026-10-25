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
    val bondsAge: BondsAgeLimits = BondsAgeLimits(),
    /** Verify Bonds age proofs here (needs the native library and about 450 MB of verifying keys); otherwise relay only. */
    val bondsAgeVerify: Boolean = false,
    val verifier: VerifierLimits = VerifierLimits(),
    /** The verifier's name on the holder's consent screen. */
    val verifierName: String = "DIW 網頁查驗",
    /** When set, creating a verification needs this code (the page asks for it); otherwise anyone may use the page. */
    val verifierAccessToken: String? = null,
    /** Where the verifier's signing key is kept; its DID changes when the key does. */
    val verifierKeyDir: File? = null,
    val zkTls: ZkTlsLimits = ZkTlsLimits(),
    /** SHA-256 of the tokens that may create zkTLS sessions; required with the zktls feature. */
    val zkTlsTokenHashes: List<ByteArray> = emptyList(),
    /** A local template pack to offer instead of the project's release (for an offline host). */
    val zkTlsTemplatesFile: File? = null,
    /** The PEM file holding the key that signs zkTLS requests and receipts, and its chain; null keeps it in the data directory. */
    val signingKeyFile: File? = null,
    /** Make a signing key when [signingKeyFile] is missing; off when the key comes from a mounted secret. */
    val signingGenerate: Boolean = true,
    /** The signing file's contents, from a platform secret in DIW_SIGNING_KEY_PEM; wins over [signingKeyFile]. */
    val signingKeyPem: String? = null,
    /** The web verifier's key (private then public PEM), from a platform secret; wins over [verifierKeyDir]. */
    val verifierKeyPem: String? = null,
    /**
     * Take the client address from the reverse proxy's forwarded headers. Defaults to on only when listening on the
     * loopback interface; a container sets it explicitly because its port is reachable only from the proxy.
     */
    val trustForwarded: Boolean = listenHost == "127.0.0.1" || listenHost == "::1" || listenHost == "localhost",
) {
    companion object {
        const val FEATURE_RELAY = "relay"
        const val FEATURE_BONDS_AGE = "bonds-age"
        const val FEATURE_VERIFIER = "verifier"
        const val FEATURE_ZKTLS = "zktls"
        val FEATURES = setOf(FEATURE_RELAY, FEATURE_BONDS_AGE, FEATURE_VERIFIER, FEATURE_ZKTLS)
        const val DEFAULT_FILE = "/etc/diw-server/server.conf"
        val KEYS = setOf(
            "public_url", "listen_host", "listen_port", "features", "data_dir", "trust_forwarded",
            "relay_max_rooms", "relay_lifetime_minutes", "relay_max_mb", "relay_max_frame_kb", "relay_per_address",
            "bonds_age_max_sessions", "bonds_age_per_address", "bonds_age_verify",
            "verifier_name", "verifier_access_token", "verifier_key_dir", "verifier_max_sessions", "verifier_per_address",
            "zktls_token_sha256", "zktls_max_sessions", "zktls_per_address", "zktls_concurrent", "zktls_templates_file",
            "signing_key_file", "signing_generate", "signing_key_pem", "verifier_key_pem",
        )
        private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

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
            fun bool(key: String, default: Boolean): Boolean = when (values[key]?.lowercase()) {
                null -> default
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("$key must be true or false")
            }
            val tokenHashes = values["zktls_token_sha256"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty().map { h ->
                require(HEX64.matches(h)) { "zktls_token_sha256 must list SHA-256 hashes in hex (64 characters each)" }
                h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }
            require(FEATURE_ZKTLS !in features || tokenHashes.isNotEmpty()) {
                "The zktls feature needs zktls_token_sha256 (diw-server hash-token prints it)"
            }
            val trustForwarded = bool("trust_forwarded", listenHost == "127.0.0.1" || listenHost == "::1" || listenHost == "localhost")
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
                bondsAge = BondsAgeLimits(
                    maxSessions = int("bonds_age_max_sessions", 64, 1..10_000),
                    perAddress = int("bonds_age_per_address", 8, 1..1000),
                ),
                bondsAgeVerify = bool("bonds_age_verify", false),
                verifier = VerifierLimits(
                    maxSessions = int("verifier_max_sessions", 64, 1..10_000),
                    perAddress = int("verifier_per_address", 8, 1..1000),
                ),
                verifierName = values["verifier_name"]?.filter { !it.isISOControl() }?.take(60)?.takeIf { it.isNotBlank() } ?: "DIW 網頁查驗",
                verifierAccessToken = values["verifier_access_token"]?.takeIf { it.isNotEmpty() }?.also {
                    require(it.length >= 8) { "verifier_access_token must be at least 8 characters" }
                },
                verifierKeyDir = values["verifier_key_dir"]?.takeIf { it.isNotBlank() }?.let { File(it) },
                zkTls = ZkTlsLimits(
                    maxSessions = int("zktls_max_sessions", 16, 1..1000),
                    perAddress = int("zktls_per_address", 4, 1..1000),
                    concurrent = int("zktls_concurrent", 1, 1..16),
                ),
                zkTlsTokenHashes = tokenHashes,
                zkTlsTemplatesFile = values["zktls_templates_file"]?.takeIf { it.isNotBlank() }?.let { File(it) },
                signingKeyFile = values["signing_key_file"]?.takeIf { it.isNotBlank() }?.let { File(it) },
                signingGenerate = bool("signing_generate", true),
                // A PEM pasted on one line keeps its line breaks as \n.
                signingKeyPem = values["signing_key_pem"]?.takeIf { it.isNotBlank() }?.replace("\\n", "\n"),
                verifierKeyPem = values["verifier_key_pem"]?.takeIf { it.isNotBlank() }?.replace("\\n", "\n"),
            )
        }
    }
}
