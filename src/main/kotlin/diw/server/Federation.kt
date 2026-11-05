package diw.server

import diw.wallet.core.jose.jwkThumbprint
import diw.wallet.core.jose.publicJwk
import diw.wallet.core.jose.signJws
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** One entity this server vouches for as a superior (intermediate or Trust Anchor), from federation_subordinates_file. */
data class Subordinate(
    val entityId: String,
    val jwks: JsonObject,
    val metadataPolicy: JsonObject?,
    val metadata: JsonObject?,
    val constraints: JsonObject?,
    /** Entity types the subordinate serves, for the list endpoint's entity_type filter; empty means unknown. */
    val entityTypes: List<String>,
)

/**
 * This server as an OpenID Federation 1.0 entity: a self-signed Entity Configuration at
 * `/.well-known/openid-federation` naming its superiors (authority_hints), its trust marks and its metadata,
 * including the `diw_wallet_server` type that binds the zkTLS signing root and the web verifier DID to the
 * federation, so a wallet that trusts the federation's Trust Anchor trusts this server without comparing the
 * fingerprint by hand. With a subordinates file it is also a superior: `/federation/fetch` signs Subordinate
 * Statements and `/federation/list` lists them, so one operator's server can anchor a federation of others.
 */
class Federation(
    private val config: ServerConfig,
    val key: EcPrivateKey,
    /** [key]'s public JWK with its thumbprint as kid ([jwk]). */
    val publicJwk: JsonObject,
    /** The metadata of this server's own entity types, beside federation_entity. */
    private val serverMetadata: () -> JsonObject,
    private val clock: Clock = Clock.System,
) {
    val entityId: String = config.federationEntityId
    val kid: String = publicJwk["kid"]!!.jsonPrimitive.content
    val jwks: JsonObject = buildJsonObject { put("keys", JsonArray(listOf(publicJwk))) }
    private val lifetime = config.federation.lifetimeHours.hours
    private val fetchEndpoint = config.publicUrl + FETCH_PATH
    private val listEndpoint = config.publicUrl + LIST_PATH

    private val subordinatesFile = config.federation.subordinatesFile
    @Volatile private var loaded: Pair<Long, List<Subordinate>>? = null

    /** The subordinates, re-read when the file changes so an operator can enrol one without a restart. */
    fun subordinates(): List<Subordinate> {
        val file = subordinatesFile ?: return emptyList()
        val stamp = file.lastModified()
        loaded?.takeIf { it.first == stamp }?.let { return it.second }
        val list = parseSubordinates(file.readText(Charsets.UTF_8))
        loaded = stamp to list
        return list
    }

    val isSuperior: Boolean get() = subordinatesFile != null

    /** The Entity Configuration (section 3.1 and 9). */
    suspend fun configuration(): String {
        val now = clock.now().epochSeconds
        val claims = buildJsonObject {
            put("iss", entityId)
            put("sub", entityId)
            put("iat", now)
            put("exp", now + lifetime.inWholeSeconds)
            put("jwks", jwks)
            if (config.federation.authorityHints.isNotEmpty()) putJsonArray("authority_hints") { config.federation.authorityHints.forEach { add(JsonPrimitive(it)) } }
            put("metadata", buildJsonObject {
                put("federation_entity", buildJsonObject {
                    put("organization_name", config.federation.organizationName ?: config.verifierName)
                    put("homepage_uri", config.publicUrl)
                    if (isSuperior) {
                        put("federation_fetch_endpoint", fetchEndpoint)
                        put("federation_list_endpoint", listEndpoint)
                    }
                })
                serverMetadata().forEach { (type, value) -> put(type, value) }
            })
            if (config.federation.trustMarks.isNotEmpty()) {
                putJsonArray("trust_marks") {
                    config.federation.trustMarks.forEach { jwt ->
                        add(buildJsonObject {
                            put("trust_mark_type", trustMarkType(jwt) ?: "unknown")
                            put("trust_mark", jwt)
                        })
                    }
                }
            }
        }
        return sign(claims)
    }

    /** A Subordinate Statement about [sub] (section 3.1, 8.1.1), or null when [sub] is not enrolled. */
    suspend fun statementAbout(sub: String): String? {
        val s = subordinates().firstOrNull { it.entityId == sub } ?: return null
        val now = clock.now().epochSeconds
        val claims = buildJsonObject {
            put("iss", entityId)
            put("sub", s.entityId)
            put("iat", now)
            put("exp", now + lifetime.inWholeSeconds)
            put("jwks", s.jwks)
            s.metadataPolicy?.let { put("metadata_policy", it) }
            s.metadata?.let { put("metadata", it) }
            s.constraints?.let { put("constraints", it) }
            put("source_endpoint", fetchEndpoint)
        }
        return sign(claims)
    }

    private suspend fun sign(claims: JsonObject): String {
        val header = buildJsonObject {
            put("alg", "ES256")
            put("typ", STATEMENT_TYPE)
            put("kid", kid)
        }
        return signJws(header, claims, AsymmetricKey.anonymous(key))
    }

    companion object {
        const val CONFIGURATION_PATH = "/.well-known/openid-federation"
        const val FETCH_PATH = "/federation/fetch"
        const val LIST_PATH = "/federation/list"
        const val STATEMENT_TYPE = "entity-statement+jwt"
        /** The entity type naming this server's signing root, web verifier DID and features. */
        const val SERVER_TYPE = "diw_wallet_server"

        suspend fun jwk(key: EcPublicKey): JsonObject {
            val base = publicJwk(key.toJwk())
            return JsonObject(base + mapOf("kid" to JsonPrimitive(jwkThumbprint(base)), "use" to JsonPrimitive("sig"), "alg" to JsonPrimitive("ES256")))
        }

        private val PRIVATE = Regex("-----BEGIN (EC )?PRIVATE KEY-----[\\s\\S]+?-----END (EC )?PRIVATE KEY-----")
        private val PUBLIC = Regex("-----BEGIN PUBLIC KEY-----[\\s\\S]+?-----END PUBLIC KEY-----")

        /** The private key PEM followed by the public key PEM, the form [parseKey] reads. */
        fun pem(key: EcPrivateKey): String = key.toPem() + "\n" + key.publicKey.toPem() + "\n"

        fun parseKey(text: String): EcPrivateKey {
            val public = PUBLIC.find(text)?.value?.let { EcPublicKey.fromPem(it) } ?: throw IllegalArgumentException("No public key in the federation key")
            return PRIVATE.find(text)?.value?.let { EcPrivateKey.fromPem(it, public) } ?: throw IllegalArgumentException("No private key in the federation key")
        }

        /** The federation key from the settings, the file, or (with signing_generate) a new one written to the file. */
        suspend fun loadKey(config: ServerConfig): EcPrivateKey {
            config.federation.keyPem?.let { return parseKey(it) }
            val file = config.federation.keyFile ?: File(config.dataDir, "federation.pem")
            if (file.isFile) return parseKey(file.readText())
            require(config.signingGenerate) { "Federation key not found: ${file.path} (run diw-server keygen ${file.path} ${config.publicUrl} federation)" }
            return Crypto.createEcPrivateKey(EcCurve.P256).also { writePrivateFile(file, pem(it)) }
        }

        suspend fun createKey(): EcPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)

        private fun trustMarkType(jwt: String): String? = runCatching {
            val payload = java.util.Base64.getUrlDecoder().decode(jwt.split('.')[1].trimEnd('='))
            val o = Json.parseToJsonElement(payload.decodeToString()) as JsonObject
            (o["trust_mark_type"] ?: o["id"])?.jsonPrimitive?.content
        }.getOrNull()

        /**
         * `{"subordinates": [{"entity_id", "jwks", "metadata_policy"?, "metadata"?, "constraints"?, "entity_types"?}],
         * "metadata_policy"?, "constraints"?}`: top-level policy and constraints apply to entries without their own.
         */
        fun parseSubordinates(text: String): List<Subordinate> {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("The subordinates file is not a JSON object")
            val defaultPolicy = root["metadata_policy"] as? JsonObject
            val defaultConstraints = root["constraints"] as? JsonObject
            val entries = root["subordinates"] as? JsonArray ?: throw IllegalArgumentException("The subordinates file needs a subordinates array")
            return entries.map { e ->
                val o = e as? JsonObject ?: throw IllegalArgumentException("A subordinate is not an object")
                val id = (o["entity_id"] as? JsonPrimitive)?.content ?: throw IllegalArgumentException("A subordinate has no entity_id")
                val jwks = o["jwks"] as? JsonObject ?: throw IllegalArgumentException("Subordinate $id has no jwks")
                val keys = jwks["keys"] as? JsonArray
                require(keys != null && keys.isNotEmpty()) { "Subordinate $id has no keys" }
                // Only public members are ever published.
                val publicKeys = buildJsonObject { put("keys", JsonArray(keys.map { publicJwk(it as JsonObject) })) }
                Subordinate(
                    entityId = id.trimEnd('/'),
                    jwks = publicKeys,
                    metadataPolicy = o["metadata_policy"] as? JsonObject ?: defaultPolicy,
                    metadata = o["metadata"] as? JsonObject,
                    constraints = o["constraints"] as? JsonObject ?: defaultConstraints,
                    entityTypes = (o["entity_types"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                )
            }
        }

        /** A subordinates file entry for an entity whose configuration the operator has checked (federation-enroll). */
        fun enrolment(entityId: String, keys: List<JsonObject>, types: List<String>): JsonObject = buildJsonObject {
            put("entity_id", entityId)
            put("jwks", buildJsonObject { put("keys", JsonArray(keys.map { publicJwk(it) })) })
            put("entity_types", buildJsonArray { types.forEach { add(JsonPrimitive(it)) } })
        }
    }
}

fun Route.federationRoutes(federation: Federation) {
    val statementType = ContentType("application", Federation.STATEMENT_TYPE)
    get(Federation.CONFIGURATION_PATH) {
        call.respondText(federation.configuration(), statementType)
    }
    get(Federation.FETCH_PATH) {
        if (!federation.isSuperior) return@get call.respondError(HttpStatusCode.NotFound, "not_found", "This entity has no subordinates")
        val sub = call.request.queryParameters["sub"]?.trimEnd('/')
            ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_request", "sub is required")
        if (sub == federation.entityId) return@get call.respondError(HttpStatusCode.BadRequest, "invalid_request", "sub is this entity")
        val statement = federation.statementAbout(sub)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "not_found", "Unknown subordinate")
        call.respondText(statement, statementType)
    }
    get(Federation.LIST_PATH) {
        if (!federation.isSuperior) return@get call.respondError(HttpStatusCode.NotFound, "not_found", "This entity has no subordinates")
        val type = call.request.queryParameters["entity_type"]
        val ids = federation.subordinates().filter { type == null || it.entityTypes.isEmpty() || type in it.entityTypes }.map { it.entityId }
        call.respondText(JsonArray(ids.map { JsonPrimitive(it) as JsonElement }).toString(), ContentType.Application.Json)
    }
}
