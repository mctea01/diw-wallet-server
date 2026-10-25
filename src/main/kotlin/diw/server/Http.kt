package diw.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal suspend fun ApplicationCall.respondJson(body: JsonObject, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(body.toString(), ContentType.Application.Json, status)

internal suspend fun ApplicationCall.respondError(status: HttpStatusCode, error: String, description: String) =
    respondJson(buildJsonObject { put("error", error); put("error_description", description) }, status)

/** The body as text, or null when it is longer than [max] bytes. */
internal suspend fun ApplicationCall.receiveLimited(max: Int): String? {
    val bytes = receiveChannel().readRemaining(max.toLong() + 1).readByteArray()
    return if (bytes.size > max) null else bytes.decodeToString()
}
