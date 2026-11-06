package diw.server

import diw.wallet.core.did.DidResolver
import diw.wallet.core.document.sign.MobileMoicaCredentials
import diw.wallet.core.env.EnvironmentPolicy
import diw.wallet.core.status.W3cStatusChecker
import diw.wallet.core.trust.TrustRegistry
import diw.wallet.core.profile.twdiw.TwdiwTrustList
import diw.wallet.core.trust.DidTrust
import diw.wallet.core.zk.OpenAcAgeVerifier
import diw.wallet.core.trust.BuiltInAnchors
import diw.wallet.core.zk.ZkBackend
import diw.wallet.core.zk.ZkTemplatePack
import org.multipaz.crypto.EcPublicKey
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
import io.ktor.server.cio.CIO
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.multipaz.storage.ephemeral.EphemeralStorage
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
    /** Replaces the TWDIW trust list and the status list check (tests). */
    didTrust: DidTrust? = null,
    checkStatus: Boolean = true,
    /** Server roots TLSNotary also accepts (tests add their target's CA). */
    private val zkTlsRoots: List<ByteArray> = emptyList(),
    /** Keys a template pack may be signed with; the project's release key unless a test signs its own pack. */
    templateKeys: List<EcPublicKey>? = null,
    /** Pretends the native library has TLSNotary (tests without it). */
    zkTlsAvailable: Boolean? = null,
    /** The TW FidO service address; tests point it at a local stand-in. */
    twFidoBaseUrl: String? = null,
) {
    private val http by lazy { HttpClient(Java) }
    private val didTrust: DidTrust by lazy { didTrust ?: DidTrust(listOf(TwdiwTrustList(http))) }

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
        BondsAge(config.publicUrl, config.bondsAge, DidResolver(http), this.didTrust) { nativeAgeVerifier }

    /** Keys from zktls_template_keys_file, accepted for template packs besides the release key. */
    private val extraTemplateKeys: List<EcPublicKey> = config.zkTlsTemplateKeysFile?.let { file ->
        require(file.isFile) { "zktls_template_keys_file not found: ${file.path}" }
        Regex("-----BEGIN PUBLIC KEY-----[\\s\\S]+?-----END PUBLIC KEY-----").findAll(file.readText())
            .map { EcPublicKey.fromPem(it.value) }.toList()
            .also { require(it.isNotEmpty()) { "zktls_template_keys_file holds no public key" } }
    }.orEmpty()

    /** Roots from zktls_extra_roots_file (an intranet or test page), read once at start. */
    private val extraRoots: List<ByteArray> = config.zkTlsExtraRootsFile?.let { file ->
        require(file.isFile) { "zktls_extra_roots_file not found: ${file.path}" }
        Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----").findAll(file.readText())
            .map { org.multipaz.crypto.X509Cert.fromPem(it.value).encoded.toByteArray() }.toList()
            .also { require(it.isNotEmpty()) { "zktls_extra_roots_file holds no certificate" } }
    }.orEmpty()

    /** The key that signs zkTLS requests and receipts; only the zktls feature needs it. */
    val signing: SigningIdentity? = if (ServerConfig.FEATURE_ZKTLS !in config.features) null else runBlocking {
        SigningIdentity.load(config.signingKeyFile ?: File(config.dataDir, "signing.pem"), config.publicUrl, config.signingGenerate, config.signingKeyPem)
    }

    val templates: ZkTemplates? = if (signing == null) null else
        ZkTemplates(http, config.dataDir, config.zkTlsTemplatesFile, (templateKeys ?: ZkTemplatePack.trustedKeys()) + extraTemplateKeys)

    private val tlsn = zkTlsAvailable ?: (ZkBackend.TLSN in native)

    val zkTls: ZkTlsHost? = signing?.let { id ->
        ZkTlsHost(config.publicUrl, id, config.zkTls, { BuiltInAnchors.all.map { it.certificate.encoded.toByteArray() }.distinct() + zkTlsRoots + extraRoots }) { tlsn }
    }

    /** Mobile citizen certificate signing with this server's SP account (the SP key never leaves the server). */
    val twFido: TwFidoHost? = if (ServerConfig.FEATURE_TWFIDO !in config.features) null else TwFidoHost(
        http, config.twFidoEnvironment, MobileMoicaCredentials(config.twFidoServiceId!!, config.twFidoAesKey!!), config.twFido,
        twFidoBaseUrl ?: config.twFidoEnvironment.baseUrl,
    )

    /** This server as an OpenID Federation entity (and superior, with a subordinates file). */
    val federation: Federation? = if (ServerConfig.FEATURE_FEDERATION !in config.features) null else
        runBlocking { Federation.loadKey(config).let { Federation(config, it, Federation.jwk(it.publicKey), { serverMetadata() }) } }

    /** Whether every enabled feature can run: zktls needs TLSNotary in the native library. */
    val healthy: Boolean get() = zkTls == null || tlsn

    val verifier: TwdiwVerifier? = if (ServerConfig.FEATURE_VERIFIER !in config.features) null else TwdiwVerifier(
        config.publicUrl, config.verifier, config.verifierName,
        runBlocking {
            config.verifierKeyPem?.let { TwdiwVerifier.Identity.parse(it) }
                ?: TwdiwVerifier.Identity.load(config.verifierKeyDir ?: File(config.dataDir, "verifier"))
        },
        DidResolver(http), this.didTrust,
        if (checkStatus) W3cStatusChecker(http, TrustRegistry(EphemeralStorage(), EnvironmentPolicy()), this.didTrust) else null,
    )

    /**
     * The `diw_wallet_server` metadata: what a wallet otherwise reads from /info and confirms by hand, signed into the
     * federation so a trusted Trust Anchor vouches for the signing root and the web verifier DID.
     */
    fun serverMetadata(): JsonObject = buildJsonObject {
        put(Federation.SERVER_TYPE, buildJsonObject {
            put("public_url", config.publicUrl)
            putJsonArray("features") { config.features.sorted().forEach { add(JsonPrimitive(it)) } }
            signing?.let { put("signing_root_sha256", it.rootSha256) }
            verifier?.let { put("verifier_did", it.did) }
            put("settings_sha256", config.settingsDigest())
        })
    }

    /**
     * With bonds_age_verify, installs the OpenAC verifying keys under the data directory (pinned hashes, fetched once)
     * and then verifies here; until then, or without the native library, the age verifier only relays.
     */
    fun prepare(scope: CoroutineScope) {
        templates?.start(scope)
        zkTls?.start(scope)
        twFido?.start(scope)
        if (zkTls != null && !tlsn) log.warn("The zktls feature is set but the native library has no TLSNotary backend")
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
        get("/healthz") {
            if (server.healthy) call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.OK)
            else call.respondText("native library without TLSNotary", ContentType.Text.Plain, HttpStatusCode.ServiceUnavailable)
        }
        get("/info") {
            val body = buildJsonObject {
                put("name", "diw-wallet-server")
                put("version", serverVersion)
                // The address wallets are sent to; a wallet that reached /info elsewhere warns about the mismatch.
                put("publicUrl", server.config.publicUrl)
                putJsonArray("features") { server.config.features.sorted().forEach { add(JsonPrimitive(it)) } }
                putJsonArray("native") { server.nativeBackends.forEach { add(JsonPrimitive(it)) } }
                // The web verifier's DID, for registering it or recognising it on the holder's consent screen.
                server.verifier?.let { put("verifierDid", it.did) }
                // The root the wallet trusts after the user compares its SHA-256 (DESIGN 3.4).
                server.signing?.let { id ->
                    put("signing", buildJsonObject {
                        put("subject", id.root.subject.name)
                        put("rootSha256", id.rootSha256)
                        put("root", id.root.toPem())
                    })
                }
                server.templates?.version?.let { put("templatesVersion", it) }
                // Which TW FidO service this server's SP account signs with; the account itself stays private.
                server.twFido?.let { put("twfido", buildJsonObject { put("environment", it.environment.name) }) }
                // Equal on every deployment with the same service settings (ServerConfig.settingsDigest).
                put("settingsSha256", server.config.settingsDigest())
                put("platform", server.config.platform.id)
                // The federation entity, for adding its Trust Anchor or this server in the wallet.
                server.federation?.let { f ->
                    put("federation", buildJsonObject {
                        put("entityId", f.entityId)
                        putJsonArray("authorityHints") { server.config.federation.authorityHints.forEach { add(JsonPrimitive(it)) } }
                        put("superior", f.isSuperior)
                    })
                }
            }
            call.respondText(body.toString(), ContentType.Application.Json)
        }
        server.relay?.let { relayRoutes(it) }
        server.bondsAge?.let { bondsAgeRoutes(it, server.config.bondsAge) }
        server.verifier?.let { verifierRoutes(it, server.config.verifierAccessToken) }
        server.zkTls?.let { zkTlsRoutes(it, server.config.zkTlsTokenHashes) }
        server.twFido?.let { twFidoRoutes(it, server.config.twFidoTokenHashes) }
        server.federation?.let { federationRoutes(it) }
    }
}

