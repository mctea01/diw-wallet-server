package diw.server

import diw.wallet.core.document.sign.MobileMoicaEnvironment
import java.io.File
import java.net.URI
import java.security.MessageDigest
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
 * Where the server runs, named by the image or unit in DIW_PLATFORM. A platform only changes defaults of the
 * deployment keys (how and where it listens and keeps data); the service settings come from the same file on every
 * platform, so a manual install and every image behave alike.
 */
enum class Platform(val id: String, val defaults: Map<String, String>) {
    HOST("host", emptyMap()),
    CONTAINER("container", mapOf("listen_host" to "0.0.0.0")),
    /** Behind Caddy on the compose network: only Caddy reaches the port. */
    COMPOSE("compose", mapOf("listen_host" to "0.0.0.0", "trust_forwarded" to "true")),
    /** Google's front end terminates TLS and sets X-Forwarded-For; the disk does not survive a restart. */
    CLOUD_RUN("cloudrun", mapOf("listen_host" to "0.0.0.0", "trust_forwarded" to "true", "data_dir" to "/tmp/diw-server", "signing_generate" to "false")),
    /** The Worker in front replaces X-Forwarded-For with CF-Connecting-IP; the disk does not survive a restart. */
    CLOUDFLARE("cloudflare", mapOf("listen_host" to "0.0.0.0", "trust_forwarded" to "true", "data_dir" to "/tmp/diw-server", "signing_generate" to "false"));

    companion object {
        fun of(id: String?): Platform = if (id.isNullOrBlank()) HOST else
            entries.firstOrNull { it.id == id.trim().lowercase() } ?: throw IllegalArgumentException("Unknown DIW_PLATFORM: $id (${entries.joinToString { it.id }})")
    }
}

