package diw.server

import diw.wallet.core.util.DiwJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * One-time QR pairing for a temporary prover on a private network (DESIGN 3.10). The link carries a 256-bit secret
 * that never travels again: the wallet proves it with an HMAC once, both sides derive a session key from it and the
 * two nonces, and the link is spent. Every later call carries a sealed counter, method and path in its Authorization
 * header, and request and response bodies are AES-256-GCM sealed, so plain HTTP on the LAN shows nothing and a
 * captured call cannot be replayed. Sessions live in memory and end after [idle] without use or with the process.
 */
class ZkPassportPairing(
    private val url: String,
    private val clock: () -> Instant = { Clock.System.now() },
    private val offerLifetime: Duration = 10.minutes,
    private val idle: Duration = 30.minutes,
) {
    private class Offer(val secret: ByteArray, val expires: Instant)
    private class Session(val key: SecretKeySpec, var last: Long, var used: Instant)

    private val random = SecureRandom()
    private var offer: Offer? = null
    private val sessions = HashMap<String, Session>()

    /** A new one-time link; any unused earlier link stops working. */
    @Synchronized
    fun newLink(): String {
        val secret = ByteArray(32).also(random::nextBytes)
        offer = Offer(secret, clock() + offerLifetime)
        return "diw-prover://v1?u=" + URLEncoder.encode(url, Charsets.UTF_8) + "&p=" + B64.encodeToString(secret)
    }

    sealed interface Paired {
        data class Ok(val session: String, val nonce: String) : Paired
        data object Refused : Paired
        data object Gone : Paired
    }

    /** Spends the current link when [mac] is HMAC(secret, PAIR || nonce); the session key also mixes a server nonce. */
    @Synchronized
    fun pair(nonce: ByteArray, mac: ByteArray): Paired {
        prune()
        val o = offer ?: return Paired.Gone
        if (clock() > o.expires) {
            offer = null
            return Paired.Gone
        }
        if (nonce.size != NONCE || !MessageDigest.isEqual(hmac(o.secret, PAIR, nonce), mac)) return Paired.Refused
        offer = null
        val serverNonce = ByteArray(NONCE).also(random::nextBytes)
        val id = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        sessions[id] = Session(SecretKeySpec(hmac(o.secret, SESSION, nonce + serverNonce), "AES"), 0, clock())
        return Paired.Ok(id, B64.encodeToString(serverNonce))
    }

    /** Bodies of one authorized call, sealed under its session key and counter. */
    class Seal internal constructor(private val key: SecretKeySpec, private val context: String) {
        /** The request body `{sealed}` opened; null when it is not sealed for this call. */
        fun open(body: JsonObject): JsonObject? {
            val sealed = (body["sealed"] as? JsonPrimitive)?.contentOrNull ?: return null
            val plain = decrypt(key, sealed, "$context request") ?: return null
            return runCatching { DiwJson.parseToJsonElement(plain.decodeToString()) as JsonObject }.getOrNull()
        }

        fun seal(body: JsonObject): JsonObject = buildJsonObject {
            put("sealed", encrypt(key, body.toString().encodeToByteArray(), "$context response"))
        }
    }

    /**
     * Checks `DIW-Session <id>.<sealed {n, m, p}>`: a known, recently used session, a counter above the last one and
     * the call's own method and path. Null otherwise.
     */
    @Synchronized
    fun authorize(header: String?, method: String, path: String): Seal? {
        prune()
        val value = header?.takeIf { it.startsWith(SCHEME) }?.removePrefix(SCHEME)?.trim() ?: return null
        val id = value.substringBefore('.')
        val session = sessions[id] ?: return null
        val plain = decrypt(session.key, value.substringAfter('.', ""), "$AUTH $id") ?: return null
        val o = runCatching { DiwJson.parseToJsonElement(plain.decodeToString()) as JsonObject }.getOrNull() ?: return null
        val n = (o["n"] as? JsonPrimitive)?.longOrNull ?: return null
        if (n <= session.last) return null
        if ((o["m"] as? JsonPrimitive)?.contentOrNull != method || (o["p"] as? JsonPrimitive)?.contentOrNull != path) return null
        session.last = n
        session.used = clock()
        return Seal(session.key, "$id $n")
    }

    /** Ends the session; false for an unknown one. */
    @Synchronized
    fun end(header: String?): Boolean {
        val id = header?.takeIf { it.startsWith(SCHEME) }?.removePrefix(SCHEME)?.trim()?.substringBefore('.') ?: return false
        return sessions.remove(id) != null
    }

    private fun prune() {
        val now = clock()
        sessions.entries.removeAll { now - it.value.used > idle }
    }

    companion object {
        const val SCHEME = "DIW-Session "
        private const val NONCE = 16
        private val PAIR = "diw-prover pair v1".encodeToByteArray()
        private val SESSION = "diw-prover session v1".encodeToByteArray()
        private const val AUTH = "diw-prover auth v1"
        private val B64 = Base64.getUrlEncoder().withoutPadding()
        private val B64D = Base64.getUrlDecoder()

        fun hmac(key: ByteArray, label: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(label + data)

        /** base64url of iv || AES-GCM ciphertext, with [aad] bound. */
        fun encrypt(key: SecretKeySpec, plain: ByteArray, aad: String): String {
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
                updateAAD(aad.encodeToByteArray())
            }
            return B64.encodeToString(iv + c.doFinal(plain))
        }

        fun decrypt(key: SecretKeySpec, sealed: String, aad: String): ByteArray? = runCatching {
            val bytes = B64D.decode(sealed)
            require(bytes.size > 12 + 16)
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
                updateAAD(aad.encodeToByteArray())
            }.doFinal(bytes, 12, bytes.size - 12)
        }.getOrNull()

        fun decode(value: String?): ByteArray? = value?.let { runCatching { B64D.decode(it) }.getOrNull() }

        /**
         * Loopback, private (RFC 1918, fc00::/7), link-local or shared (100.64.0.0/10, used by Tailscale) addresses:
         * a pairing prover answers nobody from the internet, even when a router forwards its port.
         */
        fun privateAddress(address: String): Boolean {
            val a = literal(address) ?: return false
            if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
            val b = a.address
            return when (b.size) {
                4 -> (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
                16 -> (b[0].toInt() and 0xfe) == 0xfc
                else -> false
            }
        }

        fun loopback(address: String): Boolean = literal(address)?.isLoopbackAddress == true

        /** An IP literal; a name is never resolved. */
        private fun literal(address: String): InetAddress? {
            val bare = address.removePrefix("[").removeSuffix("]").substringBefore('%')
            // What an engine reports for an in-process client; a real socket always gives a literal.
            if (bare == "localhost") return InetAddress.getLoopbackAddress()
            if (bare.isEmpty() || !bare.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it in ".:" }) return null
            return runCatching { InetAddress.getByName(bare) }.getOrNull()
        }
    }
}