private const val USAGE = "usage: diw-server [keygen <file> <public_url> [verifier|federation] | hash-token | config | " +
    "config-template | federation-jwks | federation-enroll <entity_id>]"

private fun fail(message: String): Nothing {
    System.err.println("diw-server: $message")
    exitProcess(2)
}

private fun loadConfig(): ServerConfig = try {
    ServerConfig.load()
} catch (e: IllegalArgumentException) {
    fail(e.message ?: "invalid settings")
}

/**
 * Commands besides serving:
 * - `keygen <file> <public_url> [verifier|federation]` writes a key file for a platform secret (signing_key_pem,
 *   verifier_key_pem, federation_key_pem);
 * - `hash-token` prints the SHA-256 of stdin's first line;
 * - `config` prints the effective settings, where each came from (secrets hidden) and the settings digest that
 *   /info also reports, to compare deployments;
 * - `config-template` prints the settings file every deployment uses (deploy/server.conf.example);
 * - `federation-jwks` prints this server's federation keys, for the superior that enrols it;
 * - `federation-enroll <entity_id>` fetches and checks a subordinate's Entity Configuration and prints the entry to
 *   add to federation_subordinates_file after comparing the key thumbprints with its operator.
 */
private fun command(args: Array<String>): Boolean {
    when (args.firstOrNull()) {
        null -> return false
        "keygen" -> {
            val file = args.getOrNull(1)?.let { File(it) }
            val url = args.getOrNull(2)
            if (file == null || url == null) fail(USAGE)
            if (file.exists()) fail("${file.path} exists; remove it first")
            when (args.getOrNull(3)) {
                "verifier" -> {
                    val identity = runBlocking { TwdiwVerifier.Identity.create() }
                    writePrivateFile(file, TwdiwVerifier.Identity.pem(identity))
                    println("Wrote ${file.path}; verifier DID ${identity.did}")
                }
                "federation" -> {
                    val key = runBlocking { Federation.createKey() }
                    writePrivateFile(file, Federation.pem(key))
                    println("Wrote ${file.path}; federation key ${runBlocking { Federation.jwk(key.publicKey) }["kid"]}")
                }
                null -> {
                    val identity = runBlocking { SigningIdentity.create(url) }
                    SigningIdentity.write(file, identity)
                    println("Wrote ${file.path}; root SHA-256 ${identity.rootSha256}")
                }
                else -> fail(USAGE)
            }
        }
        "hash-token" -> {
            val token = readlnOrNull()?.trim().orEmpty()
            if (token.length < 16) fail("use a token of at least 16 characters")
            println(SigningIdentity.sha256Hex(token.encodeToByteArray()))
        }
        "config" -> {
            val config = loadConfig()
            println("# platform ${config.platform.id}")
            for ((key, value) in config.effective()) {
                val source = config.sources[key] ?: "default"
                println("${ConfigKeys.get(key)!!.env}=${value ?: ""}    # $source")
            }
            println("# settings digest ${config.settingsDigest()}")
        }
        "config-template" -> print(ConfigKeys.template())
        "federation-jwks" -> {
            val config = loadConfig()
            if (!config.dataDir.isDirectory && !config.dataDir.mkdirs()) fail("cannot create data directory ${config.dataDir}")
            val jwk = runBlocking { Federation.jwk(Federation.loadKey(config).publicKey) }
            println(buildJsonObject { put("keys", kotlinx.serialization.json.JsonArray(listOf(jwk))) })
        }
        "federation-enroll" -> {
            val id = args.getOrNull(1)?.trimEnd('/') ?: fail(USAGE)
            val http = HttpClient(Java)
            try {
                val ec = runBlocking { diw.wallet.core.federation.FederationResolver(http, { emptyList() }).configuration(id) }
                ec.checkTime(kotlin.time.Clock.System.now().epochSeconds, 120)
                System.err.println("Entity Configuration of $id verified with its own keys; compare these thumbprints with its operator:")
                ec.jwks.forEach { System.err.println("  " + diw.wallet.core.jose.jwkThumbprint(it)) }
                val types = ec.metadata.keys.filter { it != "federation_entity" }
                println(Federation.enrolment(id, ec.jwks, types))
            } catch (e: Exception) {
                fail("cannot read the Entity Configuration of $id: ${e.message}")
            } finally {
                http.close()
            }
        }
        else -> fail(USAGE)
    }
    return true
}

