package diw.server

import diw.wallet.core.did.DidResolver
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

    /** The key that signs zkTLS requests and receipts; only the zktls feature needs it. */
    val signing: SigningIdentity? = if (ServerConfig.FEATURE_ZKTLS !in config.features) null else runBlocking {
        SigningIdentity.load(config.signingKeyFile ?: File(config.dataDir, "signing.pem"), config.publicUrl, config.signingGenerate, config.signingKeyPem)
    }

    val templates: ZkTemplates? = if (signing == null) null else
        ZkTemplates(http, config.dataDir, config.zkTlsTemplatesFile, templateKeys ?: ZkTemplatePack.trustedKeys())

    private val tlsn = zkTlsAvailable ?: (ZkBackend.TLSN in native)

    val zkTls: ZkTlsHost? = signing?.let { id ->
        ZkTlsHost(config.publicUrl, id, config.zkTls, { BuiltInAnchors.all.map { it.certificate.encoded.toByteArray() }.distinct() + zkTlsRoots }) { tlsn }
    }

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
     * With bonds_age_verify, installs the OpenAC verifying keys under the data directory (pinned hashes, fetched once)
     * and then verifies here; until then, or without the native library, the age verifier only relays.
     */
    fun prepare(scope: CoroutineScope) {
        templates?.start(scope)
        zkTls?.start(scope)
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
            }
            call.respondText(body.toString(), ContentType.Application.Json)
        }
        server.relay?.let { relayRoutes(it) }
        server.bondsAge?.let { bondsAgeRoutes(it, server.config.bondsAge) }
        server.verifier?.let { verifierRoutes(it, server.config.verifierAccessToken) }
        server.zkTls?.let { zkTlsRoutes(it, server.config.zkTlsTokenHashes) }
    }
}

/**
 * `keygen <file> <public_url>` writes a signing file, or with `verifier` the web verifier's key, for a platform
 * secret (signing_key_pem, verifier_key_pem); `hash-token` prints the SHA-256 of stdin's first line.
 */
private fun command(args: Array<String>): Boolean {
    when (args.firstOrNull()) {
        null -> return false
        "keygen" -> {
            val file = args.getOrNull(1)?.let { File(it) }
            val url = args.getOrNull(2)
            if (file == null || url == null) {
                System.err.println("usage: diw-server keygen <file> <public_url> [verifier]")
                exitProcess(2)
            }
            if (file.exists()) {
                System.err.println("diw-server: ${file.path} exists; remove it first")
                exitProcess(2)
            }
            if (args.getOrNull(3) == "verifier") {
                val identity = runBlocking { TwdiwVerifier.Identity.create() }
                writePrivateFile(file, TwdiwVerifier.Identity.pem(identity))
                println("Wrote ${file.path}; verifier DID ${identity.did}")
            } else {
                val identity = runBlocking { SigningIdentity.create(url) }
                SigningIdentity.write(file, identity)
                println("Wrote ${file.path}; root SHA-256 ${identity.rootSha256}")
            }
        }
        "hash-token" -> {
            val token = readlnOrNull()?.trim().orEmpty()
            if (token.length < 16) {
                System.err.println("diw-server: use a token of at least 16 characters")
                exitProcess(2)
            }
            println(SigningIdentity.sha256Hex(token.encodeToByteArray()))
        }
        else -> {
            System.err.println("usage: diw-server [keygen <file> <public_url> [verifier] | hash-token]")
            exitProcess(2)
        }
    }
    return true
}

fun main(args: Array<String>) {
    if (command(args)) return
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
