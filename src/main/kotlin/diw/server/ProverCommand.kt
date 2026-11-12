package diw.server

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.Semaphore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

private const val PROVER_USAGE = "usage: diw-server prover [--port <port>] [--address <ip>]"

/**
 * `diw-server prover`: a temporary zkPassport prover for wallets on this network (DESIGN 3.10). It needs no settings
 * file. A TLS front on every interface admits private peers only and forwards to the server on loopback; its
 * certificate is made for this run and pinned by the wallet through the one-time QR code printed here (never in a
 * log), so phones whose release builds refuse clear text still connect. DIW_DATA_DIR (default ~/.diw-server) keeps
 * the CRS between runs; DIW_ZKPASSPORT_RPC, DIW_ZKPASSPORT_SEPOLIA_RPC and DIW_ZKPASSPORT_QUEUE apply as on a server.
 */
internal fun runProver(args: List<String>) {
    var port = 8090
    var address: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> port = args.getOrNull(++i)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: fail(PROVER_USAGE)
            "--address" -> address = args.getOrNull(++i) ?: fail(PROVER_USAGE)
            else -> fail(PROVER_USAGE)
        }
        i++
    }
    val host = address ?: lanAddress() ?: fail("no private network address found; name one with --address")
    if (!ZkPassportPairing.privateAddress(host)) fail("$host is not a private network address")
    val publicUrl = "https://${if (':' in host) "[$host]" else host}:$port"
    val inner = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
    val env = System.getenv()
    val values = mutableMapOf(
        "public_url" to publicUrl,
        "features" to ServerConfig.FEATURE_ZKPASSPORT,
        "listen_host" to "127.0.0.1",
        "listen_port" to inner.toString(),
        "trust_forwarded" to "false",
        "data_dir" to (env["DIW_DATA_DIR"]?.takeIf { it.isNotBlank() } ?: File(System.getProperty("user.home"), ".diw-server").path),
    )
    for (key in listOf("zkpassport_rpc", "zkpassport_sepolia_rpc", "zkpassport_queue")) {
        env[ConfigKeys.get(key)!!.env]?.takeIf { it.isNotBlank() }?.let { values[key] = it.trim() }
    }
    val config = try {
        ServerConfig.from(values, pairing = true)
    } catch (e: IllegalArgumentException) {
        fail(e.message ?: "invalid settings")
    }
    if (!config.dataDir.isDirectory && !config.dataDir.mkdirs()) fail("cannot create data directory ${config.dataDir}")
    val tls = ProverTls.create(publicUrl)
    val server = Server(config, zkPassportCertificateSha256 = tls.certificateSha256)
    server.prepare(CoroutineScope(SupervisorJob()))
    if (!server.healthy) System.err.println("diw-server: the native library has no UltraHonk prover; proofs fail until a build with zkpdf is used")
    embeddedServer(CIO, port = inner, host = "127.0.0.1") { module(server) }.start(wait = false)
    try {
        tls.serve(port, inner)
    } catch (e: java.io.IOException) {
        fail("cannot listen on port $port: ${e.message}")
    }
    val pairing = server.zkPassportPairing!!
    // The classic Windows console shows ANSI colours as text; Windows Terminal (WT_SESSION) draws them.
    val image = if (System.getProperty("os.name").orEmpty().startsWith("Windows") && System.getenv("WT_SESSION") == null) {
        // In the user's own data directory, not the shared temporary one: the code is a secret until it is used.
        File(config.dataDir, "pairing-code.svg").apply { deleteOnExit() }
    } else null
    fun show() {
        val link = pairing.newLink()
        println()
        if (image != null) {
            image.writeText(svgQr(link), Charsets.UTF_8)
            println("Open ${image.path} to see the code; it is deleted when the prover stops.")
        } else {
            print(terminalQr(link))
        }
        println(link)
        println("zkPassport prover at $publicUrl. Scan this one-time code with the wallet; it expires in 10 minutes.")
        println("Enter: a new code (for another phone or after it expired). Ctrl+C: stop; every session and job ends with it.")
    }
    show()
    while (true) {
        // Without a terminal (started in the background) the first code is the only one; keep serving.
        if (readlnOrNull() == null) Thread.currentThread().join()
        show()
    }
}

/** The TLS front of diw-server prover: a certificate for this run only, and a forwarder to the loopback server. */
internal class ProverTls private constructor(private val context: SSLContext, val certificateSha256: ByteArray) {
    private val slots = Semaphore(MAX_CONNECTIONS)

