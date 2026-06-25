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
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.newExtractorLink

class EpornerProvider : MainAPI() {
    /**
     * Base URL of the eporner-api deployment.
     * Update this to match your actual deployment URL.
     */
    override var mainUrl = "https://epo-api.netlify.app/api"
    override var name = "EPORNER"
    override val supportedTypes = setOf(TvType.Others)
    override var lang = "en"
    override val hasMainPage = true

    // ─── HTTP Helper ───────────────────────────────────────────────────────

    private suspend fun apiGet(path: String): String {
        return app.get("$mainUrl$path").text
    }

    // ─── ID Extraction ─────────────────────────────────────────────────────

    /**
     * Extract the video ID from a load URL.
     * URL format: "${mainUrl}/video/{id}"
     */
    private fun idFromUrl(url: String): String {
        return url.trimEnd('/').substringAfterLast("/")
    }

    // ─── Main Page ─────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sections = mutableListOf<HomePageList>()

        // Fetch latest videos
        val latest = try {
            val json = apiGet("/latest?page=$page")
            val resp = tryParseJson<ApiListResponse>(json)
            resp?.videos?.map { it.toSearchResponse(this) } ?: emptyList()
        } catch (e: Exception) { emptyList() }

        if (latest.isNotEmpty()) {
            sections.add(HomePageList("Latest Videos", latest, true))
        }

        // On page 1, also fetch popular and top-rated sections for variety
        if (page == 1) {
            val popular = try {
                val json = apiGet("/popular?page=1&per_page=10")
                val resp = tryParseJson<ApiListResponse>(json)
                resp?.videos?.map { it.toSearchResponse(this) } ?: emptyList()
            } catch (e: Exception) { emptyList() }
            if (popular.isNotEmpty()) {
                sections.add(HomePageList("Most Popular", popular))
            }

            val topRated = try {
                val json = apiGet("/top-rated?page=1&per_page=10")
                val resp = tryParseJson<ApiListResponse>(json)
                resp?.videos?.map { it.toSearchResponse(this) } ?: emptyList()
            } catch (e: Exception) { emptyList() }
            if (topRated.isNotEmpty()) {
                sections.add(HomePageList("Top Rated", topRated))
            }

            val trending = try {
                val json = apiGet("/trending?page=1&per_page=10")
                val resp = tryParseJson<ApiListResponse>(json)
                resp?.videos?.map { it.toSearchResponse(this) } ?: emptyList()
            } catch (e: Exception) { emptyList() }
            if (trending.isNotEmpty()) {
                sections.add(HomePageList("Trending", trending))
            }
        }

