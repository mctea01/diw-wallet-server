package diw.server

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

private const val PROVER_USAGE = "usage: diw-server prover [--port <port>] [--address <ip>]"

/**
 * `diw-server prover`: a temporary zkPassport prover for wallets on this network (DESIGN 3.10). It needs no settings
 * file: it listens on every interface, answers only private addresses and lets a wallet in with a one-time QR code
 * printed here, never in a log. DIW_DATA_DIR (default ~/.diw-server) keeps the CRS between runs; DIW_ZKPASSPORT_RPC,
 * DIW_ZKPASSPORT_SEPOLIA_RPC and DIW_ZKPASSPORT_QUEUE apply as on a server.
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
    val env = System.getenv()
    val values = mutableMapOf(
        "public_url" to "http://${if (':' in host) "[$host]" else host}:$port",
        "features" to ServerConfig.FEATURE_ZKPASSPORT,
        "zkpassport_pairing" to "true",
        "listen_host" to "0.0.0.0",
        "listen_port" to port.toString(),
        "trust_forwarded" to "false",
        "data_dir" to (env["DIW_DATA_DIR"]?.takeIf { it.isNotBlank() } ?: File(System.getProperty("user.home"), ".diw-server").path),
    )
    for (key in listOf("zkpassport_rpc", "zkpassport_sepolia_rpc", "zkpassport_queue")) {
        env[ConfigKeys.get(key)!!.env]?.takeIf { it.isNotBlank() }?.let { values[key] = it.trim() }
    }
    val config = try {
        ServerConfig.from(values)
    } catch (e: IllegalArgumentException) {
        fail(e.message ?: "invalid settings")
    }
    if (!config.dataDir.isDirectory && !config.dataDir.mkdirs()) fail("cannot create data directory ${config.dataDir}")
    val server = Server(config)
    server.prepare(CoroutineScope(SupervisorJob()))
    if (!server.healthy) System.err.println("diw-server: the native library has no UltraHonk prover; proofs fail until a build with zkpdf is used")
    embeddedServer(CIO, port = config.listenPort, host = config.listenHost) { module(server) }.start(wait = false)
    val pairing = server.zkPassportPairing!!
    fun show() {
        val link = pairing.newLink()
        println()
        print(terminalQr(link))
        println(link)
        println("zkPassport prover at ${config.publicUrl}. Scan this one-time code with the wallet; it expires in 10 minutes.")
        println("Enter: a new code (for another phone or after it expired). Ctrl+C: stop; every session and job ends with it.")
    }
    show()
    while (true) {
        // Without a terminal (started in the background) the first code is the only one; keep serving.
        if (readlnOrNull() == null) Thread.currentThread().join()
        show()
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

/** [text] as a QR code drawn with ANSI background colours, two spaces per module, quiet zone included. */
internal fun terminalQr(text: String): String {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L),
    )
    val dark = "\u001B[40m  "
    val light = "\u001B[47m  "
    return buildString {
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) append(if (matrix.get(x, y)) dark else light)
            append("\u001B[0m\n")
        }
    }
}
