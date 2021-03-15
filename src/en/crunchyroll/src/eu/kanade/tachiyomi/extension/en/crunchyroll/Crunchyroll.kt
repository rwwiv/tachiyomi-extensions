package eu.kanade.tachiyomi.extension.en.crunchyroll

import android.webkit.CookieManager
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import eu.kanade.tachiyomi.extension.en.crunchyroll.model.Chapters
import eu.kanade.tachiyomi.extension.en.crunchyroll.model.Pages
import eu.kanade.tachiyomi.extension.en.crunchyroll.model.SeriesInfo
import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.lang.Exception
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class Crunchyroll : HttpSource() {
    override val name = "Crunchyroll"

    override val baseUrl = "https://www.crunchyroll.com"

    override val lang = "en"

    override val supportsLatest = true

    private val rateLimitInterceptor = RateLimitInterceptor(1)

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(rateLimitInterceptor)
        .connectTimeout(1, TimeUnit.MINUTES)
        .readTimeout(1, TimeUnit.MINUTES)
        .writeTimeout(1, TimeUnit.MINUTES)
        .addInterceptor(CrunchyrollImageInterceptor())
        .build()

    private val gson = GsonBuilder()
        .setLenient()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

    private val cookieString: String
        get() {
            return CookieManager.getInstance().getCookie(baseUrl) ?: throw Exception("Open WebView to login")
        }

    private val sessionId: String
        get() {
            for (string in cookieString.split("; ")) {
                val cookie = string.split("=")

                if (cookie[0] == "session_id") {
                    return cookie[1]
                }
            }
            throw Exception("Open WebView to login")
        }

    private fun addParams(urlString: String, vararg params: Pair<String, String>): String {
        var tempString = urlString +
            "?device_id=$DEVICE_ID" +
            "&device_type=$DEVICE_TYPE" +
            "&locale=enUS" +
            "&auth=manga_auth" // Value not checked (login checked via cookie), but key needs to be included

        for (param in params) {
            tempString += "&${param.first}=${param.second}"
        }

        return tempString
    }

    // Popular

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        val url = "$API_MANGA_URL/series"
        val req = GET(addParams(url, Pair("session_id", sessionId)), headers)
        return client.newCall(req)
            .asObservableSuccess()
            .map { response ->
                popularMangaParse(response)
            }
    }

    override fun popularMangaRequest(page: Int) = throw UnsupportedOperationException("Not used.")

    override fun popularMangaParse(response: Response): MangasPage {
        val allSeries = gson.fromJson(response.body()!!.string(), Array<SeriesInfo>::class.java)

        val mangaList = mutableListOf<SManga>()

        allSeries.forEach { manga ->
            if (manga.locale != null) {
                mangaList.add(
                    seriesInfo2SManga(manga)
                )
            }
        }

        return MangasPage(mangaList, false)
    }

    private fun seriesInfo2SManga(seriesInfo: SeriesInfo): SManga = SManga.create().apply {
        if (seriesInfo.locale != null) {
            title = seriesInfo.locale.enUS["name"].toString()
            description = seriesInfo.locale.enUS["description"].toString().replace("\\r\\n", "\n")
            author = seriesInfo.authors
            artist = seriesInfo.artist
            thumbnail_url = seriesInfo.locale.enUS["thumb_url"].toString()
            // Cheat storing the series id as a URL param since there's no field we can use
            url = "/comics/manga${seriesInfo.url}/volumes?series_id=${URLEncoder.encode(seriesInfo.seriesId, "UTF-8")}"
            status = SManga.LICENSED
            initialized = true
        }
    }

    // Latest

    override fun fetchLatestUpdates(page: Int) = fetchPopularManga(page)

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException("Not used.")

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // Search

    private var globalQuery: String = ""

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val url = "$API_MANGA_URL/list_series"
        val req = GET(addParams(url, Pair("session_id", sessionId)), headers)
        globalQuery = query
        return client.newCall(req)
            .asObservableSuccess()
            .map { response ->
                searchMangaParse(response)
            }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = throw UnsupportedOperationException("Not used.")

    override fun searchMangaParse(response: Response): MangasPage {
        val allSeries = gson.fromJson(response.body()!!.string(), Array<SeriesInfo>::class.java)

        val mangaList = mutableListOf<SManga>()

        val filteredList = allSeries.filter { manga ->
            manga.locale != null && manga.locale.enUS["name"].toString().contains(globalQuery, ignoreCase = true)
        }

        filteredList.forEach { manga ->
            if (manga.locale != null) {
                mangaList.add(
                    seriesInfo2SManga(manga)
                )
            }
        }

        return MangasPage(mangaList, false)
    }

    // Details

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return Observable.just(manga)
    }

    override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException("Not used.")

    // Chapters

    private var mangaUrl = ""

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val url = "$API_MANGA_URL/list_chapters"
        val seriesId = URLDecoder.decode(manga.url.substringAfter("?series_id="), "UTF-8")
        val req = GET(addParams(url, Pair("session_id", sessionId), Pair("series_id", seriesId)), headers)
        mangaUrl = manga.url
        return client.newCall(req)
            .asObservableSuccess()
            .map { response ->
                chapterListParse(response)
            }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val allChapters = gson.fromJson(response.body()!!.string(), Chapters::class.java)

        val chapterList = mutableListOf<SChapter>()

        for (chapter in allChapters.chapters) {
            if (chapter.locale != null) {
                chapterList.add(
                    SChapter.create().apply {
                        val tempUrl = mangaUrl
                            .substringBefore("/volumes")
                            .replace("/comics", "")
                        url = "$tempUrl/read/${chapter.number.toString().substringBefore(".00")}?chapter_id=${chapter.chapterId}"
                        chapter_number = chapter.number
                        name = chapter.locale.enUS["name"].toString()
                        scanlator = "Crunchyroll"
                    }
                )
            }
        }

        return chapterList.sortedByDescending { it.chapter_number }
    }

    // Pages

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val res = client.newCall(pageListRequest(chapter)).execute()
        if (res.code() >= 400) {
            throw Exception("Couldn't fetch chapter\nMake sure to log in via WebView")
        }

        return client.newCall(pageListRequest(chapter))
            .asObservableSuccess()
            .map { response ->
                pageListParse(response)
            }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val newHeaders = headers.newBuilder()
            .add("Referer", baseUrl)
            .build()
        val url = "$API_MANGA_URL/list_chapter"
        val chapterId = URLDecoder.decode(chapter.url.substringAfter("?chapter_id="), "UTF-8")
        return GET(addParams(url, Pair("session_id", sessionId), Pair("chapter_id", chapterId)), newHeaders)
    }

    override fun pageListParse(response: Response): List<Page> {
        val pages = gson.fromJson(response.body()!!.string(), Pages::class.java)

        val pageList = mutableListOf<Page>()

        pages.pages.forEach { page ->
            if (page.locale != null) {
                pageList.add(
                    Page(
                        index = page.number.toInt(),
                        imageUrl = page.locale.enUS["encrypted_composed_image_url"]
                    )
                )
            }
        }

        return pageList
    }

    // Image

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .set("Referer", page.url)
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException("Not used.")

    companion object {
        private const val API_URL = "https://api.crunchyroll.com"
        private const val API_MANGA_URL = "https://api-manga.crunchyroll.com"

        private const val DEVICE_ID = "2fa9e3886d132f87"
        private const val DEVICE_TYPE = "com.crunchyroll.manga.android"
        private const val ACCESS_TOKEN = "FLpcfZH4CbW4muO"

        private const val TAG = "Crunchyroll"
    }
}