        return newHomePageResponse(sections)
    }

    // ─── Search ────────────────────────────────────────────────────────────

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val json = apiGet("/search?q=${query.encodeUri()}&page=$page")
        val resp = tryParseJson<ApiSearchResponse>(json)
        if (resp?.success == false) return null

        return resp?.videos?.map { it.toSearchResponse(this) }?.toNewSearchResponseList()
    }

    // ─── Load (Video Detail) ───────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val id = idFromUrl(url)
        if (id.isBlank() || !id.all { it.isLetterOrDigit() }) return null

        val json = apiGet("/video/$id")
        val detail = tryParseJson<ApiVideoDetail>(json) ?: return null
        if (!detail.success) return null

        // Serialize sources to JSON to pass through to loadLinks
        val sources = detail.sources ?: emptyList()
        val dataJson = sources.toJson()

        return newMovieLoadResponse(
            detail.title,
            url,
            TvType.Others,
            dataJson
        ) {
            // Poster: use the default thumbnail, fallback to medium
            posterUrl = detail.thumbnails?.defaultThumb
                ?: detail.thumbnails?.medium

            // Plot summary with metadata
            plot = buildString {
                detail.keywords?.take(10)?.joinToString(", ")?.let {
                    append("Tags: $it\n")
                }
                detail.uploader?.let {
                    append("Uploader: ${it.username}\n")
                }
                detail.views?.let { append("Views: $it\n") }
                detail.rating?.let { append("Rating: $it/5\n") }
                detail.duration?.let { append("Duration: $it\n") }
                detail.added?.let { append("Added: $it") }
            }.ifBlank { null }

            // Year from the "added" date (format: "YYYY-MM-DD HH:MM:SS")
            detail.added?.let {
                if (it.length >= 4) year = it.substring(0, 4).toIntOrNull()
            }

            // Tags from keywords
            detail.keywords?.let { tags = it.take(20).ifEmpty { null } }
        }
    }

    // ─── Load Links (Video Sources) ────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = tryParseJson<List<VideoSourceData>>(data) ?: return false

        sources.forEach { source ->
            val qualityLabel = source.quality ?: "Video"
            val qualityValue = when {
                source.height != null && source.height >= 2160 -> Qualities.FourK.value
                source.height != null && source.height >= 1080 -> Qualities.VeryHigh.value
                source.height != null && source.height >= 720 -> Qualities.High.value
                source.height != null && source.height >= 480 -> Qualities.Medium.value
                source.height != null && source.height >= 360 -> Qualities.Low.value
                else -> Qualities.Unknown.value
            }

            callback(
                newExtractorLink(name, qualityLabel, source.url) {
                    quality = qualityValue
                    referer = "https://www.eporner.com"
                }
            )
        }

        return sources.isNotEmpty()
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Data Classes (mapping to eporner-api JSON response shapes)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Response from /api/latest, /api/popular, /api/top-rated, /api/trending
     */
    private data class ApiListResponse(
        val success: Boolean,
        val videos: List<ApiVideoCard>?,
        val page: Int?,
        val perPage: Int?,
        val totalCount: Int?,
        val totalPages: Int?
    )

    /**
     * Response from /api/search
     */
    private data class ApiSearchResponse(
        val success: Boolean,
        val query: String?,
        val count: Int?,
        val videos: List<ApiVideoCard>?,
        val page: Int?,
        val perPage: Int?,
        val totalCount: Int?,
        val totalPages: Int?
    )

    /**
     * Thumbnails object from normalized video responses.
     */
    private data class ApiVideoThumbnails(
        val small: String?,
        val medium: String?,
        val big: String?,
        @JsonProperty("default") val defaultThumb: String?,
        val all: List<String>?
    )

    /**
     * A video in list responses (latest, search, popular, etc.).
     */
    private data class ApiVideoCard(
        val id: String,
        val title: String,
        val keywords: List<String>?,
        val views: Int?,
        val rating: String?,
        val url: String?,
        val embed: String?,
        val added: String?,
        @JsonProperty("duration_sec") val durationSec: Int?,
        val duration: String?,
        val thumbnails: ApiVideoThumbnails?
    ) {
        fun toSearchResponse(provider: EpornerProvider): SearchResponse {
            return provider.newMovieSearchResponse(
                title,
                "${provider.mainUrl}/video/$id",
                TvType.Others
            ) {
                this.posterUrl = thumbnails?.defaultThumb
                    ?: thumbnails?.medium
            }
        }
    }

    /**
     * A video source object from the XHR API scraping.
     * Passed as JSON from load() to loadLinks().
     */
    private data class VideoSourceData(
        val url: String,
        val quality: String?,
        val format: String?,
        val height: Int?
    )

    /**
     * Uploader info from the video detail endpoint.
     */
    private data class UploaderInfo(
        val username: String,
        val profileUrl: String?
    )

    /**
     * Response from /api/video/:id
     * Combines normalized video metadata with scraped sources and uploader info.
     */
    private data class ApiVideoDetail(
        val success: Boolean,
        val id: String?,
        val title: String,
        val keywords: List<String>?,
        val views: Int?,
        val rating: String?,
        val url: String?,
        val embed: String?,
        val added: String?,
        @JsonProperty("duration_sec") val durationSec: Int?,
        val duration: String?,
        val thumbnails: ApiVideoThumbnails?,
        val sources: List<VideoSourceData>?,
        val uploader: UploaderInfo?
    )
}