    /** Accepts TLS on every interface at [port], admits private peers only and pipes each connection to [target]. */
    fun serve(port: Int, target: Int): SSLServerSocket {
        val listener = context.serverSocketFactory.createServerSocket(port, 64, InetAddress.getByName("0.0.0.0")) as SSLServerSocket
        Thread({
            while (!listener.isClosed) {
                val client = runCatching { listener.accept() as SSLSocket }.getOrNull() ?: continue
                if (!ZkPassportPairing.privateAddress(client.inetAddress.hostAddress) || !slots.tryAcquire()) {
                    runCatching { client.close() }
                    continue
                }
                Thread({ forward(client, target) }, "prover-tls").apply { isDaemon = true }.start()
            }
        }, "prover-tls-accept").apply { isDaemon = true }.start()
        return listener
    }

    private fun forward(client: SSLSocket, target: Int) {
        try {
            client.soTimeout = IDLE_MS
            client.startHandshake()
            Socket(InetAddress.getLoopbackAddress(), target).use { upstream ->
                upstream.soTimeout = IDLE_MS
                val back = Thread({ pipe(upstream.getInputStream(), client.getOutputStream()) }, "prover-tls-back").apply { isDaemon = true }
                back.start()
                pipe(client.getInputStream(), upstream.getOutputStream())
                runCatching { upstream.shutdownOutput() }
                back.join(IDLE_MS.toLong())
            }
        } catch (_: Exception) {
            // A failed handshake or a dropped connection ends only this connection.
        } finally {
            runCatching { client.close() }
            slots.release()
        }
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        runCatching {
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                output.flush()
            }
        }
    }

    companion object {
        private const val MAX_CONNECTIONS = 16
        // Polling a long proof keeps connections busy for a few seconds at most; an idle one is closed.
        private const val IDLE_MS = 120_000

        /** A P-256 key and certificate made for this run, kept in memory only. */
        fun create(publicUrl: String): ProverTls {
            val identity = runBlocking { SigningIdentity.create(publicUrl) }
            val pem = identity.key.privateKey.toPem()
            val der = Base64.getMimeDecoder().decode(pem.lines().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString(""))
            require(pem.contains("BEGIN PRIVATE KEY")) { "unexpected key format" }
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
            val factory = CertificateFactory.getInstance("X.509")
            val chain = identity.chain.map { factory.generateCertificate(it.encoded.toByteArray().inputStream()) as X509Certificate }
            val password = CharArray(0)
            val store = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("prover", key, password, chain.toTypedArray())
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }.keyManagers
            val context = SSLContext.getInstance("TLS").apply { init(managers, null, SecureRandom()) }
            return ProverTls(context, MessageDigest.getInstance("SHA-256").digest(chain.first().encoded))
        }
    }
}

/** The likeliest LAN address: a private IPv4 address of an interface that is up, skipping container bridges. */
private fun lanAddress(): String? {
    val skipped = listOf("docker", "br-", "veth", "virbr", "vmnet", "vboxnet", "cni", "flannel")
    val candidates = NetworkInterface.getNetworkInterfaces().toList()
        .filter { n -> runCatching { n.isUp && !n.isLoopback && !n.isVirtual }.getOrDefault(false) && skipped.none { n.name.startsWith(it) } }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { ZkPassportPairing.privateAddress(it.hostAddress) && !it.isLoopbackAddress }
        .map { it.hostAddress }
    return candidates.minByOrNull {
        when {
            it.startsWith("192.168.") -> 0
            it.startsWith("10.") -> 1
            else -> 2
        }
    }
}

private fun qrMatrix(text: String) = QRCodeWriter().encode(
    text, BarcodeFormat.QR_CODE, 0, 0,
    mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L),
)

/** [text] as a QR code drawn with ANSI background colours, two spaces per module, quiet zone included. */
internal fun terminalQr(text: String): String {
    val matrix = qrMatrix(text)
    val dark = "\u001B[40m  "
    val light = "\u001B[47m  "
    return buildString {
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) append(if (matrix.get(x, y)) dark else light)
            append("\u001B[0m\n")
        }
    }
}

/** [text] as an SVG QR code, for consoles without ANSI colours. */
internal fun svgQr(text: String): String {
    val matrix = qrMatrix(text)
    return buildString {
        append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${matrix.width} ${matrix.height}\" width=\"${matrix.width * 8}\" ")
        append("height=\"${matrix.height * 8}\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path fill=\"#000\" d=\"")
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix.get(x, y)) append("M${x} ${y}h1v1h-1z")
        append("\"/></svg>\n")
    }
}