fun main(args: Array<String>) {
    // Command output (the settings template, verifier names) is UTF-8 whatever the locale, so Chinese never turns into ?.
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, Charsets.UTF_8))
    System.setErr(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, Charsets.UTF_8))
    if (command(args)) return
    val config = loadConfig()
    if (!config.dataDir.isDirectory && !config.dataDir.mkdirs()) {
        System.err.println("diw-server: cannot create data directory ${config.dataDir}")
        exitProcess(2)
    }
    val server = try {
        Server(config)
    } catch (e: IllegalArgumentException) {
        fail(e.message ?: "invalid settings")
    }
    server.prepare(CoroutineScope(SupervisorJob()))
    log.info("diw-wallet-server {} on {}:{} for {}, features {}, native {}, platform {}, settings {}", serverVersion, config.listenHost,
        config.listenPort, config.publicUrl, config.features.sorted(), server.nativeBackends, config.platform.id, config.settingsDigest().take(12))
    // Session links, relay addresses and the web verifier's URLs are all built from public_url, so a name that does
    // not resolve breaks every wallet connection while /info still answers at the address actually used.
    val publicHost = java.net.URI(config.publicUrl).host
    Thread {
        if (runCatching { java.net.InetAddress.getAllByName(publicHost) }.isFailure) {
            log.warn("public_url host {} does not resolve: wallets are sent to this address, set public_url to the name clients use", publicHost)
        }
    }.apply { isDaemon = true }.start()
    embeddedServer(CIO, port = config.listenPort, host = config.listenHost) { module(server) }.start(wait = true)
}
