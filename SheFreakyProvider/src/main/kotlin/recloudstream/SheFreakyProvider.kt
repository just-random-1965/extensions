package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.Tag
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.newExtractorLink

class SheFreakyProvider : MainAPI() {
    override var mainUrl = "https://she-api.vercel.app/api"
    override var name = "She's Freaky"
    override val supportedTypes = setOf(TvType.Others)
    override var lang = "en"
    override val hasMainPage = true
    override val hasSearchSupport = true

    private suspend fun apiGet(path: String): String {
        return app.get("$mainUrl$path").text
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sections = mutableListOf<HomePageList>()

        if (page == 1) {
            val featured = try {
                val json = apiGet("/featured?page=1")
                val resp = tryParseJson<ApiSearchResponse>(json)
                resp?.items?.map { it.toSearchResponse(this) } ?: emptyList()
            } catch (e: Exception) { emptyList() }
            if (featured.isNotEmpty()) sections.add(HomePageList("Featured", featured))

            val latestVideos = try {
                val json = apiGet("/latest?page=1&type=videos")
                val resp = tryParseJson<ApiSearchResponse>(json)
                resp?.items?.map { it.toSearchResponse(this) } ?: emptyList()
            } catch (e: Exception) { emptyList() }
            if (latestVideos.isNotEmpty()) sections.add(HomePageList("Latest Videos", latestVideos))

            val topRated = try {
                val json = apiGet("/top-rated/?page=1")
                val resp = tryParseJson<ApiSearchResponse>(json)
                resp?.items?.map { it.toSearchResponse(this) } ?: emptyList()
            } catch (e: Exception) { emptyList() }
            if (topRated.isNotEmpty()) sections.add(HomePageList("Top Rated", topRated))

            val mostViewed = try {
                val json = apiGet("/most-viewed/?page=1")
                val resp = tryParseJson<ApiSearchResponse>(json)
                resp?.items?.map { it.toSearchResponse(this) } ?: emptyList()
            } catch (e: Exception) { emptyList() }
            if (mostViewed.isNotEmpty()) sections.add(HomePageList("Most Viewed", mostViewed))

            val latestGalleries = try {
                val json = apiGet("/latest?page=1&type=photos")
                val resp = tryParseJson<ApiSearchResponse>(json)
                resp?.items?.map { it.toSearchResponse(this) } ?: emptyList()
            } catch (e: Exception) { emptyList() }
            if (latestGalleries.isNotEmpty()) sections.add(HomePageList("Latest Galleries", latestGalleries))

            val channels = getChannels()
            if (channels.isNotEmpty()) {
                val channelItems = channels.map { channel ->
                    newMovieSearchResponse(
                        channel.name,
                        "${mainUrl}/category/${channel.id}?page=1",
                        TvType.Others
                    ) { posterUrl = null }
                }
                sections.add(HomePageList("Categories", channelItems, true))
            }
        } else {
            val latest = try {
                val json = apiGet("/latest?page=$page&type=videos")
                val resp = tryParseJson<ApiSearchResponse>(json)
                resp?.items?.map { it.toSearchResponse(this) } ?: emptyList()
            } catch (e: Exception) { emptyList() }
            if (latest.isNotEmpty()) sections.add(HomePageList("Latest Videos", latest))
        }

        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val json = apiGet("/search?q=${query.encodeUri()}&type=videos&page=$page")
        val resp = tryParseJson<ApiSearchResponse>(json) ?: return null
        if (!resp.success) return null
        val items = resp.items?.map { it.toSearchResponse(this) } ?: emptyList()
        return newMovieSearchResponse(query, items) {
            this.page = page
            this.totalPages = resp.totalPages
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/category/")) {
            return null
        }

        val isGallery = url.contains("/gallery/")
        val id = url.substringAfterLast("/").trimEnd('/')
        if (id.isBlank() || !id.all { it.isDigit() }) return null

        return if (isGallery) loadGallery(id.toInt(), url)
        else loadVideo(id.toInt(), url)
    }

    private suspend fun loadVideo(videoId: Int, url: String): LoadResponse? {
        val json = apiGet("/video/$videoId")
        val detail = tryParseJson<ApiVideoDetail>(json) ?: return null
        if (!detail.success) return null
        if (detail.videoUrl == null) return null

        val dataJson = detail.videoUrl!!.toJson()

        return newMovieLoadResponse(
            detail.title?.ifEmpty { "Video #$videoId" } ?: "Video #$videoId",
            url,
            TvType.Others,
            dataJson
        ) {
            posterUrl = detail.thumbnail
            plot = buildString {
                detail.description?.takeIf { it.isNotBlank() }?.let { append("$it\n") }
                detail.categories?.mapNotNull { it["name"] as? String }?.joinToString(", ")?.let {
                    append("Categories: $it\n")
                }
                detail.uploader?.let { append("Uploader: ${it["username"]}\n") }
            }.ifBlank { null }
            detail.tags?.let { tags = it.map { t -> Tag(text = t) } }
            detail.views?.let { addActors(listOf("Views: $it")) }
            detail.duration?.let { addDuration(it) }
            detail.date?.let { year = it.takeLast(4).toIntOrNull() }
        }
    }

