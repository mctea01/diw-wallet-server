package diw.server

import diw.wallet.core.did.DidKeys
import diw.wallet.core.did.DidResolver
import diw.wallet.core.jose.Jws
import diw.wallet.core.jose.VerificationKey
import diw.wallet.core.jose.signJws
import diw.wallet.core.profile.bonds.MoicaSignedCredential
import diw.wallet.core.status.W3cStatusChecker
import diw.wallet.core.trust.DidTrust
import diw.wallet.core.trust.TrustRole
import diw.wallet.core.trust.TrustTier
import diw.wallet.core.util.arr
import diw.wallet.core.util.obj
import diw.wallet.core.util.str
import diw.wallet.core.util.strList
import diw.wallet.core.w3c.SdJwtDisclosures
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Limits of the web verifier: sessions at once and sessions one address may hold. */
data class VerifierLimits(val maxSessions: Int = 64, val perAddress: Int = 8)

/** What a verifier asks for: a TWDIW card of [cardType], or Bonds' self-issued citizen certificate card. */
data class VerifierAsk(val kind: Kind, val cardType: String?, val cardName: String?, val fields: List<String>, val purpose: String) {
    enum class Kind { TWDIW, MOICA }

    companion object {
        private val FIELD = Regex("^[A-Za-z0-9_]{1,40}$")
        const val MAX_FIELDS = 20

        /** The ask in a session request, or a message saying what is wrong. */
        fun parse(body: JsonObject): Result<VerifierAsk> = runCatching {
            val kind = when (body.str("kind")) {
                "twdiw", null -> Kind.TWDIW
                "moica" -> Kind.MOICA
                else -> throw IllegalArgumentException("kind is twdiw or moica")
            }
            val fields = body.strList("fields").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            require(fields.isNotEmpty() && fields.size <= MAX_FIELDS) { "Name 1 to $MAX_FIELDS fields" }
            require(fields.all { FIELD.matches(it) }) { "Field names are letters, digits and _" }
            val type = body.str("cardType")?.trim()?.takeIf { it.isNotEmpty() }
            if (kind == Kind.TWDIW) {
                require(type != null && type.length <= 80 && type.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
                    "cardType is the card's type name (letters, digits, _ - .)"
                }
            }
            val clean = { s: String?, max: Int -> s?.filter { !it.isISOControl() }?.trim()?.take(max)?.takeIf { it.isNotEmpty() } }
            VerifierAsk(kind, if (kind == Kind.TWDIW) type else MOICA_TYPE, clean(body.str("cardName"), 60), fields,
                clean(body.str("purpose"), 100) ?: "身分查驗")
        }

        const val MOICA_TYPE = "NationalIDCredential"
    }
}

/** The outcome shown to the verifier. Disclosed values live only in memory and are never logged. */
data class VerifierResult(
    val accepted: Boolean,
    val summary: String,
    val notes: List<String>,
    val fields: List<Pair<String, String>>,
    val issuer: String?,
    val issuerName: String?,
    val issuerTier: String?,
    val status: String?,
) {
    fun json(): JsonObject = buildJsonObject {
        put("accepted", accepted)
        put("summary", summary)
        putJsonArray("notes") { notes.forEach { add(it) } }
        putJsonArray("fields") { fields.forEach { (k, v) -> addJsonObject { put("name", k); put("value", v) } } }
        issuer?.let { put("issuer", it) }
        issuerName?.let { put("issuerName", it) }
        issuerTier?.let { put("issuerTier", it) }
        status?.let { put("status", it) }
    }
}

/**
 * A web verifier for cards in the government wallet, in the official verifier's dialect (wallet docs/research/twdiw.md
 * sections 1.4 and 4.3): a bare did:key client, a signed request object by reference, Presentation Exchange with the
 * card type as a `$.type` filter, `direct_post`, and a jwt_vp holding the card's SD-JWT. Bonds' self-issued citizen
 * certificate card (vc+moica) is asked for the same way. The issuer must be on the TWDIW trust list (T2 or better)
 * for the card to pass; its status list is checked when it names one. Sessions live in memory only.
 */
