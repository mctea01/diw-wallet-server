package diw.server

import diw.wallet.core.federation.EntityStatement
import diw.wallet.core.federation.FederationAnchor
import diw.wallet.core.federation.FederationResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** This server in an OpenID Federation, checked with the wallet's own resolver: a leaf under a Trust Anchor that is another server. */
class FederationTest {
    private val tokenHash = SigningIdentity.sha256Hex("test-token-0123456789".encodeToByteArray())

    private fun port() = ServerSocket(0).use { it.localPort }

    private fun dir(): File = Files.createTempDirectory("diw-federation").toFile()

    @Test
    fun aWalletResolvesTheServerThroughATrustAnchorServer() = runBlocking<Unit> {
        val anchorUrl = "http://127.0.0.1:${port()}"
        val leafUrl = "http://localhost:${port()}"
        val leaf = Server(ServerConfig.from(mapOf(
            "public_url" to leafUrl, "features" to "relay,zktls,verifier,federation", "zktls_token_sha256" to tokenHash,
            "data_dir" to dir().path, "federation_authority_hints" to anchorUrl, "federation_organization_name" to "Leaf operator",
        )), zkTlsAvailable = true)
        val leafFederation = leaf.federation!!
        val subordinates = File(dir(), "subordinates.json")
        subordinates.writeText(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
            put("subordinates", JsonArray(listOf(Federation.enrolment(leafUrl, listOf(leafFederation.publicJwk), listOf(Federation.SERVER_TYPE)))))
        }))
        val anchor = Server(ServerConfig.from(mapOf(
            "public_url" to anchorUrl, "features" to "relay,federation", "data_dir" to dir().path,
            "federation_subordinates_file" to subordinates.path,
        )))
        val engines = listOf(leaf to leafUrl, anchor to anchorUrl).map { (s, url) ->
            embeddedServer(CIO, port = io.ktor.http.Url(url).port, host = "127.0.0.1") { module(s) }.start(wait = false)
        }
        val http = HttpClient(Java)
        try {
            val trusted = FederationAnchor(anchorUrl, anchor.federation!!.jwks.toString(), "Test federation", "user", addedAt = 0)
            val resolver = FederationResolver(http, { listOf(trusted) }, allowInsecureLoopback = true)
            val resolved = resolver.resolve(leafUrl, Federation.SERVER_TYPE)
            assertEquals(listOf(leafUrl, anchorUrl), resolved.path)
            assertEquals(leaf.signing!!.rootSha256, resolved.metadata["signing_root_sha256"]!!.jsonPrimitive.content)
            assertEquals(leaf.verifier!!.did, resolved.metadata["verifier_did"]!!.jsonPrimitive.content)
            assertEquals(leaf.config.settingsDigest(), resolved.metadata["settings_sha256"]!!.jsonPrimitive.content)
            assertEquals("Leaf operator", resolved.displayName)

            // The superior lists and fetches only its enrolled subordinates.
            assertEquals(listOf(leafUrl), Json.parseToJsonElement(http.get("$anchorUrl/federation/list").bodyAsText()).jsonArray.map { it.jsonPrimitive.content })
            assertEquals(HttpStatusCode.NotFound, http.get("$anchorUrl/federation/fetch?sub=https://other.example").status)
            assertEquals(HttpStatusCode.NotFound, http.get("$leafUrl/federation/fetch?sub=$anchorUrl").status)
            val statement = EntityStatement.parse(http.get("$anchorUrl/federation/fetch?sub=$leafUrl").bodyAsText())
            assertEquals(anchorUrl, statement.iss)
            assertEquals(leafUrl, statement.sub)

            // /info names the federation entity.
            val info = Json.parseToJsonElement(http.get("$leafUrl/info").bodyAsText()).jsonObject
            assertEquals(leafUrl, info["federation"]!!.jsonObject["entityId"]!!.jsonPrimitive.content)

            // A wallet that trusts another anchor does not resolve the server.
            val stranger = FederationAnchor("https://elsewhere.example", leafFederation.jwks.toString(), "Other", "user", addedAt = 0)
            assertFailsWith<Exception> { FederationResolver(http, { listOf(stranger) }, allowInsecureLoopback = true).resolve(leafUrl, Federation.SERVER_TYPE) }

            // Removing the leaf from the subordinates file breaks the chain without a restart.
            subordinates.writeText("""{"subordinates":[]}""")
            subordinates.setLastModified(System.currentTimeMillis() + 2000)
            assertFailsWith<Exception> { resolver.resolve(leafUrl, Federation.SERVER_TYPE) }
        } finally {
            http.close()
            engines.forEach { it.stop(100, 500) }
        }
    }

    @Test
    fun theKeyIsKeptAndCanComeFromASecret() = runBlocking<Unit> {
        val data = dir()
        val config = ServerConfig.from(mapOf("public_url" to "https://diw.example.org", "features" to "federation", "data_dir" to data.path))
        val first = Federation.loadKey(config)
        assertEquals(Federation.jwk(first.publicKey), Federation.jwk(Federation.loadKey(config).publicKey))
        val pem = File(data, "federation.pem").readText()
        val fromSecret = ServerConfig.from(mapOf("public_url" to "https://diw.example.org", "features" to "federation", "data_dir" to dir().path,
            "federation_key_pem" to pem.replace("\n", "\\n"), "signing_generate" to "false"))
        assertEquals(Federation.jwk(first.publicKey), Federation.jwk(Federation.loadKey(fromSecret).publicKey))
        val missing = ServerConfig.from(mapOf("public_url" to "https://diw.example.org", "features" to "federation", "data_dir" to dir().path, "signing_generate" to "false"))
        assertFailsWith<IllegalArgumentException> { Federation.loadKey(missing) }
        assertTrue(Federation.parseSubordinates("""{"subordinates":[]}""").isEmpty())
        assertFailsWith<IllegalArgumentException> { Federation.parseSubordinates("""{"subordinates":[{"entity_id":"https://x"}]}""") }
    }
}
