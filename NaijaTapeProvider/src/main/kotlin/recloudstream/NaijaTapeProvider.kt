package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NaijaTapeProvider : MainAPI() {
    override var mainUrl = "https://www.naijatape.com"
    override var name = "NaijaTape"
    override val supportedTypes = setOf(TvType.Others, TvType.Movie)
    override var lang = "en"
    override val hasMainPage = true

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private suspend fun getHtml(path: String, retries: Int = 2): String {
        val url = "$mainUrl/${path.removePrefix("/")}"
        var lastErr: Exception? = null
        for (attempt in 0..retries) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", mainUrl)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .get()
                    .build()
                val resp = withContext(Dispatchers.IO) { httpClient.newCall(req).execute() }
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                val body = resp.body?.string() ?: throw Exception("Empty body")
                resp.closeSilently()
                return body
            } catch (e: Exception) {
                lastErr = e
                if (attempt < retries) Thread.sleep(1000L * (attempt + 1))
            }
        }
        throw lastErr ?: Exception("Unknown error")
    }

    private fun parsePostCards(html: String): List<PostCard> {
        val doc = Jsoup.parse(html)
        val cards = mutableListOf<PostCard>()
        doc.select("article").forEach { el ->
            val linkEl = el.select("h2 a, .entry-title a").first()
            if (linkEl == null) return@forEach
            val href = linkEl.attr("href").trim()
            val slug = href.substringAfterLast("/").replace(".html", "").replace(".php", "").trim()
            if (slug.isBlank()) return@forEach

            val title = linkEl.text().trim()
                ?: el.select("img").first()?.attr("alt")?.trim() ?: ""

            val img = el.select("img").first()
            val thumbnail = img?.attr("src")
                ?: img?.attr("data-src")
                ?: img?.attr("data-lazy-src")
                ?: null

            val dateEl = el.select("time, .entry-date, .post-date").first()
            val date = dateEl?.attr("datetime")?.trim()
                ?: dateEl?.text()?.trim()
                ?: null

            val categories = el.select(".cat-links a, .category a, .post-categories a")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()

            cards.add(PostCard(slug, title, thumbnail, date, categories))
        }
        return cards
    }

    private fun parsePagination(html: String): Pair<Int, Int> {
        val doc = Jsoup.parse(html)
        var maxPage = 1
        doc.select(".pagination a, .nav-links a, .page-numbers:not(.next):not(.prev)").forEach { el ->
            val text = el.text().trim()
            val num = text.toIntOrNull()
            if (num != null && num > maxPage) maxPage = num
        }
        val current = doc.select(".page-numbers.current").first()
        val page = current?.text()?.trim()?.toIntOrNull() ?: 1
        return Pair(page, maxPage)
    }

    private fun parsePostDetail(html: String, slug: String): PostDetail {
        val doc = Jsoup.parse(html)

        val titleEl = doc.select("h1.entry-title, meta[property=og:title]").first()
        val title = if (titleEl?.tagName() == "meta") {
            titleEl.attr("content").trim()
        } else {
            titleEl?.text()?.trim() ?: ""
        }

        val thumbEl = doc.select("meta[property=og:image], .wp-post-image").first()
        val thumbnail = if (thumbEl?.tagName() == "meta") {
            thumbEl.attr("content").takeIf { it.isNotEmpty() }
        } else {
            thumbEl?.attr("src").takeIf { it.isNotEmpty() }
        }

        val dateEl = doc.select("time.entry-date, meta[property=article:published_time], .entry-date").first()
        val date = if (dateEl?.tagName() == "meta") {
            dateEl.attr("content").takeIf { it.isNotEmpty() }
        } else {
            dateEl?.attr("datetime")?.trim() ?: dateEl?.text()?.trim()
        }

        val videoUrls = mutableSetOf<String>()
        doc.select("a[href*=.mp4]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.contains("cdn.naijatape.com") && href.endsWith(".mp4")) {
                videoUrls.add(href)
            }
        }
        doc.select("video source[src], video[src]").forEach { el ->
            val src = el.attr("src").trim()
            if (src.contains("cdn.naijatape.com") && src.endsWith(".mp4")) {
                videoUrls.add(src)
            }
        }
        doc.select(".entry-content, .post-content, article .content").forEach { block ->
            val text = block.text()
            Regex("https?://[^\\s]+\\.mp4").findAll(text).forEach { m ->
                val url = m.value
                if (url.contains("cdn.naijatape.com")) videoUrls.add(url)
            }
        }

        val images = mutableSetOf<String>()
        doc.select(".entry-content img, .post-content img, article img").forEach { el ->
            val src = el.attr("src").takeIf { it.isNotEmpty() }
                ?: el.attr("data-src").takeIf { it.isNotEmpty() }
                ?: return@forEach
            if (src.contains("wp-content/uploads") && !src.endsWith(".mp4")) {
                images.add(src)
            }
        }

        val categories = doc.select(".cat-links a, .category a, .post-categories a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val articleEl = doc.select("article").first()
        if (articleEl != null) {
            val articleClass = articleEl.attr("class")
            Regex("category-([\\w-]+)").findAll(articleClass).forEach {
                val cat = it.groupValues[1].replace("-", " ")
                if (cat.isNotEmpty() && !categories.contains(cat)) categories.add(cat)
            }
            Regex("tag-([\\w-]+)").findAll(articleClass).forEach {
                val tag = it.groupValues[1].replace("-", " ")
                if (tag.isNotEmpty()) Unit
            }
        }

        val tags = doc.select(".tag-links a, .tags a, .post-tags a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (articleEl != null) {
            val articleClass = articleEl.attr("class")
            Regex("tag-([\\w-]+)").findAll(articleClass).forEach {
                val tag = it.groupValues[1].replace("-", " ")
                if (tag.isNotEmpty() && !tags.contains(tag)) tags.add(tag)
            }
        }

        return PostDetail(
            slug = slug,
            title = title,
            thumbnail = thumbnail,
            date = date,
            videoUrls = videoUrls.toList(),
            images = images.toList(),
            categories = categories,
            tags = tags,
        )
    }

    private fun extractYear(dateStr: String?): Int? {
        if (dateStr == null || dateStr.length < 4) return null
        return dateStr.substring(0, 4).toIntOrNull()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = if (page == 1) "/" else "/page/$page/"
        val html = getHtml(path)
        val posts = parsePostCards(html)

        return newHomePageResponse(
            listOf(
                HomePageList("Latest", posts.map { it.toSearchResponse(this) }, true)
            )
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val safeQuery = query.replace(" ", "-")
        val path = if (page == 1) "/?s=$safeQuery" else "/page/$page/?s=$safeQuery"
        val html = getHtml(path)
        val posts = parsePostCards(html)
        return posts.map { it.toSearchResponse(this) }.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.trimEnd('/').substringAfterLast("/")
        if (slug.isBlank()) return null

        val searchHtml = getHtml("/?s=${slug.replace("-", " ")}")
        val doc = Jsoup.parse(searchHtml)
        val firstLink = doc.select(".entry-title a").first()?.attr("href")
        if (firstLink.isNullOrEmpty()) return null

        val postHtml = getHtml(firstLink)
        val detail = parsePostDetail(postHtml, slug)
        if (detail.videoUrls.isEmpty() && detail.images.isEmpty()) return null

        val dataJson = (detail.videoUrls + detail.images).toJson()

        return newMovieLoadResponse(
            detail.title,
            url,
            TvType.Movie,
            dataJson
        ) {
            posterUrl = detail.thumbnail
            plot = buildString {
                detail.categories.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { append("Categories: $it\n") }
                detail.tags.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { append("Tags: $it") }
            }.ifBlank { null }
            detail.date?.let { year = extractYear(it) }
            tags = (detail.categories + detail.tags).takeIf { it.isNotEmpty() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = tryParseJson<List<String>>(data) ?: return false
        urls.forEachIndexed { index, videoUrl ->
            callback(
                newExtractorLink(name, "Link ${index + 1}", videoUrl) {
                    quality = Qualities.Unknown.value
                    referer = ""
                }
            )
        }
        return urls.isNotEmpty()
    }

    private data class PostCard(
        val slug: String,
        val title: String,
        val thumbnail: String?,
        val date: String?,
        val categories: List<String>,
    ) {
        fun toSearchResponse(provider: NaijaTapeProvider): SearchResponse {
            return provider.newMovieSearchResponse(
                title,
                "${provider.mainUrl}/post/$slug",
                TvType.Others
            ) {
                posterUrl = thumbnail
            }
        }
    }

    private data class PostDetail(
        val slug: String,
        val title: String,
        val thumbnail: String?,
        val date: String?,
        val videoUrls: List<String>,
        val images: List<String>,
        val categories: List<String>,
        val tags: List<String>,
    )
}
