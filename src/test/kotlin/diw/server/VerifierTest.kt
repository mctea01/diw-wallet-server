package diw.server

import diw.wallet.core.did.DidKeys
import diw.wallet.core.jose.Jws
import diw.wallet.core.jose.signJws
import diw.wallet.core.trust.DidTrust
import diw.wallet.core.trust.DidTrustSource
import diw.wallet.core.trust.TrustDecision
import diw.wallet.core.trust.TrustRole
import diw.wallet.core.trust.TrustTier
import diw.wallet.core.util.obj
import diw.wallet.core.util.str
import diw.wallet.core.w3c.SdJwtDisclosures
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.util.toBase64Url
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/** The web verifier: the request object the government wallet reads, and checking what it posts back. */
class VerifierTest {
    private val card = "00000000_test_card"

    private class Card(val issuer: EcPrivateKey, val issuerDid: String, val holder: EcPrivateKey, val holderDid: String, val compact: String)

    private suspend fun card(type: String = card, claims: Map<String, String> = mapOf("name" to "王小明", "birthdate" to "0830306", "id_number" to "A123456789")): Card {
        val issuer = Crypto.createEcPrivateKey(EcCurve.P256)
        val holder = Crypto.createEcPrivateKey(EcCurve.P256)
        val issuerDid = DidKeys.didKeyJwkJcs(issuer.publicKey)
        val holderDid = DidKeys.didKeyJwkJcs(holder.publicKey)
        val disclosures = claims.map { (k, v) ->
            buildJsonArray { add("salt-$k"); add(k); add(v) }.toString().encodeToByteArray().toBase64Url()
        }
        val holderJwk = holder.publicKey.toJwk()
        val jwt = signJws(
            buildJsonObject { put("alg", "ES256"); put("typ", "vc+sd-jwt"); put("kid", DidKeys.verificationMethod(issuerDid)) },
            buildJsonObject {
                put("iss", issuerDid); put("sub", holderDid)
                put("exp", Clock.System.now().epochSeconds + 86_400)
                putJsonObject("cnf") { put("jwk", holderJwk) }
                putJsonObject("vc") {
                    putJsonArray("type") { add("VerifiableCredential"); add(type) }
                    putJsonObject("credentialSubject") { putJsonArray("_sd") { disclosures.forEach { add(SdJwtDisclosures.digest(it)) } } }
                }
            },
            AsymmetricKey.AnonymousExplicit(issuer, Algorithm.ES256),
        )
        return Card(issuer, issuerDid, holder, holderDid, (listOf(jwt) + disclosures).joinToString("~") + "~")
    }

    private fun trustOnly(did: () -> String) = DidTrust(listOf(object : DidTrustSource {
        override suspend fun decide(d: String, role: TrustRole): TrustDecision? =
            if (d == did()) TrustDecision(TrustTier.T1, null, true, "測試發卡機關", emptySet()) else null
    }))

    private fun server(trust: DidTrust, token: String? = null) = Server(
        ServerConfig(
            publicUrl = "https://diw.example.org",
            features = setOf(ServerConfig.FEATURE_VERIFIER),
            dataDir = Files.createTempDirectory("diw-server-test").toFile(),
            verifierAccessToken = token,
        ),
        didTrust = trust, checkStatus = false,
    )

    private suspend fun HttpClient.create(body: String, token: String? = null) = post("/api/oidvp/sessions") {
        contentType(ContentType.Application.Json); token?.let { header("Authorization", "Bearer $it") }; setBody(body)
    }

    private suspend fun present(c: Card, request: JsonObject, aud: String, nonce: String = request.str("nonce")!!): List<Pair<String, String>> {
        val now = Clock.System.now().epochSeconds
        val holderJwk = c.holder.publicKey.toJwk()
        val vp = signJws(
            buildJsonObject { put("typ", "JWT"); put("alg", "ES256"); put("jwk", holderJwk) },
            buildJsonObject {
                put("sub", c.holderDid); put("iss", c.holderDid); put("aud", aud); put("nbf", now); put("exp", now + 30 * 86_400); put("nonce", nonce)
                putJsonObject("vp") {
                    putJsonArray("context") { add("https://www.w3.org/2018/credentials/v1") }
                    putJsonArray("type") { add("VerifiablePresentation") }
                    putJsonArray("verifiableCredential") { add(c.compact) }
                }
            },
            AsymmetricKey.AnonymousExplicit(c.holder, Algorithm.ES256),
        )
        val definition = request.obj("presentation_definition")!!
        val descriptor = definition["input_descriptors"]!!.jsonArray.first().jsonObject.str("id")!!
        val submission = buildJsonObject {
            put("id", "submission"); put("definition_id", definition.str("id"))
            putJsonArray("descriptor_map") {
                addJsonObject {
                    put("id", descriptor); put("format", "jwt_vp"); put("path", "$")
                    putJsonObject("path_nested") { put("id", descriptor); put("format", "jwt_vc"); put("path", "$.vp.verifiableCredential[0]") }
                }
            }
        }
        return listOf("state" to request.str("state")!!, "vp_token" to vp, "presentation_submission" to submission.toString())
    }

