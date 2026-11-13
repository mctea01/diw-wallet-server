package diw.server

import diw.wallet.core.zkpassport.ZkPassportPairedClient
import diw.wallet.core.zkpassport.ZkPassportProver
import diw.wallet.core.zkpassport.ZkPassportRemote
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The wallet core's own client against this server over real sockets: token mode and TLS pairing; fictional data. */
class ZkPassportClientInteropTest {
    private val token = "zkpassport-interop-token-0123456789"
    private val chip = ZkPassportProver.Chip(byteArrayOf(0x77, 0x01, 0x02), byteArrayOf(0x61, 0x03), byteArrayOf(0x75))
    private val job = ZkPassportProver.Job(query = buildJsonObject { }, domain = "demo.example.org", scope = "adult")

    private fun freePort() = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun proving(seen: MutableList<ZkPassportProver.Chip>) = ZkPassportProving { c, _, progress ->
        seen += c
        progress(ZkPassportProver.Progress.Proving(1, 1, "disclose_bytes"))
        ZkPassportProver.Proved(
            listOf(ZkPassportProver.Proof("disclose_bytes", "0xcd", "vk", "0x02", "0.22.0", null)),
            listOf("done" to buildJsonObject { }),
            buildJsonObject { put("ok", true) },
        )
    }

    @Test
    fun aPairedWalletProvesThroughThePinnedTlsFront() = runBlocking {
        val front = freePort()
        val inner = freePort()
        val url = "https://127.0.0.1:$front"
        val config = ServerConfig.from(
            mapOf(
                "public_url" to url, "features" to "zkpassport", "listen_host" to "127.0.0.1", "listen_port" to inner.toString(),
                "trust_forwarded" to "false", "data_dir" to kotlin.io.path.createTempDirectory("diw-interop").toString(),
            ),
            pairing = true,
        )
        val tls = ProverTls.create(url)
        val seen = mutableListOf<ZkPassportProver.Chip>()
        val server = Server(config, zkPassportProving = proving(seen), zkPassportAvailable = true, zkPassportCertificateSha256 = tls.certificateSha256)
        val engine = embeddedServer(CIO, port = inner, host = "127.0.0.1") { module(server) }.start(wait = false)
        val listener = tls.serve(front, inner)
        try {
            val link = server.zkPassportPairing!!.newLink()
            val client = ZkPassportPairedClient.pair(link)
            assertEquals("pairing_used", assertFailsWith<ZkPassportProver.Failure> { ZkPassportPairedClient.pair(link) }.code)
            val proved = ZkPassportRemote.prove(client, chip, job) {}
            assertEquals("0x02", proved.proofs.single().vkeyHash)
            assertTrue(seen.single().sod.contentEquals(chip.sod))
            assertNull(seen.single().dg2)
            client.end()
            assertEquals("remote_unauthorized", assertFailsWith<ZkPassportProver.Failure> { ZkPassportRemote.prove(client, chip, job) {} }.code)
            // A code naming another certificate is refused by the client before anything is sent.
            val wrongPin = server.zkPassportPairing!!.newLink().replace(Regex("&c=[^&]+"), "&c=" + "A".repeat(43))
            assertEquals("remote_unreachable", assertFailsWith<ZkPassportProver.Failure> { ZkPassportPairedClient.pair(wrongPin) }.code)
        } finally {
            listener.close()
            engine.stop(0, 0)
        }
    }

    @Test
    fun theOwnersTokenReachesItsProver() = runBlocking {
        val port = freePort()
        val config = ServerConfig.from(
            mapOf(
                "public_url" to "http://127.0.0.1:$port", "features" to "zkpassport", "listen_host" to "127.0.0.1", "listen_port" to port.toString(),
                "zkpassport_token_sha256" to SigningIdentity.sha256Hex(token.encodeToByteArray()),
                "data_dir" to kotlin.io.path.createTempDirectory("diw-interop").toString(),
            ),
        )
        val seen = mutableListOf<ZkPassportProver.Chip>()
        val server = Server(config, zkPassportProving = proving(seen), zkPassportAvailable = true)
        val engine = embeddedServer(CIO, port = port, host = "127.0.0.1") { module(server) }.start(wait = false)
        val http = HttpClient(Java)
        try {
            val proved = ZkPassportRemote.prove(ZkPassportRemote.Host(http, "http://127.0.0.1:$port", token), chip, job) {}
            assertEquals("done", proved.messages.single().first)
            val wrong = ZkPassportRemote.Host(http, "http://127.0.0.1:$port", "not-the-token-0123456789")
            assertEquals("remote_unauthorized", assertFailsWith<ZkPassportProver.Failure> { ZkPassportRemote.prove(wrong, chip, job) {} }.code)
        } finally {
            http.close()
            engine.stop(0, 0)
        }
    }
}
