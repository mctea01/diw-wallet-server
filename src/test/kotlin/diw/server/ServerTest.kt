package diw.server

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerTest {
    private fun config(relay: RelayLimits = RelayLimits()) = ServerConfig(
        publicUrl = "https://diw.example.org",
        dataDir = Files.createTempDirectory("diw-server-test").toFile(),
        relay = relay,
    )

    private fun room(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))

    private fun ApplicationTestBuilder.start(server: Server) = application { module(server) }

    private fun ApplicationTestBuilder.wsClient() = createClient { install(WebSockets) }

    @Test
    fun healthAndInfo() = testApplication {
        start(Server(config()))
        assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
        val info = Json.parseToJsonElement(client.get("/info").bodyAsText()).jsonObject
        assertEquals("diw-wallet-server", info["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("relay"), info["features"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun relayForwardsBothWaysAndForgetsTheRoom() = testApplication {
        val server = Server(config())
        start(server)
        val ws = wsClient()
        val id = room()
        val a = ws.webSocketSession("/zktls/relay/$id")
        val b = ws.webSocketSession("/zktls/relay/$id")
        a.send(Frame.Binary(true, byteArrayOf(1, 2, 3)))
        assertContentEquals(byteArrayOf(1, 2, 3), (b.incoming.receive() as Frame.Binary).readBytes())
        b.send(Frame.Binary(true, byteArrayOf(9)))
        assertContentEquals(byteArrayOf(9), (a.incoming.receive() as Frame.Binary).readBytes())
        a.close()
        withTimeout(5_000) { while (server.relay!!.size != 0) delay(20) }
    }

    @Test
    fun aThirdPartyAndMalformedRoomsAreRefused() = testApplication {
        start(Server(config()))
        val ws = wsClient()
        val id = room()
        val a = ws.webSocketSession("/zktls/relay/$id")
        val b = ws.webSocketSession("/zktls/relay/$id")
        val c = ws.webSocketSession("/zktls/relay/$id")
        assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, c.closeReason.await()?.code)
        val bad = ws.webSocketSession("/zktls/relay/not-a-room")
        assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, bad.closeReason.await()?.code)
        a.close(); b.close()
    }

    @Test
    fun oneAddressHasBoundedConnections() = testApplication {
        start(Server(config(RelayLimits(perAddress = 2))))
        val ws = wsClient()
        val open = List(2) { ws.webSocketSession("/zktls/relay/${room()}") }
        val third = ws.webSocketSession("/zktls/relay/${room()}")
        assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, third.closeReason.await()?.code)
        open.forEach { it.close() }
    }

    @Test
    fun anOversizedFrameEndsTheRoom() = testApplication {
        start(Server(config(RelayLimits(maxFrame = 16))))
        val ws = wsClient()
        val id = room()
        val a = ws.webSocketSession("/zktls/relay/$id")
        val b = ws.webSocketSession("/zktls/relay/$id")
        a.send(Frame.Binary(true, ByteArray(17)))
        // The peer is closed without receiving the frame.
        val frame = withTimeout(5_000) { b.incoming.receiveCatching().getOrNull() }
        assertTrue(frame == null || frame is Frame.Close)
    }

    @Test
    fun configFromValues() {
        val c = ServerConfig.from(mapOf("public_url" to "https://diw.example.org/", "relay_max_mb" to "32", "listen_host" to "0.0.0.0"))
        assertEquals("https://diw.example.org", c.publicUrl)
        assertEquals(32L shl 20, c.relay.maxBytes)
        assertFalse(c.trustForwarded)
        assertTrue(ServerConfig.from(mapOf("public_url" to "https://x", "listen_host" to "0.0.0.0", "trust_forwarded" to "true")).trustForwarded)
        assertFailsWith<IllegalArgumentException> { ServerConfig.from(emptyMap()) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.from(mapOf("public_url" to "ftp://x")) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.from(mapOf("public_url" to "https://x", "features" to "prover")) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.from(mapOf("public_url" to "https://x", "relay_max_rooms" to "0")) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.from(mapOf("public_url" to "https://x", "typo" to "1")) }
    }

    @Test
    fun configFromEnvironmentOverridesTheFile() {
        val file = Files.createTempFile("server", ".conf").toFile()
        file.writeText("public_url=https://file.example.org\nlisten_port=9000\n")
        val c = ServerConfig.load(mapOf("DIW_CONFIG" to file.path, "DIW_LISTEN_PORT" to "9100", "DIW_ZK_NATIVE" to "skip"))
        assertEquals("https://file.example.org", c.publicUrl)
        assertEquals(9100, c.listenPort)
        // A platform's PORT applies only when the port is not set explicitly.
        assertEquals(9000, ServerConfig.load(mapOf("DIW_CONFIG" to file.path, "PORT" to "7000")).listenPort)
        assertEquals(7000, ServerConfig.load(mapOf("DIW_PUBLIC_URL" to "https://x", "DIW_CONFIG" to " ", "PORT" to "7000")).listenPort)
    }
}