    @Test
    fun aListedIssuersCardPassesAndOnlyAskedFieldsAreShown() = testApplication {
        var issuerDid = ""
        val server = server(trustOnly { issuerDid })
        application { module(server) }
        val info = Json.parseToJsonElement(client.get("/info").bodyAsText()).jsonObject
        val did = info["verifierDid"]!!.jsonPrimitive.content
        assertTrue(did.startsWith("did:key:z"))
        assertTrue("網頁查驗" in client.get("/verify").bodyAsText())

        val s = Json.parseToJsonElement(client.create("""{"kind":"twdiw","cardType":"$card","fields":["name","birthdate"],"purpose":"入場"}""").bodyAsText()).jsonObject
        val link = s["deepLink"]!!.jsonPrimitive.content
        assertTrue(link.startsWith("modadigitalwallet://authorize?client_id="))
        val requestUri = java.net.URLDecoder.decode(link.substringAfter("request_uri="), Charsets.UTF_8)
        val jws = Jws.parse(client.get(Url(requestUri).encodedPath).bodyAsText())
        assertEquals("oauth-authz-req+jwt", jws.header.str("typ"))
        jws.verify(diw.wallet.core.did.DidResolver(HttpClient()).resolveKey(did, null).key)
        val request = jws.payload
        assertEquals(did, request.str("client_id"))
        assertEquals("direct_post", request.str("response_mode"))
        assertTrue(card in request.toString())
        assertTrue(client.get("/api/oidvp/sessions/${s["sessionId"]!!.jsonPrimitive.content}/qr.svg?token=${s["token"]!!.jsonPrimitive.content}").bodyAsText().startsWith("<svg"))

        val c = card()
        issuerDid = c.issuerDid
        val r = client.post(Url(request.str("response_uri")!!).encodedPath) {
            contentType(ContentType.Application.FormUrlEncoded); setBody(present(c, request, did).formUrlEncode())
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("0", Json.parseToJsonElement(r.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
        val state = Json.parseToJsonElement(
            client.get("/api/oidvp/sessions/${s["sessionId"]!!.jsonPrimitive.content}?token=${s["token"]!!.jsonPrimitive.content}").bodyAsText(),
        ).jsonObject
        assertEquals("done", state["state"]!!.jsonPrimitive.content)
        val result = state["result"]!!.jsonObject
        assertTrue(result["accepted"]!!.jsonPrimitive.content.toBoolean(), result.toString())
        assertEquals(listOf("name", "birthdate"), result["fields"]!!.jsonArray.map { it.jsonObject.str("name") })
        assertFalse("A123456789" in result.toString(), "a field nobody asked for is dropped")
        assertEquals(HttpStatusCode.NotFound, client.get(Url(requestUri).encodedPath).status, "an answered request is not served again")
    }

    @Test
    fun unlistedIssuersAndForgedPresentationsFail() = testApplication {
        application { module(server(trustOnly { "nobody" })) }
        val did = Json.parseToJsonElement(client.get("/info").bodyAsText()).jsonObject["verifierDid"]!!.jsonPrimitive.content
        suspend fun session(): Pair<JsonObject, JsonObject> {
            val s = Json.parseToJsonElement(client.create("""{"cardType":"$card","fields":["name"]}""").bodyAsText()).jsonObject
            val uri = java.net.URLDecoder.decode(s["deepLink"]!!.jsonPrimitive.content.substringAfter("request_uri="), Charsets.UTF_8)
            return s to Jws.parse(client.get(Url(uri).encodedPath).bodyAsText()).payload
        }
        suspend fun result(s: JsonObject) = Json.parseToJsonElement(
            client.get("/api/oidvp/sessions/${s["sessionId"]!!.jsonPrimitive.content}?token=${s["token"]!!.jsonPrimitive.content}").bodyAsText(),
        ).jsonObject["result"]!!.jsonObject

        val (s1, r1) = session()
        client.post("/api/oidvp/response") { contentType(ContentType.Application.FormUrlEncoded); setBody(present(card(), r1, did).formUrlEncode()) }
        val unlisted = result(s1)
        assertFalse(unlisted["accepted"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("簽章有效，但發卡者不在數位皮夾的信任清單", unlisted.str("summary"))

        val (s2, r2) = session()
        val wrongAud = client.post("/api/oidvp/response") { contentType(ContentType.Application.FormUrlEncoded); setBody(present(card(), r2, "did:key:other").formUrlEncode()) }
        assertEquals(HttpStatusCode.BadRequest, wrongAud.status)
        assertEquals("出示對象不是本查驗端", result(s2).str("summary"))

        val (s3, r3) = session()
        val other = client.post("/api/oidvp/response") { contentType(ContentType.Application.FormUrlEncoded); setBody(present(card("99999999_other"), r3, did).formUrlEncode()) }
        assertEquals(HttpStatusCode.BadRequest, other.status)
        assertEquals("出示的不是要求的卡片類型", result(s3).str("summary"))

        assertEquals(HttpStatusCode.BadRequest, client.create("""{"cardType":"$card","fields":["bad field"]}""").status)
    }

    @Test
    fun anAccessCodeGuardsTheVerifierAndTheKeyIsKept() = testApplication {
        val dir = Files.createTempDirectory("diw-verifier").toFile()
        val a = TwdiwVerifier.Identity.load(dir)
        assertEquals(a.did, TwdiwVerifier.Identity.load(dir).did)
        assertEquals("rw-------", java.nio.file.Files.getPosixFilePermissions(File(dir, "verifier-key.pem").toPath()).let { java.nio.file.attribute.PosixFilePermissions.toString(it) })
        application { module(server(trustOnly { "x" }, token = "secret-code")) }
        assertEquals(HttpStatusCode.Unauthorized, client.create("""{"cardType":"$card","fields":["name"]}""").status)
        assertEquals(HttpStatusCode.Unauthorized, client.create("""{"cardType":"$card","fields":["name"]}""", "wrong-code").status)
        assertEquals(HttpStatusCode.OK, client.create("""{"cardType":"$card","fields":["name"]}""", "secret-code").status)
        assertTrue("存取碼" in client.get("/verify").bodyAsText())
    }
}
