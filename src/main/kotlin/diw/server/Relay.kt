package diw.server

import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The wallet's relay (wallet docs/design/p4-zkp.md section 5.2): two parties each open `/zktls/relay/{room}` and the
 * relay forwards binary frames between them. It sees only end-to-end encrypted bytes, plays no part in trust and
 * stores nothing. A room takes two connections and lives within [RelayLimits]; one address holds a bounded number of
 * connections at once.
 */
class Relay(private val limits: RelayLimits) {
    private class Room(val first: DefaultWebSocketServerSession, val created: Instant) {
        val second = CompletableDeferred<DefaultWebSocketServerSession>()
        val bytes = AtomicLong(0)
    }

    private val rooms = HashMap<String, Room>()
    private val addresses = HashMap<String, Int>()

    /** Rooms waiting or in use. */
    val size: Int get() = synchronized(rooms) { rooms.size }

    suspend fun join(id: String, address: String, session: DefaultWebSocketServerSession) {
        if (!validRoom(id)) {
            session.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Malformed room"))
            return
        }
        val admitted = synchronized(addresses) {
            val n = addresses[address] ?: 0
            if (n >= limits.perAddress) false else { addresses[address] = n + 1; true }
        }
        if (!admitted) {
            session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too many connections"))
            return
        }
        try {
            serve(id, session)
        } finally {
            synchronized(addresses) {
                val n = (addresses[address] ?: 1) - 1
                if (n <= 0) addresses.remove(address) else addresses[address] = n
            }
        }
    }

    private suspend fun serve(id: String, session: DefaultWebSocketServerSession) {
        val now = Clock.System.now()
        val (room, first) = synchronized(rooms) {
            rooms.entries.removeIf { now - it.value.created > limits.lifetime }
            val existing = rooms[id]
            when {
                existing == null && rooms.size >= limits.maxRooms -> null to false
                existing == null -> Room(session, now).also { rooms[id] = it } to true
                existing.second.isCompleted -> null to false
                else -> existing.also { it.second.complete(session) } to false
            }
        }
        if (room == null) {
            session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Room unavailable"))
            return
        }
        try {
            withTimeoutOrNull(limits.lifetime - (now - room.created)) {
                val peer = if (first) {
                    // Wait for the other side, or for this side to leave first.
                    select {
                        room.second.onAwait { it }
                        session.closeReason.onAwait { null }
                    } ?: return@withTimeoutOrNull
                } else room.first
                forward(session, peer, room)
            }
        } finally {
            synchronized(rooms) { if (rooms[id] === room) rooms.remove(id) }
            runCatching { session.close() }
            if (room.second.isCompleted) {
                val other = if (first) room.second.getCompleted() else room.first
                runCatching { other.close() }
            } else {
                room.second.cancel()
            }
        }
    }

    /** Copies this side's binary frames to [peer] until this side ends or the room reaches its limit. */
    private suspend fun forward(from: DefaultWebSocketServerSession, peer: DefaultWebSocketServerSession, room: Room) {
        for (frame in from.incoming) {
            if (frame !is Frame.Binary) continue
            val data = frame.data
            if (data.size > limits.maxFrame || room.bytes.addAndGet(data.size.toLong()) > limits.maxBytes) break
            peer.send(Frame.Binary(true, data))
        }
    }

    companion object {
        /** A room is 16 random bytes in unpadded base64url. */
        fun validRoom(id: String): Boolean = id.length == 22 &&
            runCatching { Base64.getUrlDecoder().decode(id).size == 16 }.getOrDefault(false)
    }
}

fun Route.relayRoutes(relay: Relay) {
    webSocket("/zktls/relay/{room}") { relay.join(call.parameters["room"].orEmpty(), call.request.origin.remoteAddress, this) }
}
