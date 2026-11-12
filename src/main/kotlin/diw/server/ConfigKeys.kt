package diw.server

/**
 * Every setting the server reads, in one list: the parser, `diw-server config-template` (which writes
 * deploy/server.conf.example) and the deployment files are all checked against it, so a manual install and every
 * image accept exactly the same settings.
 */
data class ConfigKey(
    val name: String,
    /** The default as written in the template; null when there is none. */
    val default: String?,
    val help: String,
    /** Never printed, never part of the settings digest. */
    val secret: Boolean = false,
    /** Where and how the process listens; differs between platforms by design and is left out of the digest. */
    val deployment: Boolean = false,
    /** Written uncommented in the template. */
    val required: Boolean = false,
    /** The template's example value when it differs from the default. */
    val example: String? = null,
) {
    val env: String get() = "DIW_" + name.uppercase()
}

object ConfigKeys {
    val groups: List<Pair<String, List<ConfigKey>>> = listOf(
        "General" to listOf(
            ConfigKey("public_url", null, "Address wallets use, e.g. https://diw.example.org; session links, relay and web verifier URLs are built from it.", required = true, example = "https://diw.example.org"),
            ConfigKey("features", "relay", "Enabled features, comma separated: relay, zktls, bonds-age, verifier, twfido, federation, oprf, zkpassport."),
            ConfigKey("acme_email", null, "Contact address for the certificate authority; read by the bundled Caddy configuration, not by the server.", example = "admin@example.org"),
        ),
        "Deployment (platform defaults apply when unset)" to listOf(
            ConfigKey("listen_host", "127.0.0.1", "Address to listen on; containers default to 0.0.0.0.", deployment = true),
            ConfigKey("listen_port", "8080", "Port to listen on; PORT is used when this is unset (Cloud Run).", deployment = true),
            ConfigKey("data_dir", "/var/lib/diw-server", "Data directory: native library, keys, template cache.", deployment = true),
            ConfigKey("trust_forwarded", null, "Take the client address from X-Forwarded-For; defaults to true on loopback and behind the platform's own proxy.", deployment = true),
        ),
        "Relay" to listOf(
            ConfigKey("relay_max_rooms", "64", "Rooms at once."),
            ConfigKey("relay_lifetime_minutes", "15", "Lifetime of one room."),
            ConfigKey("relay_max_mb", "256", "Traffic of one room, both ways."),
            ConfigKey("relay_max_frame_kb", "128", "Largest frame."),
            ConfigKey("relay_per_address", "8", "Connections from one client address."),
        ),
        "zkTLS verifier host (features=zktls)" to listOf(
            ConfigKey("zktls_token_sha256", null, "SHA-256 of the access tokens, comma separated (diw-server hash-token prints one); required with zktls.", example = "<64 hex>"),
            ConfigKey("zktls_max_sessions", "16", "Sessions at once."),
            ConfigKey("zktls_per_address", "4", "Sessions from one client address."),
            ConfigKey("zktls_concurrent", "1", "Verifications running at once."),
            ConfigKey("zktls_templates_file", null, "Local signed template pack instead of the project's release (still checked against the pinned key)."),
            ConfigKey("zktls_template_keys_file", null, "PEM public keys also accepted for template packs (a private pack); wallets still take pages only from their own pack outside the test environment."),
            ConfigKey("zktls_extra_roots_file", null, "PEM file of extra server roots accepted for proved pages (an intranet or test host)."),
        ),
        "Keys (zktls signing key; signing_generate also covers the federation key)" to listOf(
            ConfigKey("signing_key_file", null, "PEM file with the signing key and its chain; defaults to data_dir/signing.pem."),
            ConfigKey("signing_key_pem", null, "The same PEM inline, \\n for line breaks (a platform secret); wins over the file.", secret = true),
            ConfigKey("signing_generate", "true", "Make missing keys on the first start; set false where the disk is not kept."),
        ),
        "Web verifier (features=verifier)" to listOf(
            ConfigKey("verifier_name", "DIW 網頁查驗", "Name on the holder's consent screen."),
            ConfigKey("verifier_access_token", null, "Access code the page asks for (at least 8 characters); unset lets anyone use it.", secret = true),
            ConfigKey("verifier_key_dir", null, "Directory of the verifier key; defaults to data_dir/verifier."),
            ConfigKey("verifier_key_pem", null, "The verifier key PEM inline (a platform secret); wins over the directory.", secret = true),
            ConfigKey("verifier_max_sessions", "64", "Verifications at once."),
            ConfigKey("verifier_per_address", "8", "Verifications from one client address."),
        ),
        "Bonds age host (features=bonds-age)" to listOf(
            ConfigKey("bonds_age_max_sessions", "64", "Requests at once."),
            ConfigKey("bonds_age_per_address", "8", "Requests from one client address."),
            ConfigKey("bonds_age_verify", "false", "Verify proofs here (needs OpenAC and about 2 GB of memory); otherwise relay only."),
        ),
        "Mobile citizen certificate signing (features=twfido)" to listOf(
            ConfigKey("twfido_environment", "production", "production or uat; both need an SP account from the Ministry of the Interior."),
            ConfigKey("twfido_service_id", null, "SP service ID; required with twfido."),
            ConfigKey("twfido_aes_key", null, "SP AES key, base64 of 32 bytes; required with twfido.", secret = true),
            ConfigKey("twfido_token_sha256", null, "SHA-256 of the signing access tokens; defaults to zktls_token_sha256."),
            ConfigKey("twfido_max_sessions", "16", "Requests at once."),
            ConfigKey("twfido_per_address", "4", "Requests from one client address."),
            ConfigKey("twfido_per_id", "2", "Requests for one ID number (counted by hash)."),
        ),
        "OpenID Federation (features=federation)" to listOf(
            ConfigKey("federation_entity_id", null, "Entity Identifier; defaults to public_url."),
            ConfigKey("federation_authority_hints", null, "Superior entities (intermediates or Trust Anchors), comma separated."),
            ConfigKey("federation_trust_marks", null, "Trust Mark JWTs issued to this server, comma separated."),
            ConfigKey("federation_organization_name", null, "organization_name in the federation_entity metadata; defaults to verifier_name."),
            ConfigKey("federation_key_file", null, "PEM file of the federation entity key; defaults to data_dir/federation.pem."),
            ConfigKey("federation_key_pem", null, "The federation key PEM inline (a platform secret); wins over the file.", secret = true),
            ConfigKey("federation_subordinates_file", null, "JSON file of subordinate entities; makes this server an intermediate or Trust Anchor (fetch and list endpoints)."),
            ConfigKey("federation_lifetime_hours", "24", "Validity of the statements this server signs."),
        ),
        "Project OPRF for zkPassport salted identifiers (features=oprf)" to listOf(
            ConfigKey("oprf_key", null, "OPRF key, a BabyJubJub scalar (diw-server oprf-keygen prints one); required with oprf.", secret = true),
            ConfigKey("oprf_key_id", "1", "Id published with the public key; change it when the key is rotated."),
            ConfigKey("oprf_token_sha256", null, "SHA-256 of the evaluation access tokens; defaults to zktls_token_sha256."),
            ConfigKey("oprf_per_address", "20", "Evaluations from one client address per hour."),
            ConfigKey("oprf_total", "2000", "Evaluations in all per hour."),
        ),
        "Own zkPassport prover (features=zkpassport)" to listOf(
            ConfigKey("zkpassport_token_sha256", null, "SHA-256 of the owner's tokens that may send passport data for proving; required with zkpassport and never one of zktls_token_sha256."),
            ConfigKey("zkpassport_queue", "2", "Jobs that may wait behind the one being proved."),
            ConfigKey("zkpassport_rpc", null, "Ethereum mainnet node for zkPassport's registries; defaults to the wallet's public node."),
            ConfigKey("zkpassport_sepolia_rpc", null, "Ethereum Sepolia node for test requests; defaults to the wallet's public node."),
        ),
    )

