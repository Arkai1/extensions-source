package eu.kanade.tachiyomi.animeextension.en.kaido

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request

class Kaido : ZoroTheme(
    "en",
    "Kaido",
    "https://kaido.to",
    hosterNames = listOf(
        "Vidstreaming",
        "Vidcloud",
        "StreamTape",
    ),
) {
    override val id = 5569316556494057205L

    override val ajaxRoute = "/v2"

    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val megaCloudExtractor by lazy { MegaCloudExtractor(client, headers, preferences) }

    override fun extractVideo(server: VideoData): List<Video> {
        return when (server.name) {
            "StreamTape" -> {
                streamtapeExtractor.videoFromUrl(server.link, "Streamtape - ${server.type}")
                    ?.let(::listOf)
                    ?: emptyList()
            }
            "Vidstreaming", "Vidcloud" -> megaCloudExtractor.getVideosFromUrl(server.link, server.type, server.name)
            else -> emptyList()
        }
    }

    // Debugging: Log the JSON response to troubleshoot the missing 'html' issue
    private fun logApiResponse(request: Request): String {
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
        println("Response Body: $responseBody")
        return responseBody ?: throw Exception("Response body is null")
    }
}

@Serializable
data class HtmlResponse(
    val html: String? = null // Allow nullable to handle missing 'html' gracefully
)