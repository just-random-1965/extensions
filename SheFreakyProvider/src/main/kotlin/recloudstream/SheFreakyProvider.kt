package recloudstream

import org.jsoup.Jsoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Element

class SheFreakyProvider : MainAPI() {
    override var mainUrl = "https://www.shesfreaky.com"
    override var name = "She's Freaky"
    override val supportedTypes = setOf(TvType.Others)
    override var lang = "en"
    override val hasMainPage = true

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun normalizeUrl(url: String): String {
        if (url.isEmpty()) return ""
        return if (url.startsWith("//")) "https:$url" else url
    }

    private fun getHtml(path: String, retries: Int = 2): String {
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
                val resp = httpClient.newCall(req).execute()
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                val body = resp.body?.string() ?: throw Exception("Empty body")
                resp.use { }
                return body
            } catch (e: Exception) {
                lastErr = e
                if (attempt < retries) Thread.sleep(1000L * (attempt + 1))
            }
        }
        throw lastErr ?: Exception("Unknown error")
    }

    private fun parseListItem(el: Element): ListItem? {
        val link = el.select("a").first() ?: return null
        val href = link.attr("href").trim()
        if (href.isEmpty()) return null

        val videoMatch = Regex("/video/(.+)-(\\d+)\\.html").find(href)
        val galleryMatch = Regex("/gallery/(.+)-(\\d+)\\.html").find(href)

        if (videoMatch == null && galleryMatch == null) return null

        val type = if (videoMatch != null) "video" else "gallery"
        val id = (videoMatch ?: galleryMatch!!).groups[2]!!.value.toIntOrNull() ?: return null
        val slug = (videoMatch ?: galleryMatch!!).groups[1]!!.value

        val title = el.select(".item-title, .title, h2, h3").first()?.text()?.trim()
            ?: el.select("img").first()?.attr("alt")?.trim() ?: ""

        val thumbRaw = el.select(".thumb img, img").first()
            ?.attr("src")
            ?.takeIf { !it.isNullOrEmpty() }
            ?: el.select(".thumb img").first()?.attr("data-src")
            ?.takeIf { !it.isNullOrEmpty() }
        val thumbnail = thumbRaw?.let { normalizeUrl(it) }

        val previewUrl = el.select("[data-preview]").first()?.attr("data-preview")
            ?.takeIf { !it.isNullOrEmpty() }

        val duration = el.select(".thumb-length, .video-duration, .duration").first()?.text()?.trim()
        val views = el.select(".video-views, .thumb-views, [class*=views]").first()?.text()?.trim()

        val photoCountText = el.select(".thumb-count, .photo-count").first()?.text()?.trim()
        val photoCount = photoCountText?.let { Regex("(\\d+)").find(it) }
            ?.groups?.get(1)?.value?.toIntOrNull()

        return ListItem(
            id = id,
            slug = slug,
            title = title,
            type = type,
            thumbnail = thumbnail,
            previewUrl = previewUrl,
            duration = duration,
            views = views,
            photoCount = photoCount,
            url = href,
        )
    }

    private fun parseListingPage(html: String): List<ListItem> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<ListItem>()
        doc.select(".item").forEach { el ->
            if (el.select("a[href*=video], a[href*=gallery]").isNotEmpty()) {
                val item = parseListItem(el)
                if (item != null) items.add(item)
            }
        }
        return items
    }

    private fun parseVideoDetail(html: String, id: Int): VideoDetail? {
        val doc = Jsoup.parse(html)

        val title = doc.select("h2, h1").first()?.text()?.trim() ?: ""

        val canonical = doc.select("link[rel=canonical]").first()?.attr("href") ?: ""
        val urlMatch = Regex("/video/(.+)-(\\d+)\\.html").find(canonical)
        var slug = urlMatch?.groups?.get(1)?.value ?: ""
        if (slug.isEmpty()) {
            val pageTitle = doc.title().trim()
            val slugMatch = Regex("^(.+?)\\s*-\\s*ShesFreaky").find(pageTitle)
            if (slugMatch != null) {
                slug = slugMatch.groups[1]!!.value.trim()
                    .replace("\\s+".toRegex(), "-")
                    .lowercase()
            }
        }

        var videoUrl: String? = doc.select("video#video-id source[src], video source[src], video[src]").first()?.attr("src")
        if (videoUrl.isNullOrEmpty()) {
            videoUrl = doc.select("[data-preview]").first()?.attr("data-preview")
        }
        if (videoUrl.isNullOrEmpty()) {
            doc.select("script").forEach { script ->
                val text = script.data() ?: return@forEach
                val match = Regex("""src['"\s:]+\s*['"]([^"']+\.mp4[^"']*)['"]""").find(text)
                if (match != null) {
                    videoUrl = match.groups[1]!!.value.replace("\\\\/", "/")
                    return@forEach
                }
            }
        }

        var thumbnail: String? = doc.select("video#video-id, video").first()?.attr("poster")
            ?: doc.select("meta[property=og:image]").first()?.attr("content")
        if (thumbnail.isNullOrEmpty()) {
            val thumbSrc = doc.select("#content-thumbs img").first()?.attr("src")
            if (!thumbSrc.isNullOrEmpty()) thumbnail = normalizeUrl(thumbSrc)
        }

        val contentMain = doc.select("#content-main").first()
        val contentText = contentMain?.text() ?: ""

        var duration = doc.select(".thumb-length, .video-duration, .duration").first()?.text()?.trim()
        var views = doc.select(".video-views, .thumb-views, [class*=views]").first()?.text()?.trim()
        var date = doc.select(".video-date, .gallery-date, .date, .post-date").first()?.text()?.trim()

        doc.select("h2 + p, h2 ~ p").first()?.let { metaP ->
            val metaHtml = metaP.html() ?: ""
            if (duration.isNullOrEmpty()) {
                val clockMatch = Regex("fa-clock[^<]*</i>\\s*([\\d:]+)").find(metaHtml)
                if (clockMatch != null) duration = clockMatch.groups[1]!!.value.trim()
            }
            if (views.isNullOrEmpty()) {
                val eyeMatch = Regex("fa-eye[^<]*</i>\\s*([\\d,.KkMmbBvViwW]+)").find(metaHtml)
                if (eyeMatch != null) views = eyeMatch.groups[1]!!.value.trim()
            }
            if (date.isNullOrEmpty()) {
                val calMatch = Regex("fa-calendar[^<]*</i>\\s*([\\d-]+)").find(metaHtml)
                if (calMatch != null) date = calMatch.groups[1]!!.value.trim()
            }
        }

        if (duration.isNullOrEmpty()) {
            val durMatch = Regex("(\\d+:\\d+)").find(contentText)
            if (durMatch != null) duration = durMatch.groups[1]!!.value
        }

        val rating = doc.select(".video-rating, .rating").first()?.text()?.trim()
            ?: doc.select("#rating-thumbs .btn-success").first()?.text()?.trim()

        val description = Regex("Description:\\s*([^<]+)").find(contentText)
            ?.groups?.get(1)?.value?.trim()
            ?: doc.select(".description, .video-description, .entry-content").first()?.text()?.trim()
            ?: ""

        val categories = mutableListOf<CategoryInfo>()
        doc.select(".categories a, .channels a, [class*=category] a, [class*=channel] a, #content-main a[href*=channels]").forEach { a ->
            val href = a.attr("href").trim()
            val name = a.text().trim()
            val chMatch = Regex("/channels/(\\d+)/([^/]+)/").find(href)
            if (chMatch != null && name.isNotEmpty()) {
                categories.add(CategoryInfo(
                    id = chMatch.groups[1]!!.value.toIntOrNull() ?: 0,
                    name = name,
                    slug = chMatch.groups[2]!!.value,
                    url = href,
                ))
            }
        }

        val tags = mutableListOf<String>()
        doc.select(".tags a, [class*=tag] a, #content-main a[href*=search]").forEach { a ->
            val name = a.text().trim().lowercase()
            if (name.isNotEmpty() && !name.contains("+") && !name.startsWith("suggest")) {
                tags.add(name)
            }
        }

        val uploaderLink = doc.select(".uploader a, .profile a, .member a, a.redlinks[href*=profile]").first()
        val uploaderImg = doc.select(".uploader img, .profile img, .member img").first()
        val uploader: UploaderInfo?
        if (uploaderLink != null) {
            val avatarSrc = uploaderImg?.attr("src")
            uploader = UploaderInfo(
                username = uploaderLink.text().trim(),
                profileUrl = uploaderLink.attr("href").trim(),
                avatar = if (!avatarSrc.isNullOrEmpty()) normalizeUrl(avatarSrc) else null,
            )
        } else {
            uploader = null
        }

        return VideoDetail(
            id = id,
            slug = slug,
            title = title,
            description = description,
            thumbnail = thumbnail?.let { normalizeUrl(it) },
            videoUrl = videoUrl,
            duration = duration,
            views = views,
            rating = rating,
            date = date,
            categories = categories,
            tags = tags,
            uploader = uploader,
        )
    }

    private fun parseGalleryDetail(html: String, id: Int): GalleryDetail? {
        val doc = Jsoup.parse(html)

        val title = doc.select("h2, h1").first()?.text()?.trim() ?: ""

        val canonical = doc.select("link[rel=canonical]").first()?.attr("href") ?: ""
        val urlMatch = Regex("/gallery/(.+)-(\\d+)\\.html").find(canonical)
        var slug = urlMatch?.groups?.get(1)?.value ?: ""
        if (slug.isEmpty()) {
            val pageTitle = doc.title().trim()
            val slugMatch = Regex("^(.+?)\\s*-\\s*ShesFreaky").find(pageTitle)
            if (slugMatch != null) {
                slug = slugMatch.groups[1]!!.value.trim()
                    .replace("\\s+".toRegex(), "-")
                    .lowercase()
            }
        }

        val images = mutableListOf<String>()
        val thumbnails = mutableListOf<String>()

        doc.select(".gallery-images a, .gallery-item a, #gallery-container a[href*=galleries], a[href*=galleries]").forEach { a ->
            val href = a.attr("href").trim()
            val thumb = a.select("img").first()?.attr("src") ?: ""
            if (href.contains("/galleries/")) {
                images.add(normalizeUrl(href))
                if (thumb.isNotEmpty()) thumbnails.add(normalizeUrl(thumb))
            }
        }

        if (images.isEmpty()) {
            doc.select("#gallery-container img[src*=galleries], img[src*=galleries]").forEach { el ->
                val src = el.attr("src").takeIf { !it.isNullOrEmpty() }
                    ?: el.attr("data-src").takeIf { !it.isNullOrEmpty() }
                    ?: return@forEach
                if (src.contains("/galleries/") && !src.contains("/thumbs/")) {
                    images.add(normalizeUrl(src))
                }
                val thumb = el.attr("src")
                if (!thumb.isNullOrEmpty() && thumb.contains("/galleries/") && thumb.contains("/thumbs/")) {
                    thumbnails.add(normalizeUrl(thumb))
                }
            }
        }

        if (images.isEmpty() && thumbnails.isNotEmpty()) {
            thumbnails.forEach { t -> images.add(t.replace("/thumbs/", "/")) }
        }

        val uniqueImages = images.distinct()
        val uniqueThumbnails = thumbnails.distinct()

        val photoCountText = doc.select(".thumb-count, .photo-count, .gallery-count").first()?.text()?.trim()
        val photoCount = photoCountText?.let { Regex("(\\d+)").find(it) }
            ?.groups?.get(1)?.value?.toIntOrNull() ?: uniqueImages.size

        var views = doc.select(".video-views, .thumb-views, [class*=views]").first()?.text()?.trim()
        var date = doc.select(".video-date, .gallery-date, .date, .post-date").first()?.text()?.trim()

        doc.select("h2 + p, h2 ~ p").first()?.let { metaP ->
            val metaHtml = metaP.html() ?: ""
            if (views.isNullOrEmpty()) {
                val eyeMatch = Regex("fa-eye[^<]*</i>\\s*([\\d,.KkMmbBvViwW]+)").find(metaHtml)
                if (eyeMatch != null) views = eyeMatch.groups[1]!!.value.trim()
            }
            if (date.isNullOrEmpty()) {
                val calMatch = Regex("fa-calendar[^<]*</i>\\s*([\\d-]+)").find(metaHtml)
                if (calMatch != null) date = calMatch.groups[1]!!.value.trim()
            }
        }

        val categories = mutableListOf<CategoryInfo>()
        doc.select(".categories a, .channels a, [class*=category] a, [class*=channel] a, #content-main a[href*=channels]").forEach { a ->
            val href = a.attr("href").trim()
            val name = a.text().trim()
            val chMatch = Regex("/channels/(\\d+)/([^/]+)/").find(href)
            if (chMatch != null && name.isNotEmpty()) {
                categories.add(CategoryInfo(
                    id = chMatch.groups[1]!!.value.toIntOrNull() ?: 0,
                    name = name,
                    slug = chMatch.groups[2]!!.value,
                ))
            }
        }

        val tags = mutableListOf<String>()
        doc.select(".tags a, [class*=tag] a, #content-main a[href*=search]").forEach { a ->
            val name = a.text().trim().lowercase()
            if (name.isNotEmpty() && !name.contains("+") && !name.startsWith("suggest")) {
                tags.add(name)
            }
        }

        return GalleryDetail(
            id = id,
            slug = slug,
            title = title,
            views = views,
            date = date,
            photoCount = photoCount,
            images = uniqueImages,
            thumbnails = uniqueThumbnails,
            categories = categories,
            tags = tags,
        )
    }

    private fun parseChannels(html: String): List<Channel> {
        val doc = Jsoup.parse(html)
        val channels = mutableListOf<Channel>()
        val seen = mutableSetOf<Int>()
        doc.select("a[href*=channels]").forEach { a ->
            val href = a.attr("href").trim()
            val name = a.text().trim()
            val match = Regex("/channels/(\\d+)/([^/]+)/").find(href)
            if (match != null && name.isNotEmpty()) {
                val id = match.groups[1]!!.value.toIntOrNull() ?: return@forEach
                if (!seen.contains(id)) {
                    seen.add(id)
                    channels.add(Channel(id = id, name = name, slug = match.groups[2]!!.value, url = href))
                }
            }
        }
        return channels
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sections = mutableListOf<HomePageList>()

        if (page == 1) {
            try {
                val html = getHtml("/featured/")
                val items = parseListingPage(html)
                if (items.isNotEmpty()) sections.add(HomePageList("Featured", items.map { it.toSearchResponse(this) }))
            } catch (_: Exception) {}

            try {
                val html = getHtml("/videos/")
                val items = parseListingPage(html)
                if (items.isNotEmpty()) sections.add(HomePageList("Latest Videos", items.map { it.toSearchResponse(this) }))
            } catch (_: Exception) {}

            try {
                val html = getHtml("/top-rated/")
                val items = parseListingPage(html)
                if (items.isNotEmpty()) sections.add(HomePageList("Top Rated", items.map { it.toSearchResponse(this) }))
            } catch (_: Exception) {}

            try {
                val html = getHtml("/most-viewed/")
                val items = parseListingPage(html)
                if (items.isNotEmpty()) sections.add(HomePageList("Most Viewed", items.map { it.toSearchResponse(this) }))
            } catch (_: Exception) {}

            try {
                val html = getHtml("/photos/")
                val items = parseListingPage(html)
                if (items.isNotEmpty()) sections.add(HomePageList("Latest Galleries", items.map { it.toSearchResponse(this) }))
            } catch (_: Exception) {}

            try {
                val channelsHtml = getHtml("/channels/")
                val channels = parseChannels(channelsHtml)
                if (channels.isNotEmpty()) {
                    val channelItems = channels.map { channel ->
                        newMovieSearchResponse(
                            channel.name,
                            "$mainUrl/category/${channel.id}?page=1",
                            TvType.Others
                        )
                    }
                    sections.add(HomePageList("Categories", channelItems, true))
                }
            } catch (_: Exception) {}
        } else {
            try {
                val html = getHtml("/videos/page$page.html")
                val items = parseListingPage(html)
                if (items.isNotEmpty()) sections.add(HomePageList("Latest Videos", items.map { it.toSearchResponse(this) }))
            } catch (_: Exception) {}
        }

        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        try {
            val html = getHtml("/searchgatev2.php?mode=search&type=videos&q=${query}&page${page}.html")
            val items = parseListingPage(html)
            return items.map { it.toSearchResponse(this) }.toNewSearchResponseList()
        } catch (e: Exception) {
            return null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/category/")) return null

        val isGallery = url.contains("/gallery/")
        val id = url.substringAfterLast("/").trimEnd('/').toIntOrNull() ?: return null

        return if (isGallery) loadGallery(id, url) else loadVideo(id, url)
    }

    private suspend fun loadVideo(videoId: Int, url: String): LoadResponse? {
        val link = findItemLink(videoId, "/searchgatev2.php?mode=search&type=videos&q=$videoId")
        if (link.isNullOrEmpty()) return null

        val html = getHtml(link)
        val detail = parseVideoDetail(html, videoId) ?: return null
        if (detail.videoUrl.isNullOrEmpty()) return null

        val displayTitle = if (detail.title.isNullOrEmpty()) "Video #$videoId" else detail.title!!

        return newMovieLoadResponse(
            displayTitle,
            url,
            TvType.Others,
            listOfNotNull(detail.videoUrl)
        ) {
            posterUrl = detail.thumbnail
            plot = buildString {
                if (!detail.description.isNullOrEmpty()) append("${detail.description}\n")
                if (!detail.categories.isNullOrEmpty()) {
                    val catNames = detail.categories!!.map { it.name }.joinToString(", ")
                    append("Categories: $catNames\n")
                }
                detail.uploader?.username?.let { append("Uploader: $it\n") }
            }.ifBlank { null }
            if (!detail.tags.isNullOrEmpty()) tags = detail.tags
            detail.date?.takeLast(4)?.toIntOrNull()?.let { year = it }
        }
    }

    private suspend fun loadGallery(galleryId: Int, url: String): LoadResponse? {
        val link = findItemLink(galleryId, "/searchgatev2.php?mode=search&type=photos&q=$galleryId")
        if (link.isNullOrEmpty()) return null

        val html = getHtml(link)
        val detail = parseGalleryDetail(html, galleryId) ?: return null
        if (detail.images.isEmpty()) return null

        val displayTitle = if (detail.title.isNullOrEmpty()) "Gallery #$galleryId" else detail.title!!

        return newMovieLoadResponse(
            displayTitle,
            url,
            TvType.Others,
            detail.images
        ) {
            posterUrl = detail.thumbnails.firstOrNull()
            plot = buildString {
                if (!detail.categories.isNullOrEmpty()) {
                    val catNames = detail.categories!!.map { it.name }.joinToString(", ")
                    append("Categories: $catNames\n")
                }
                append("${detail.photoCount} images")
            }
            if (!detail.tags.isNullOrEmpty()) tags = detail.tags
            detail.date?.takeLast(4)?.toIntOrNull()?.let { year = it }
        }
    }

    private fun findItemLink(id: Int, searchPath: String): String? {
        try {
            val html = getHtml(searchPath)
            val doc = Jsoup.parse(html)
            val link = doc.select(".item-$id a, a[href*=\\-$id.html]").first()?.attr("href")
            if (!link.isNullOrEmpty()) return link
        } catch (_: Exception) {}

        try {
            val html = getHtml(if (searchPath.contains("photos")) "/photos/" else "/videos/")
            val items = parseListingPage(html)
            return items.find { it.id == id }?.url
        } catch (_: Exception) {}

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = tryParseJson<List<String>>(data) ?: return false
        val isVideo = urls.size == 1
        urls.forEachIndexed { index, link ->
            callback(
                newExtractorLink(name, if (isVideo) "Video" else "Image ${index + 1}", link) {
                    quality = Qualities.Unknown.value
                    referer = ""
                }
            )
        }
        return urls.isNotEmpty()
    }

    private data class ListItem(
        val id: Int,
        val slug: String,
        val title: String,
        val type: String,
        val thumbnail: String?,
        val previewUrl: String?,
        val duration: String?,
        val views: String?,
        val photoCount: Int?,
        val url: String,
    ) {
        fun toSearchResponse(provider: SheFreakyProvider): SearchResponse {
            val posterFix = when {
                thumbnail == null -> null
                thumbnail.startsWith("data:") -> null
                else -> thumbnail
            }
            return provider.newMovieSearchResponse(
                title.ifEmpty { "Item #$id" },
                "${provider.mainUrl}/${if (type == "gallery") "gallery" else "video"}/$id",
                TvType.Others
            ) {
                this.posterUrl = posterFix
            }
        }
    }

    private data class VideoDetail(
        val id: Int,
        val slug: String?,
        val title: String?,
        val description: String?,
        val thumbnail: String?,
        val videoUrl: String?,
        val duration: String?,
        val views: String?,
        val rating: String?,
        val date: String?,
        val categories: List<CategoryInfo>,
        val tags: List<String>,
        val uploader: UploaderInfo?,
    )

    private data class GalleryDetail(
        val id: Int,
        val slug: String?,
        val title: String?,
        val views: String?,
        val date: String?,
        val photoCount: Int,
        val images: List<String>,
        val thumbnails: List<String>,
        val categories: List<CategoryInfo>,
        val tags: List<String>,
    )

    private data class CategoryInfo(
        val id: Int,
        val name: String,
        val slug: String,
        val url: String? = null,
    )

    private data class UploaderInfo(
        val username: String,
        val profileUrl: String,
        val avatar: String?,
    )

    private data class Channel(
        val id: Int,
        val name: String,
        val slug: String,
        val url: String,
    )
}