    val all: List<ConfigKey> = groups.flatMap { it.second }
    val names: Set<String> = all.map { it.name }.toSet()
    fun get(name: String): ConfigKey? = all.firstOrNull { it.name == name }

    /**
     * The settings file every deployment uses. Lines are DIW_<KEY>=value, so the same file is a systemd
     * EnvironmentFile, a compose env_file, a mounted /etc/diw-server/server.conf and a Cloudflare secret.
     */
    fun template(): String = buildString {
        appendLine("# DIW wallet server settings: one file for every deployment (generated by diw-server config-template).")
        appendLine("# - Without containers: /etc/diw-server/server.conf, or the systemd unit's EnvironmentFile.")
        appendLine("# - compose.yaml: ./server.conf, read as env_file by the server and Caddy.")
        appendLine("# - Cloud Run: the Secret Manager secret diw-server-conf, mounted at /etc/diw-server/server.conf.")
        appendLine("# - Cloudflare Containers: the Worker secret SERVER_CONF (wrangler secret put SERVER_CONF < server.conf).")
        appendLine("# Each line is DIW_<KEY>=value without quotes (lower-case key=value is read too). An environment variable of")
        appendLine("# the same name overrides the file. Unknown keys stop the start. Keep this file mode 0600 when it holds secrets.")
        for ((title, keys) in groups) {
            appendLine()
            appendLine("# --- $title ---")
            for (k in keys) {
                val default = k.default?.let { " Default: $it." } ?: ""
                val secret = if (k.secret) " Secret." else ""
                appendLine("# ${k.help}$default$secret")
                val value = k.example ?: k.default ?: ""
                appendLine((if (k.required) "" else "# ") + "${k.env}=$value")
            }
        }
    }
}