    private suspend fun loadGallery(galleryId: Int, url: String): LoadResponse? {
        val json = apiGet("/gallery/$galleryId")
        val detail = tryParseJson<ApiGalleryDetail>(json) ?: return null
        if (!detail.success) return null

        val imageUrls = detail.images ?: emptyList()
        val dataJson = imageUrls.toJson()

        return newMovieLoadResponse(
            detail.title?.ifEmpty { "Gallery #$galleryId" } ?: "Gallery #$galleryId",
            url,
            TvType.Others,
            dataJson
        ) {
            posterUrl = detail.thumbnails?.firstOrNull()
            plot = buildString {
                detail.categories?.mapNotNull { it["name"] as? String }?.joinToString(", ")?.let {
                    append("Categories: $it\n")
                }
                append("${detail.photoCount ?: imageUrls.size} images")
            }
            detail.tags?.let { tags = it.map { t -> Tag(text = t) } }
            detail.views?.let { addActors(listOf("Views: $it")) }
            detail.date?.let { year = it.takeLast(4).toIntOrNull() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val singleUrl = tryParseJson<String>(data)
        if (singleUrl != null) {
            callback(
                newExtractorLink(name, "Video", singleUrl) {
                    quality = Qualities.Unknown.value
                    referer = ""
                }
            )
            return true
        }

        val imageUrls = tryParseJson<List<String>>(data)
        if (imageUrls != null && imageUrls.isNotEmpty()) {
            val isVideo = imageUrls.size == 1 && imageUrls[0].contains(".mp4")
            imageUrls.forEachIndexed { index, imgUrl ->
                callback(
                    newExtractorLink(
                        name,
                        if (isVideo) "Video" else "Image ${index + 1}",
                        imgUrl
                    ) {
                        quality = Qualities.Unknown.value
                        referer = ""
                        isM3u8 = false
                    }
                )
            }
            return true
        }

        return false
    }

    private suspend fun getChannels(): List<ApiChannel> {
        return try {
            val json = apiGet("/channels")
            val resp = tryParseJson<ApiChannelsResponse>(json)
            resp?.channels ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    // ---- Data Classes ----

    private data class ApiListItem(
        val id: Int,
        val slug: String,
        val title: String,
        val type: String?,
        val thumbnail: String?,
        @JsonProperty("previewUrl") val previewUrl: String?,
        val duration: String?,
        val views: String?,
        val url: String,
        @JsonProperty("photoCount") val photoCount: Int?
    ) {
        fun toSearchResponse(provider: SheFreakyProvider): SearchResponse {
            val posterFix = when {
                thumbnail == null -> null
                thumbnail.startsWith("data:") -> null
                else -> thumbnail
            }
            return provider.newMovieSearchResponse(
                title.ifEmpty { "Video #$id" },
                "${provider.mainUrl}/${if (type == "gallery") "gallery" else "video"}/$id",
                TvType.Others
            ) {
                this.posterUrl = posterFix
                if (duration != null) addDuration(duration)
            }
        }
    }

    private data class ApiVideoDetail(
        val success: Boolean,
        val id: Int,
        val slug: String?,
        val title: String?,
        val description: String?,
        val thumbnail: String?,
        @JsonProperty("videoUrl") val videoUrl: String?,
        val duration: String?,
        val views: String?,
        val rating: String?,
        val date: String?,
        val categories: List<Map<String, Any>>?,
        val tags: List<String>?,
        val uploader: Map<String, Any>?,
        val comments: List<Map<String, Any>>?
    )

    private data class ApiGalleryDetail(
        val success: Boolean,
        val id: Int,
        val slug: String?,
        val title: String?,
        val views: String?,
        val date: String?,
        @JsonProperty("photoCount") val photoCount: Int?,
        val images: List<String>?,
        val thumbnails: List<String>?,
        val categories: List<Map<String, Any>>?,
        val tags: List<String>?
    )

    private data class ApiSearchResponse(
        val success: Boolean,
        val query: String?,
        val type: String?,
        val count: Int?,
        val items: List<ApiListItem>?,
        val page: Int?,
        @JsonProperty("totalPages") val totalPages: Int?
    )

    private data class ApiChannelsResponse(
        val success: Boolean,
        val channels: List<ApiChannel>?
    )

    private data class ApiChannel(
        val id: Int,
        val name: String,
        val slug: String,
        val url: String
    )
}
