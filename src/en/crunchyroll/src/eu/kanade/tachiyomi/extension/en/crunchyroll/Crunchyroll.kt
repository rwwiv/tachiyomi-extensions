package eu.kanade.tachiyomi.extension.en.crunchyroll

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import eu.kanade.tachiyomi.extension.en.crunchyroll.model.ChapterInfo
import eu.kanade.tachiyomi.extension.en.crunchyroll.model.VolumeData
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class Crunchyroll : ParsedHttpSource() {
    override val name = "Crunchyroll"

    override val baseUrl = "https://www.crunchyroll.com"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient
        get() = network.cloudflareClient.newBuilder()
            .addInterceptor(CrunchyrollImageInterceptor())
            .build()

    private val gson = GsonBuilder()
        .setLenient()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

    // Popular

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/comics/manga", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val mangasPage = super.popularMangaParse(response)

        if (mangasPage.mangas.isEmpty()) throw Exception(COUNTRY_NOT_SUPPORTED)

        return mangasPage
    }

    override fun popularMangaSelector() = "div#main_content li.group-item a"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.select("span.series-title").first().text()
        thumbnail_url = element.select("img").first()?.attr("src")
        url = element.attr("href")
    }

    override fun popularMangaNextPageSelector(): String? = null

    // Latest

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/comics/manga/updated", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val mangasPage = super.latestUpdatesParse(response)

        if (mangasPage.mangas.isEmpty()) throw Exception(COUNTRY_NOT_SUPPORTED)

        return mangasPage
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = null

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/search?q=$query&o=mg", headers)
    }

    override fun searchMangaSelector(): String = "ul.search-results>li>a"

    override fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.select("span.info span.name").first().text()
        thumbnail_url = element.select("span.mug img").first()?.attr("src")
        url = element.attr("href")
    }

    override fun searchMangaNextPageSelector(): String? = null

    // Details

    override fun mangaDetailsParse(document: Document): SManga {
        val seriesInfo = document.select("div#sidebar").first()

        val desc = seriesInfo.select("p.description").first().text()
        val descMore = seriesInfo.select("p.description span.more").first()?.text()

        val descriptionText: String = if (descMore != "") {
            desc.substringBefore(" ... more")
        } else {
            desc
        }

        return SManga.create().apply {
            status = SManga.UNKNOWN
            description = descriptionText
            author = seriesInfo.select("span:contains(Author)").first().parent().text()
            artist = seriesInfo.select("span:contains(Artist)").first().parent().text()
            thumbnail_url = seriesInfo.select("i.poster").first()?.attr("src")
        }
    }

    // Chapters

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return client.newCall(chapterListRequest(manga))
            .asObservableSuccess()
            .map { response ->
                chapterListParse(response)
            }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaPage = response.asJsoup()

        val volumeList: List<Element> =
            mangaPage.select("div#showview_content ul.volume-list li")

        val script = mangaPage.select("script:containsData(manga_premium)").first().data()
        val premiumUser = script.substringAfter("manga_premium:'").substringBefore("'")
        val mediaType = script.substringAfter("media_type: '").substringBefore("'")

        val chapterList: MutableList<SChapter> = mutableListOf()

        volumeList.forEach { element ->
            val id = element.attr("volume_id")
            val newHeaders = headersBuilder()
                .add("Referer", response.request().url().toString())
                .build()

            var index = 0
            var ajaxUrl = "$baseUrl/ajax/?req=RpcApiManga_GetMangaCollectionCarouselPage" +
                "&volume_id=$id" +
                "&first_index=$index" +
                "&media_type=$mediaType" +
                "&manga_premium=$premiumUser}"

            val content = stringFromAjaxRequest(GET(ajaxUrl, newHeaders))

            val objList: MutableList<VolumeData> = mutableListOf()

            val jsonObj = gson.fromJson(content, VolumeData::class.java)

            objList.add(jsonObj)

            if (jsonObj.data!!.entries.size == 5) {
                try {
                    while (true) {
                        index += 5
                        ajaxUrl = "$baseUrl/ajax/?req=RpcApiManga_GetMangaCollectionCarouselPage" +
                            "&volume_id=$id" +
                            "&first_index=$index" +
                            "&media_type=$mediaType" +
                            "&manga_premium=$premiumUser}"
                        val loopedContent = stringFromAjaxRequest(GET(ajaxUrl, newHeaders))
                        val loopedJsonObj = gson.fromJson(loopedContent, VolumeData::class.java)
                        if (loopedJsonObj.data == null) break
                        objList.add(loopedJsonObj)
                    }
                } finally {
                }
            }

            objList.forEach { v ->
                v.data!!.values.forEach { string ->
                    chapterList.addAll(parseCarouselHtmlString(string, premiumUser))
                }
            }
        }

        return chapterList.sortedByDescending { it.chapter_number }
    }

    private fun stringFromAjaxRequest(request: Request): String {
        val res = client.newCall(request)
            .execute()

        return res.body()!!.string()
            .substringAfter("/*-secure-")
            .substringBeforeLast("*/")
    }

    private fun parseCarouselHtmlString(string: String, premiumUser: String): List<SChapter> {
        val html = Jsoup.parse(string)

        val chapterList: MutableList<SChapter> = mutableListOf()

        val chapterSubList = html.select("div.collection-carousel-media-link")

        chapterSubList.forEach { e ->
            val premiumChapter = e.select("span.collection-carousel-premium-crown").first()
            if ((premiumUser == "1" && premiumChapter != null) || premiumChapter == null) {
                chapterList.add(genChapter(e))
            }
        }

        return chapterList
    }

    private fun genChapter(element: Element): SChapter = SChapter.create().apply {
        url = element.select("a.link").first().attr("href")
        name = element.select("a.link").first().attr("title")
        chapter_number =
            element.select("div.collection-carousel-overlay")
                .first()
                .text()
                .substringAfter("Ch. ")
                .toFloat()
        scanlator = "Crunchyroll"
    }

    override fun chapterListRequest(manga: SManga): Request {
        return GET("${baseUrl}${manga.url}", headers)
    }

    override fun chapterListSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun chapterFromElement(element: Element): SChapter {
        throw UnsupportedOperationException("Not used.")
    }

    // Pages

    override fun pageListParse(document: Document): List<Page> {
        val initScript = document.select("script:containsData(chapterId)").first().data()

        val chapterId = initScript.substringAfter("chapterId: \"").substringBefore("\",")
        val sessionId = initScript.substringAfter("sessionId: \"").substringBefore("\",")

        val req = GET(
            "$API_URL/list_chapter?auth=manga_auth&chapter_id=$chapterId&session_id=$sessionId",
            headers,
            CacheControl.FORCE_NETWORK
        )
        val res = client.newCall(req).execute()

        val chapterInfo = gson.fromJson(res.body()!!.string(), ChapterInfo::class.java)

        val pageCount = chapterInfo.pages.size

        return IntRange(1, pageCount)
            .map {
                val url = chapterInfo.pages[it - 1].locale.enUS.encryptedComposedImageUrl
                Page(it, url = document.location(), imageUrl = url)
            }
    }

    override fun imageUrlParse(document: Document): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .set("Referer", page.url)
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    companion object {
        private const val API_URL = "https://api-manga.crunchyroll.com"

        private const val COUNTRY_NOT_SUPPORTED = "Your country is not supported, try using a VPN."

        private const val TAG = "Crunchyroll"
    }
}