/**
 * Server settings ([ConfigKeys] lists them all). Sources, lowest first: the platform's defaults, the settings file
 * (`DIW_CONFIG`, default `/etc/diw-server/server.conf`, or its text in `DIW_CONFIG_TEXT`), then environment variables
 * `DIW_<KEY>`. The file holds `DIW_<KEY>=value` lines (or `key=value`), so it is also a valid env file.
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
    val verifierName: String = DEFAULT_VERIFIER_NAME,
    /** When set, creating a verification needs this code (the page asks for it); otherwise anyone may use the page. */
    val verifierAccessToken: String? = null,
    /** Where the verifier's signing key is kept; its DID changes when the key does. */
    val verifierKeyDir: File? = null,
    val zkTls: ZkTlsLimits = ZkTlsLimits(),
    /** SHA-256 of the tokens that may create zkTLS sessions; required with the zktls feature. */
    val zkTlsTokenHashes: List<ByteArray> = emptyList(),
    /** A local template pack to offer instead of the project's release (for an offline host). */
    val zkTlsTemplatesFile: File? = null,
    /** Public keys (PEM) accepted for template packs besides the project's release key. */
    val zkTlsTemplateKeysFile: File? = null,
    /** Extra server roots (PEM) accepted for proved pages, for an intranet or test host. */
    val zkTlsExtraRootsFile: File? = null,
    /** The PEM file holding the key that signs zkTLS requests and receipts, and its chain; null keeps it in the data directory. */
    val signingKeyFile: File? = null,
    /** Make keys that are missing; off when keys come from a mounted secret. */
    val signingGenerate: Boolean = true,
    /** The signing file's contents, from a platform secret in DIW_SIGNING_KEY_PEM; wins over [signingKeyFile]. */
    val signingKeyPem: String? = null,
    /** The web verifier's key (private then public PEM), from a platform secret; wins over [verifierKeyDir]. */
    val verifierKeyPem: String? = null,
    /** The SP account for mobile citizen certificate signing (twfido): its service, its AES key and which service hosts. */
    val twFidoEnvironment: MobileMoicaEnvironment = MobileMoicaEnvironment.PRODUCTION,
    val twFidoServiceId: String? = null,
    val twFidoAesKey: String? = null,
    /** SHA-256 of the tokens that may ask for a signature; required with the twfido feature. */
    val twFidoTokenHashes: List<ByteArray> = emptyList(),
    val twFido: TwFidoLimits = TwFidoLimits(),
    /** Read by the bundled Caddy configuration only; kept so the one settings file serves both. */
    val acmeEmail: String? = null,
    val federation: FederationSettings = FederationSettings(),
    /**
     * Take the client address from the reverse proxy's forwarded headers. Defaults to on only when listening on the
     * loopback interface; a container platform sets it because its port is reachable only from the proxy.
     */
    val trustForwarded: Boolean = listenHost == "127.0.0.1" || listenHost == "::1" || listenHost == "localhost",
    /** Where the effective value of each key came from (default, platform, file, environment), for `diw-server config`. */
    val sources: Map<String, String> = emptyMap(),
    val platform: Platform = Platform.HOST,
) {
    /** This server's Entity Identifier in an OpenID Federation. */
    val federationEntityId: String get() = federation.entityId ?: publicUrl

    /**
     * The effective value of every key in canonical form, secrets shown only as set. Two deployments with the same
     * service settings print the same values whatever file layout, order or platform they used.
     */
    fun effective(): Map<String, String?> {
        fun hex(list: List<ByteArray>) = list.map { b -> b.joinToString("") { "%02x".format(it) } }.sorted().joinToString(",").ifEmpty { null }
        fun secret(v: String?) = if (v.isNullOrEmpty()) null else SECRET_SET
        val m = linkedMapOf<String, String?>(
            "public_url" to publicUrl,
            "features" to features.sorted().joinToString(","),
            "acme_email" to acmeEmail,
            "listen_host" to listenHost,
            "listen_port" to listenPort.toString(),
            "data_dir" to dataDir.path,
            "trust_forwarded" to trustForwarded.toString(),
            "relay_max_rooms" to relay.maxRooms.toString(),
            "relay_lifetime_minutes" to relay.lifetime.inWholeMinutes.toString(),
            "relay_max_mb" to (relay.maxBytes shr 20).toString(),
            "relay_max_frame_kb" to (relay.maxFrame shr 10).toString(),
            "relay_per_address" to relay.perAddress.toString(),
            "zktls_token_sha256" to hex(zkTlsTokenHashes),
            "zktls_max_sessions" to zkTls.maxSessions.toString(),
            "zktls_per_address" to zkTls.perAddress.toString(),
            "zktls_concurrent" to zkTls.concurrent.toString(),
            "zktls_templates_file" to zkTlsTemplatesFile?.path,
            "zktls_template_keys_file" to zkTlsTemplateKeysFile?.path,
            "zktls_extra_roots_file" to zkTlsExtraRootsFile?.path,
            "signing_key_file" to signingKeyFile?.path,
            "signing_key_pem" to secret(signingKeyPem),
            "signing_generate" to signingGenerate.toString(),
            "verifier_name" to verifierName,
            "verifier_access_token" to secret(verifierAccessToken),
            "verifier_key_dir" to verifierKeyDir?.path,
            "verifier_key_pem" to secret(verifierKeyPem),
            "verifier_max_sessions" to verifier.maxSessions.toString(),
            "verifier_per_address" to verifier.perAddress.toString(),
            "bonds_age_max_sessions" to bondsAge.maxSessions.toString(),
            "bonds_age_per_address" to bondsAge.perAddress.toString(),
            "bonds_age_verify" to bondsAgeVerify.toString(),
            "twfido_environment" to twFidoEnvironment.name.lowercase(),
            "twfido_service_id" to twFidoServiceId,
            "twfido_aes_key" to secret(twFidoAesKey),
            "twfido_token_sha256" to hex(twFidoTokenHashes),
            "twfido_max_sessions" to twFido.maxSessions.toString(),
            "twfido_per_address" to twFido.perAddress.toString(),
            "twfido_per_id" to twFido.perId.toString(),
            "federation_entity_id" to federationEntityId,
            "federation_authority_hints" to federation.authorityHints.joinToString(",").ifEmpty { null },
            "federation_trust_marks" to federation.trustMarks.joinToString(",").ifEmpty { null },
            "federation_organization_name" to (federation.organizationName ?: verifierName),
            "federation_key_file" to federation.keyFile?.path,
            "federation_key_pem" to secret(federation.keyPem),
            "federation_subordinates_file" to federation.subordinatesFile?.path,
            "federation_lifetime_hours" to federation.lifetimeHours.toString(),
        )
        check(m.keys == ConfigKeys.names) { "effective() and ConfigKeys disagree: ${(m.keys - ConfigKeys.names) + (ConfigKeys.names - m.keys)}" }
        return m
    }

    /**
     * SHA-256 over the service settings (deployment keys and secret values left out): equal on every deployment that
     * runs the same settings, so `/info` and `diw-server config` show at a glance whether two installs agree.
     */
    fun settingsDigest(): String {
        val canonical = effective().filterKeys { ConfigKeys.get(it)?.deployment != true }.entries
            .sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value ?: ""}" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val FEATURE_RELAY = "relay"
        const val FEATURE_BONDS_AGE = "bonds-age"
        const val FEATURE_VERIFIER = "verifier"
        const val FEATURE_ZKTLS = "zktls"
        const val FEATURE_TWFIDO = "twfido"
        const val FEATURE_FEDERATION = "federation"
        val FEATURES = setOf(FEATURE_RELAY, FEATURE_BONDS_AGE, FEATURE_VERIFIER, FEATURE_ZKTLS, FEATURE_TWFIDO, FEATURE_FEDERATION)
        const val DEFAULT_FILE = "/etc/diw-server/server.conf"
        const val DEFAULT_VERIFIER_NAME = "DIW 網頁查驗"
        const val SECRET_SET = "<set>"
        val KEYS: Set<String> get() = ConfigKeys.names
        private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

        /**
         * Reads one settings file: `DIW_<KEY>=value` or `key=value` lines, `#` comments, an optional `export ` and
         * optional surrounding quotes, as docker compose and systemd read env files. Unknown keys are an error.
         */
        fun parseFile(text: String, origin: String): Map<String, String> {
            val values = LinkedHashMap<String, String>()
            text.removePrefix("﻿").lineSequence().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val body = line.removePrefix("export ").trimStart()
                val eq = body.indexOf('=')
                require(eq > 0) { "$origin line ${index + 1}: expected KEY=value" }
                val name = body.substring(0, eq).trim().let { if (it.startsWith("DIW_", ignoreCase = true)) it.substring(4) else it }.lowercase()
                require(name in ConfigKeys.names) { "Unknown setting: $name ($origin line ${index + 1})" }
                var value = body.substring(eq + 1).trim()
                if (value.length >= 2 && (value.first() == '"' && value.last() == '"' || value.first() == '\'' && value.last() == '\'')) {
                    value = value.substring(1, value.length - 1)
                }
                values[name] = value
            }
            return values
        }

        fun load(env: Map<String, String> = System.getenv()): ServerConfig {
            val platform = Platform.of(env["DIW_PLATFORM"])
            val values = LinkedHashMap<String, String>()
            val sources = HashMap<String, String>()
            platform.defaults.forEach { (k, v) -> values[k] = v; sources[k] = "platform ${platform.id}" }
            val text = env["DIW_CONFIG_TEXT"]?.takeIf { it.isNotBlank() }
            val named = env["DIW_CONFIG"]?.takeIf { it.isNotBlank() }
            if (text != null) {
                parseFile(text, "DIW_CONFIG_TEXT").forEach { (k, v) -> values[k] = v; sources[k] = "DIW_CONFIG_TEXT" }
            } else {
                val file = File(named ?: DEFAULT_FILE)
                if (file.isFile) {
                    parseFile(file.readText(Charsets.UTF_8), file.path).forEach { (k, v) -> values[k] = v; sources[k] = file.path }
                } else if (named != null) {
                    throw IllegalArgumentException("Config file not found: ${file.path}")
                }
            }
            // Only known keys: other DIW_ variables (the wallet build's, the start script's DIW_SERVER_OPTS) are not settings.
            ConfigKeys.all.forEach { key -> env[key.env]?.let { values[key.name] = it.trim(); sources[key.name] = "environment" } }
            // Cloud Run and similar platforms name the port to listen on in PORT.
            if ("listen_port" !in values) env["PORT"]?.takeIf { it.isNotBlank() }?.let { values["listen_port"] = it.trim(); sources["listen_port"] = "PORT" }
            return from(values).copy(sources = sources, platform = platform)
        }

        fun from(values: Map<String, String>): ServerConfig {
            values.keys.firstOrNull { it !in ConfigKeys.names }?.let { throw IllegalArgumentException("Unknown setting: $it") }
            fun str(key: String): String? = values[key]?.trim()?.takeIf { it.isNotEmpty() }
            fun int(key: String, default: Int, range: IntRange): Int {
                val v = str(key) ?: return default
                val n = v.toIntOrNull() ?: throw IllegalArgumentException("$key must be a number")
                require(n in range) { "$key must be between ${range.first} and ${range.last}" }
                return n
            }
            fun bool(key: String, default: Boolean): Boolean = when (str(key)?.lowercase()) {
                null -> default
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("$key must be true or false")
            }
            fun list(key: String) = str(key)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            fun hashes(key: String) = list(key).map { h ->
                require(HEX64.matches(h)) { "$key must list SHA-256 hashes in hex (64 characters each)" }
                h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }
            fun httpsUrl(key: String, v: String): String {
                val u = runCatching { URI(v) }.getOrNull()
                require(u != null && u.scheme in setOf("https", "http") && !u.host.isNullOrEmpty() && u.query == null && u.fragment == null) {
                    "$key must be an http(s) URL without a query"
                }
                return v.trimEnd('/')
            }
            val publicUrl = httpsUrl("public_url", str("public_url")
                ?: throw IllegalArgumentException("public_url is required (for example https://diw.example.org)"))
            val features = str("features")?.let { list("features").map { it.lowercase() }.toSet() } ?: setOf(FEATURE_RELAY)
            require(features.isNotEmpty()) { "features must name at least one feature" }
            (features - FEATURES).firstOrNull()?.let { throw IllegalArgumentException("Unknown feature: $it") }
            val listenHost = str("listen_host") ?: "127.0.0.1"
            val tokenHashes = hashes("zktls_token_sha256")
            require(FEATURE_ZKTLS !in features || tokenHashes.isNotEmpty()) {
                "The zktls feature needs zktls_token_sha256 (diw-server hash-token prints it)"
            }
            // The wallet sends one token to its server, so the zkTLS tokens are the default.
            val twFidoTokens = hashes("twfido_token_sha256").ifEmpty { tokenHashes }
            val twFidoServiceId = str("twfido_service_id")
            val twFidoAesKey = str("twfido_aes_key")
            val twFidoEnvironment = when (str("twfido_environment")?.lowercase()) {
                null, "production" -> MobileMoicaEnvironment.PRODUCTION
                "uat" -> MobileMoicaEnvironment.UAT
                else -> throw IllegalArgumentException("twfido_environment must be production or uat")
            }
            if (FEATURE_TWFIDO in features) {
                require(twFidoServiceId != null && twFidoAesKey != null) { "The twfido feature needs twfido_service_id and twfido_aes_key" }
                // Checked here so a wrong key stops the start instead of failing every request; the key itself is never printed.
                val key = runCatching { java.util.Base64.getDecoder().decode(twFidoAesKey) }.getOrNull()
                require(key != null && key.size == 32) { "twfido_aes_key must be the SP's 32-byte AES key in base64" }
                require(twFidoTokens.isNotEmpty()) { "The twfido feature needs twfido_token_sha256 or zktls_token_sha256 (diw-server hash-token prints it)" }
            }
            val federation = FederationSettings(
                entityId = str("federation_entity_id")?.let { httpsUrl("federation_entity_id", it) },
                authorityHints = list("federation_authority_hints").map { httpsUrl("federation_authority_hints", it) },
                trustMarks = list("federation_trust_marks").onEach {
                    require(it.count { c -> c == '.' } == 2) { "federation_trust_marks must list compact JWTs" }
                },
                organizationName = str("federation_organization_name")?.filter { !it.isISOControl() }?.take(100),
                keyFile = str("federation_key_file")?.let { File(it) },
                keyPem = str("federation_key_pem")?.replace("\\n", "\n"),
                subordinatesFile = str("federation_subordinates_file")?.let { File(it) },
                lifetimeHours = int("federation_lifetime_hours", 24, 1..8760),
            )
            if (FEATURE_FEDERATION in features) {
                federation.subordinatesFile?.let { require(it.isFile) { "federation_subordinates_file not found: ${it.path}" } }
            }
            return ServerConfig(
                publicUrl = publicUrl,
                listenHost = listenHost,
                trustForwarded = bool("trust_forwarded", listenHost == "127.0.0.1" || listenHost == "::1" || listenHost == "localhost"),
                listenPort = int("listen_port", 8080, 1..65535),
                features = features,
                dataDir = File(str("data_dir") ?: "/var/lib/diw-server"),
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
                verifierName = str("verifier_name")?.filter { !it.isISOControl() }?.take(60)?.takeIf { it.isNotBlank() } ?: DEFAULT_VERIFIER_NAME,
                verifierAccessToken = str("verifier_access_token")?.also {
                    require(it.length >= 8) { "verifier_access_token must be at least 8 characters" }
                },
                verifierKeyDir = str("verifier_key_dir")?.let { File(it) },
                zkTls = ZkTlsLimits(
                    maxSessions = int("zktls_max_sessions", 16, 1..1000),
                    perAddress = int("zktls_per_address", 4, 1..1000),
                    concurrent = int("zktls_concurrent", 1, 1..16),
                ),
                zkTlsTokenHashes = tokenHashes,
                zkTlsTemplatesFile = str("zktls_templates_file")?.let { File(it) },
                zkTlsTemplateKeysFile = str("zktls_template_keys_file")?.let { File(it) },
                zkTlsExtraRootsFile = str("zktls_extra_roots_file")?.let { File(it) },
                signingKeyFile = str("signing_key_file")?.let { File(it) },
                signingGenerate = bool("signing_generate", true),
                // A PEM pasted on one line keeps its line breaks as \n.
                signingKeyPem = str("signing_key_pem")?.replace("\\n", "\n"),
                verifierKeyPem = str("verifier_key_pem")?.replace("\\n", "\n"),
                twFidoEnvironment = twFidoEnvironment,
                twFidoServiceId = twFidoServiceId,
                twFidoAesKey = twFidoAesKey,
                twFidoTokenHashes = twFidoTokens,
                twFido = TwFidoLimits(
                    maxSessions = int("twfido_max_sessions", 16, 1..1000),
                    perAddress = int("twfido_per_address", 4, 1..1000),
                    perId = int("twfido_per_id", 2, 1..100),
                ),
                acmeEmail = str("acme_email"),
                federation = federation,
            )
        }
    }
}

/** OpenID Federation 1.0 settings: this server's place in a federation, and its subordinates when it is a superior. */
data class FederationSettings(
    val entityId: String? = null,
    val authorityHints: List<String> = emptyList(),
    val trustMarks: List<String> = emptyList(),
    val organizationName: String? = null,
    val keyFile: File? = null,
    val keyPem: String? = null,
    val subordinatesFile: File? = null,
    val lifetimeHours: Int = 24,
)
