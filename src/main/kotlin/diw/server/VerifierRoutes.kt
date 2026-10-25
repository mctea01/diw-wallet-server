package diw.server

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseQueryString
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import kotlin.time.Clock

/** A QR code of [text] as SVG, one path of dark modules with a two-module quiet zone. */
internal fun qrSvg(text: String): String {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"))
    val d = StringBuilder()
    for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) d.append("M$x ${y}h1v1h-1z")
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${m.width} ${m.height}\" shape-rendering=\"crispEdges\">" +
        "<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path fill=\"#000\" d=\"$d\"/></svg>"
}

fun Route.verifierRoutes(verifier: TwdiwVerifier, accessToken: String?) {
    val tokenHash = accessToken?.let { MessageDigest.getInstance("SHA-256").digest(it.encodeToByteArray()) }
    fun headersOk(header: String?): Boolean {
        if (tokenHash == null) return true
        val given = header?.removePrefix("Bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(tokenHash, MessageDigest.getInstance("SHA-256").digest(given.encodeToByteArray()))
    }
    val csp = "default-src 'self'; img-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; frame-ancestors 'none'; base-uri 'none'"

    get("/verify") {
        call.response.header("Content-Security-Policy", csp)
        call.response.header("Referrer-Policy", "no-referrer")
        call.respondText(VerifierPage.html(tokenHash != null), ContentType.Text.Html.withParameter("charset", "utf-8"))
    }
    get("/verify/app.js") { call.respondText(VerifierPage.SCRIPT, ContentType.Text.JavaScript.withParameter("charset", "utf-8")) }
    get("/verify/privacy") {
        call.response.header("Content-Security-Policy", csp)
        call.respondText(VerifierPage.PRIVACY, ContentType.Text.Html.withParameter("charset", "utf-8"))
    }
    post("/api/oidvp/sessions") {
        if (!headersOk(call.request.headers["Authorization"])) {
            return@post call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "Access code required")
        }
        val body = call.receiveLimited(16 shl 10)?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "JSON body required")
        val ask = VerifierAsk.parse(body).getOrElse {
            return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", it.message ?: "Malformed request")
        }
        when (val created = verifier.create(ask, call.request.origin.remoteAddress)) {
            is TwdiwVerifier.Created.Refused -> call.respondError(HttpStatusCode.TooManyRequests, "busy", created.message)
            is TwdiwVerifier.Created.Ok -> {
                val s = created.session
                call.respondJson(buildJsonObject {
                    put("sessionId", s.id)
                    put("token", s.token)
                    put("clientId", verifier.did)
                    put("deepLink", verifier.deepLink(s, "modadigitalwallet"))
                    put("openid4vpLink", verifier.deepLink(s, "openid4vp"))
                    put("expiresAt", s.expiresAt.toEpochMilliseconds())
                })
            }
        }
    }
    get("/api/oidvp/request/{id}") {
        val jws = verifier.requestObject(call.parameters["id"].orEmpty())
            ?: return@get call.respondText("Unknown or expired request", status = HttpStatusCode.NotFound)
        call.respondText(jws, ContentType("application", "oauth-authz-req+jwt"))
    }
    post("/api/oidvp/response") {
        val text = call.receiveLimited(512 shl 10)
            ?: return@post call.respondJson(buildJsonObject { put("code", "413"); put("message", "Presentation too large") }, HttpStatusCode.PayloadTooLarge)
        val form = parseQueryString(text).let { p -> p.names().associateWith { p[it].orEmpty() } }
        when (val error = verifier.respond(form)) {
            null -> call.respondJson(buildJsonObject { put("code", "0"); put("message", "SUCCESS") })
            else -> call.respondJson(buildJsonObject { put("code", "400"); put("message", error) }, HttpStatusCode.BadRequest)
        }
    }
    get("/api/oidvp/sessions/{id}") {
        val s = verifier.session(call.parameters["id"].orEmpty(), call.request.queryParameters["token"])
            ?: return@get call.respondError(HttpStatusCode.NotFound, "not_found", "Unknown or expired session")
        val result = s.result
        call.respondJson(buildJsonObject {
            put("state", when {
                result != null -> "done"
                Clock.System.now() > s.expiresAt -> "expired"
                else -> "waiting"
            })
            result?.let { put("result", it.json()) }
        })
    }
    get("/api/oidvp/sessions/{id}/qr.svg") {
        val s = verifier.session(call.parameters["id"].orEmpty(), call.request.queryParameters["token"])
            ?: return@get call.respondText("not found", status = HttpStatusCode.NotFound)
        val scheme = if (call.request.queryParameters["scheme"] == "openid4vp") "openid4vp" else "modadigitalwallet"
        call.response.header("Cache-Control", "no-store")
        call.respondText(qrSvg(verifier.deepLink(s, scheme)), ContentType.Image.SVG)
    }
}