class TwdiwVerifier(
    private val publicUrl: String,
    private val limits: VerifierLimits,
    private val name: String,
    private val identity: Identity,
    private val resolver: DidResolver,
    private val didTrust: DidTrust,
    private val status: W3cStatusChecker?,
) {
    /** The verifier's signing key and the did:key (jwk_jcs-pub) it is known by. */
    class Identity(val key: EcPrivateKey, val did: String) {
        companion object {
            suspend fun create(): Identity = Crypto.createEcPrivateKey(EcCurve.P256).let { Identity(it, DidKeys.didKeyJwkJcs(it.publicKey)) }

            private val PRIVATE = Regex("-----BEGIN (EC )?PRIVATE KEY-----[\\s\\S]+?-----END (EC )?PRIVATE KEY-----")
            private val PUBLIC = Regex("-----BEGIN PUBLIC KEY-----[\\s\\S]+?-----END PUBLIC KEY-----")

            /** The private key PEM followed by the public key PEM, the form [parse] reads (a platform secret). */
            fun pem(identity: Identity): String = identity.key.toPem() + "\n" + identity.key.publicKey.toPem() + "\n"

            suspend fun parse(text: String): Identity {
                val public = PUBLIC.find(text)?.value?.let { EcPublicKey.fromPem(it) } ?: throw IllegalArgumentException("No public key in verifier_key_pem")
                val key = PRIVATE.find(text)?.value?.let { EcPrivateKey.fromPem(it, public) } ?: throw IllegalArgumentException("No private key in verifier_key_pem")
                return Identity(key, DidKeys.didKeyJwkJcs(public))
            }

            /** The key in [directory], made on first use (0600) so the DID stays the same across restarts. */
            suspend fun load(directory: File): Identity {
                directory.mkdirs()
                val pem = File(directory, "verifier-key.pem")
                val pub = File(directory, "verifier-key.jwk")
                if (pem.isFile && pub.isFile) {
                    val public = EcPublicKey.fromJwk(Json.parseToJsonElement(pub.readText()) as JsonObject)
                    val key = EcPrivateKey.fromPem(pem.readText(), public)
                    return Identity(key, DidKeys.didKeyJwkJcs(public))
                }
                val created = create()
                writePrivate(pem, created.key.toPem())
                pub.writeText(created.key.publicKey.toJwk().toString())
                return created
            }

            private fun writePrivate(file: File, text: String) {
                val path = file.toPath()
                Files.deleteIfExists(path)
                runCatching { Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) }
                    .getOrElse { Files.createFile(path) }
                file.writeText(text)
            }
        }
    }

    class Session(val id: String, val token: String, val nonce: String, val ask: VerifierAsk, val created: Instant, val creator: String) {
        @Volatile var result: VerifierResult? = null
        @Volatile var fetched = false
        val expiresAt: Instant get() = created + LIFETIME
        val deadline: Instant get() = expiresAt + RESULT_KEPT
        val definitionId: String get() = if (ask.kind == VerifierAsk.Kind.MOICA) MOICA_DEFINITION else "diw-${id.take(8)}"
        val descriptorId: String get() = if (ask.kind == VerifierAsk.Kind.MOICA) "credential" else "${ask.cardType}_1"
    }

    sealed interface Created {
        data class Ok(val session: Session) : Created
        data class Refused(val message: String) : Created
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    val did: String get() = identity.did

    val size: Int get() = sessions.size

    fun create(ask: VerifierAsk, address: String): Created {
        val now = Clock.System.now()
        synchronized(sessions) {
            sessions.values.removeIf { now > it.deadline }
            if (sessions.size >= limits.maxSessions || sessions.values.count { it.creator == address } >= limits.perAddress) {
                return Created.Refused("Too many open sessions")
            }
            val s = Session(token(16), token(32), token(24), ask, Instant.fromEpochSeconds(now.epochSeconds), address)
            sessions[s.id] = s
            return Created.Ok(s)
        }
    }

    fun session(id: String, token: String?): Session? = sessions[id]?.takeIf {
        token != null && MessageDigest.isEqual(it.token.encodeToByteArray(), token.encodeToByteArray()) && Clock.System.now() <= it.deadline
    }

    /** The wallet link: modadigitalwallet:// for the government wallet, openid4vp:// for others. */
    fun deepLink(s: Session, scheme: String): String {
        val enc = { v: String -> URLEncoder.encode(v, Charsets.UTF_8) }
        val base = if (scheme == "openid4vp") "openid4vp://authorize" else "modadigitalwallet://authorize"
        return "$base?client_id=${enc(identity.did)}&request_uri=${enc("$publicUrl/api/oidvp/request/${s.id}")}"
    }

    /** The signed request object of an open session, or null when it is unknown, expired or already answered. */
    suspend fun requestObject(id: String): String? {
        val s = sessions[id]?.takeIf { Clock.System.now() <= it.expiresAt && it.result == null } ?: return null
        val header = buildJsonObject { put("alg", "ES256"); put("typ", "oauth-authz-req+jwt"); put("kid", DidKeys.verificationMethod(identity.did)) }
        val payload = buildJsonObject {
            put("iss", identity.did)
            put("aud", identity.did)
            put("client_id", identity.did)
            put("response_type", "vp_token")
            put("response_mode", "direct_post")
            put("response_uri", "$publicUrl/api/oidvp/response")
            put("nonce", s.nonce)
            put("state", s.id)
            put("iat", s.created.epochSeconds)
            put("exp", s.expiresAt.epochSeconds)
            putJsonObject("presentation_definition") {
                put("id", s.definitionId)
                put("purpose", buildJsonObject {
                    put("client", name); put("terms_uri", "$publicUrl/verify/privacy"); put("scenario", s.ask.purpose); put("purpose", s.ask.purpose)
                }.toString())
                if (s.ask.kind == VerifierAsk.Kind.TWDIW) {
                    putJsonArray("submission_requirements") {
                        addJsonObject { put("name", s.ask.cardName ?: s.ask.cardType); put("rule", "pick"); put("max", 1); put("from", "Group_1") }
                    }
                }
                putJsonArray("input_descriptors") {
                    addJsonObject {
                        put("id", s.descriptorId)
                        if (s.ask.kind == VerifierAsk.Kind.MOICA) {
                            putJsonObject("format") { putJsonObject("vc+moica") { putJsonArray("alg") { add("RS256"); add("ES256") } } }
                        } else {
                            put("name", buildJsonObject { put("org_tw_name", s.ask.cardName ?: ""); put("vc_name", s.ask.cardType) }.toString())
                            putJsonArray("group") { add("Group_1") }
                        }
                        putJsonObject("constraints") {
                            putJsonArray("fields") {
                                addJsonObject {
                                    putJsonArray("path") { add("$.type") }
                                    putJsonObject("filter") { put("type", "array"); putJsonObject("contains") { put("const", s.ask.cardType) } }
                                }
                                s.ask.fields.forEach { f -> addJsonObject { putJsonArray("path") { add("$.credentialSubject.$f") } } }
                            }
                            put("limit_disclosure", "required")
                        }
                    }
                }
            }
            putJsonObject("client_metadata") {
                put("client_name", name)
                putJsonObject("vp_formats") {
                    putJsonObject("jwt_vc") { putJsonArray("alg") { add("ES256") } }
                    putJsonObject("jwt_vp") { putJsonArray("alg") { add("ES256") } }
                }
            }
        }
        return signJws(header, payload, AsymmetricKey.AnonymousExplicit(identity.key, Algorithm.ES256))
    }

    /** The wallet's direct_post. Returns the error to answer with, or null when the presentation was taken. */
    suspend fun respond(form: Map<String, String>): String? {
        val s = form["state"]?.let { sessions[it] }?.takeIf { Clock.System.now() <= it.expiresAt } ?: return "Unknown or expired request"
        var failure: String? = null
        val result = try {
            verify(s, form)
        } catch (e: Exception) {
            failure = (e as? VerifierException)?.message ?: "出示內容無法驗證"
            VerifierResult(false, failure, emptyList(), emptyList(), null, null, null, null)
        }
        synchronized(s) {
            if (s.result != null) return "This request was already answered"
            s.result = result
        }
        // A valid presentation is taken even when the verdict is negative (an unlisted issuer); only a broken one is refused.
        return failure
    }

    private class VerifierException(message: String) : Exception(message)

    private fun fail(message: String): Nothing = throw VerifierException(message)

    private suspend fun verify(s: Session, form: Map<String, String>): VerifierResult {
        val vp = runCatching { Jws.parse(form["vp_token"].orEmpty().trim()) }.getOrNull() ?: fail("缺少出示資料（vp_token）")
        if (vp.header.str("alg") != "ES256") fail("出示資料的演算法不支援")
        val holderKey = vp.header.obj("jwk")?.let { runCatching { EcPublicKey.fromJwk(it) }.getOrNull() } ?: fail("出示資料沒有持有者金鑰")
        runCatching { vp.verify(VerificationKey.Ec(holderKey)) }.getOrElse { fail("出示資料的簽章無效") }
        val holder = DidKeys.didKeyJwkJcs(holderKey)
        val p = vp.payload
        if (p.str("iss") != holder || (p.str("sub") != null && p.str("sub") != holder)) fail("出示者與持有者金鑰不符")
        if (p.str("aud") != identity.did) fail("出示對象不是本查驗端")
        if (p.str("nonce") != s.nonce) fail("出示資料不是回應這次請求")
        val now = Clock.System.now()
        (p["nbf"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { if (Instant.fromEpochSeconds(it) > now + SKEW) fail("出示資料的時間在未來") }
        (p["exp"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { if (Instant.fromEpochSeconds(it) < now) fail("出示資料已過期") }
        val submission = runCatching { Json.parseToJsonElement(form["presentation_submission"].orEmpty()) as? JsonObject }.getOrNull()
            ?: fail("缺少 presentation_submission")
        if (submission.str("definition_id") != s.definitionId) fail("出示資料回應的是另一個請求")
        val map = submission.arr("descriptor_map")?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("id") == s.descriptorId }
            ?: fail("出示資料沒有回應要求的卡片")
        val path = map.obj("path_nested")?.str("path") ?: fail("出示資料沒有指出卡片位置")
        val index = Regex("^\\$\\.vp\\.verifiableCredential\\[(\\d+)]$").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: fail("卡片位置格式不正確")
        val card = (p.obj("vp")?.arr("verifiableCredential")?.getOrNull(index) as? JsonPrimitive)?.content ?: fail("找不到出示的卡片")
        return if (s.ask.kind == VerifierAsk.Kind.MOICA) verifyMoica(s, card, holder) else verifyTwdiw(s, card, holder, holderKey, now)
    }

    private suspend fun verifyTwdiw(s: Session, card: String, holder: String, holderKey: EcPublicKey, now: Instant): VerifierResult {
        val parts = runCatching { SdJwtDisclosures.parse(card) }.getOrNull() ?: fail("卡片格式不正確")
        val jws = runCatching { Jws.parse(parts.issuerJwt) }.getOrNull() ?: fail("卡片格式不正確")
        val iss = jws.payload.str("iss")?.takeIf { it.startsWith("did:") } ?: fail("卡片沒有發卡者 DID")
        val issuerKey = runCatching { resolver.resolveKey(iss, jws.header.str("kid")).key }.getOrNull() ?: fail("無法取得發卡者的金鑰")
        runCatching { jws.verify(issuerKey) }.getOrElse { fail("發卡者簽章無效") }
        if (jws.payload.str("sub") != holder) fail("卡片不屬於出示者")
        jws.payload.obj("cnf")?.obj("jwk")?.let { cnf ->
            if (runCatching { EcPublicKey.fromJwk(cnf) }.getOrNull() != holderKey) fail("卡片綁定的金鑰不是出示者")
        }
        (jws.payload["exp"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { if (Instant.fromEpochSeconds(it) < now) fail("卡片已過期") }
        val resolved = runCatching { SdJwtDisclosures.resolve(jws.payload, parts.disclosures) }.getOrNull() ?: fail("揭露的欄位沒有發卡者承諾")
        val vc = resolved.obj("vc") ?: resolved
        if (s.ask.cardType !in vc.strList("type")) fail("出示的不是要求的卡片類型")
        val subject = vc.obj("credentialSubject") ?: JsonObject(emptyMap())
        // The official wallet may disclose more than asked (substring matching); only the asked fields are shown.
        val fields = s.ask.fields.mapNotNull { f -> subject[f]?.let { f to ((it as? JsonPrimitive)?.content ?: it.toString()) } }
        val notes = ArrayList<String>()
        val missing = s.ask.fields.filter { f -> fields.none { it.first == f } }
        if (missing.isNotEmpty()) notes += "沒有揭露：" + missing.joinToString("、")
        val extra = subject.keys.filter { it != "id" && !it.startsWith("_") && it !in s.ask.fields }
        if (extra.isNotEmpty()) notes += "皮夾多送了 ${extra.size} 個未要求的欄位，已捨棄不顯示"
        val trust = didTrust.decideOrDefault(iss, TrustRole.ISSUER, "Issuer $iss is in no trust list")
        val statusText = status?.let { checker ->
            withTimeoutOrNull(STATUS_TIMEOUT) { runCatching { checker.check(vc, iss, bypassCache = true) }.getOrNull() }
        }
        val revoked = statusText != null && statusText.state.name in setOf("INVALID", "REVOKED", "SUSPENDED")
        val statusLine = when {
            status == null -> null
            statusText == null -> "狀態清單未回應，未檢查撤銷狀態"
            !statusText.referenced -> "卡片沒有狀態清單"
            else -> (statusText.label ?: statusText.state.name) + (if (statusText.trusted) "" else "（狀態清單只經 TLS 確認）")
        }
        statusLine?.let { notes += "狀態：$it" }
        val trusted = trust.tier <= TrustTier.T2
        val summary = when {
            revoked -> "卡片已被撤銷或停用"
            !trusted -> "簽章有效，但發卡者不在數位皮夾的信任清單"
            else -> "通過：卡片由信任清單內的發卡者簽發，且屬於出示者"
        }
        return VerifierResult(trusted && !revoked, summary, notes, fields, iss, trust.anchorName, trust.tier.name, statusLine)
    }

    private suspend fun verifyMoica(s: Session, card: String, holder: String): VerifierResult {
        val moica = runCatching { MoicaSignedCredential.parse(card) }.getOrNull() ?: fail("自發卡格式不正確")
        val result = runCatching { moica.verify(resolver) }.getOrElse { fail("自然人憑證簽章無法驗證") }
        if (result.credential.obj("credentialSubject")?.str("id") != holder) fail("自發卡不屬於出示者")
        val fields = s.ask.fields.mapNotNull { f -> result.claims[f]?.let { f to it } }
        val notes = buildList {
            add("持卡人以自然人憑證（MOICA）自行簽署，內容是持卡人的聲明，不是內政部背書")
            if (result.nameChecked) add("姓名與自然人憑證相符")
            val missing = s.ask.fields.filter { f -> fields.none { it.first == f } }
            if (missing.isNotEmpty()) add("沒有揭露：" + missing.joinToString("、"))
        }
        return VerifierResult(true, "通過：自然人憑證簽署的自發卡，屬於出示者", notes, fields, result.credential.str("issuer"), result.cardholderName, null, null)
    }

    private fun token(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    companion object {
        val LIFETIME = 5.minutes
        val RESULT_KEPT = 5.minutes
        val SKEW = 2.minutes
        val STATUS_TIMEOUT = 10.seconds
        const val MOICA_DEFINITION = "bonds-moica"
    }
}
