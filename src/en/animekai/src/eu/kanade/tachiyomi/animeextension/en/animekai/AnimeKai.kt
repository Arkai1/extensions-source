package eu.kanade.tachiyomi.animeextension.en.animekai

import android.util.Base64
import androidx.preference.ListPreference
import eu.kanade.tachiyomi.animesource.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.model.*
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class AnimeKai : AnimeHttpSource() {

    private val PREF_SERVER_KEY = "preferred_server"
    private val PREF_SUBTYPE_KEY = "preferred_subtype"
    private val PREF_DOMAIN_KEY = "preferred_domain"

    private val defaultServer = "HD-1"
    private val defaultSubtype = "sub"
    private val defaultDomain = "https://animekai.to"

    override val name = "AnimeKai"
    override val lang = "en"
    override val supportsLatest = true
    override val client: OkHttpClient = network.cloudflareClient

    override val baseUrl by lazy {
        preferences.getString(PREF_DOMAIN_KEY, defaultDomain)!!
    }

    private val decoder = AnimekaiDecoder()

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/browser?sort=trending&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("div.aitem").map {
            SAnime.create().apply {
                setUrlWithoutDomain(it.select("a.poster").attr("href"))
                title = it.select("a.title").text()
                thumbnail_url = it.select("a.poster img").attr("data-src")
            }
        }
        return AnimesPage(animeList, true)
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/browser?sort=updated_date&status[]=releasing&page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/browser?keyword=$query&page=$page", headers)

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("div.title")?.text().orEmpty()
            thumbnail_url = document.selectFirst("div.watch-section-bg")?.attr("style")
                ?.substringAfter("url(")?.substringBefore(")")?.replace("\"", "")
            genre = document.select("div.detail a[href*=genres]").joinToString { it.text() }
            status = parseStatus(document.select("div.detail div:contains(Status) span").text())
            description = "No description available"
        }
    }

    private fun parseStatus(text: String): Int = when {
        text.contains("Finished", ignoreCase = true) -> SAnime.COMPLETED
        text.contains("Releasing", ignoreCase = true) -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeId = response.asJsoup()
            .selectFirst("div.rate-box")?.attr("data-id") ?: return emptyList()
        val token = decoder.generateToken(animeId)

        val epHtml = client.newCall(GET("$baseUrl/ajax/episodes/list?ani_id=$animeId&_=$token")).execute().body?.string()
            ?: return emptyList()
        val epDocument = Jsoup.parse(epHtml)

        return epDocument.select("div.eplist a").mapIndexed { index, ep ->
            SEpisode.create().apply {
                name = ep.select("span").text().ifEmpty { "Episode ${index + 1}" }
                episode_number = ep.attr("num").toFloatOrNull() ?: (index + 1).toFloat()
                url = ep.attr("token")
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val token = response.request.url.toString().substringAfterLast("token=")
        val doc = response.asJsoup()

        val preferredServer = preferences.getString(PREF_SERVER_KEY, defaultServer)!!
        val preferredSubtype = preferences.getString(PREF_SUBTYPE_KEY, defaultSubtype)!!

        val serverEls = doc.select("div.server-items span.server[data-lid]")

        val videos = serverEls.mapNotNull { serverEl ->
            val lid = serverEl.attr("data-lid")
            val label = serverEl.text()
            val videoRes = client.newCall(
                GET("$baseUrl/ajax/links/view?id=$lid&_=${decoder.generateToken(lid)}")
            ).execute().body?.string() ?: return@mapNotNull null

            val jsonEncoded = Jsoup.parse(videoRes).text()
            val iframeDecoded = decoder.decodeIframeData(jsonEncoded)
            val videoUrl = JSONObject(iframeDecoded).optString("url") ?: return@mapNotNull null

            Video(videoUrl, "AnimeKai | $label", videoUrl, null)
        }

        return videos.sortedWith(
            compareByDescending<Video> {
                it.quality.contains(preferredServer, ignoreCase = true)
            }.thenByDescending {
                it.quality.contains(preferredSubtype, ignoreCase = true)
            }
        )
    }

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val serverPref = ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred Server"
            entries = arrayOf("HD-1", "HD-2")
            entryValues = arrayOf("HD-1", "HD-2")
            setDefaultValue(defaultServer)
            summary = "%s"
        }

        val subPref = ListPreference(screen.context).apply {
            key = PREF_SUBTYPE_KEY
            title = "Preferred Subtitle Type"
            entries = arrayOf("Sub", "Dub", "Softsub")
            entryValues = arrayOf("sub", "dub", "softsub")
            setDefaultValue(defaultSubtype)
            summary = "%s"
        }

        val domainPref = ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred Domain"
            entries = arrayOf("animekai.to", "animekai.bz")
            entryValues = arrayOf("https://animekai.to", "https://animekai.bz")
            setDefaultValue(defaultDomain)
            summary = "%s"
        }

        screen.addPreference(serverPref)
        screen.addPreference(subPref)
        screen.addPreference(domainPref)
    }

    override fun videoUrlParse(response: Response): String = throw UnsupportedOperationException()
    override fun episodeFromElement(element: org.jsoup.nodes.Element): SEpisode = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: org.jsoup.nodes.Element): SAnime = throw UnsupportedOperationException()
    override fun popularAnimeFromElement(element: org.jsoup.nodes.Element): SAnime = throw UnsupportedOperationException()
    override fun searchAnimeFromElement(element: org.jsoup.nodes.Element): SAnime = throw UnsupportedOperationException()

    override fun popularAnimeNextPageSelector(): String? = null
    override fun latestUpdatesNextPageSelector(): String? = null
    override fun searchAnimeNextPageSelector(): String? = null
    override fun popularAnimeSelector(): String = "div.aitem"
    override fun latestUpdatesSelector(): String = popularAnimeSelector()
    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun episodeListSelector(): String = "div.eplist a"
}
