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

class NaijaTapeProvider : MainAPI() {
    override var mainUrl = "https://naija-blfikyfyb-snowballons-projects.vercel.app/api"
    override var name = "NaijaTape"
    override val supportedTypes = setOf(TvType.Others, TvType.Movie)
    override var lang = "en"
    override val hasMainPage = true

    private suspend fun apiGet(path: String): String {
        return app.get("$mainUrl$path").text
    }

    private fun slugFromUrl(url: String): String {
        return url.trimEnd('/').substringAfterLast("/")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = apiGet("/latest?page=$page")
        val response = tryParseJson<ApiListResponse>(json)

        val posts = response?.posts?.map { it.toSearchResponse(this) } ?: emptyList()

        return newHomePageResponse(
            listOf(
                HomePageList("Latest", posts, true)
            )
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val json = apiGet("/search?q=${query.encodeUri()}&page=$page")
        val response = tryParseJson<ApiSearchResponse>(json)

        return response?.posts?.map { it.toSearchResponse(this) }?.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = slugFromUrl(url)
        if (slug.isBlank()) return null

        val json = apiGet("/post/$slug")
        val detail = tryParseJson<ApiPostDetail>(json) ?: return null
        if (!detail.success) return null

        val videoUrls = detail.videoUrls ?: emptyList()
        val dataJson = videoUrls.toJson()

        return newMovieLoadResponse(
            detail.title,
            url,
            TvType.Movie,
            dataJson
        ) {
            posterUrl = detail.thumbnail
            plot = buildString {
                detail.categories?.joinToString(", ")?.let { append("Categories: $it\n") }
                detail.tags?.joinToString(", ")?.let { append("Tags: $it") }
            }.ifBlank { null }
            detail.date?.let { year = extractYear(it) }
            val allTags = (detail.categories ?: emptyList()) + (detail.tags ?: emptyList())
            tags = allTags.ifEmpty { null }
        }
    }

    private fun extractYear(dateStr: String?): Int? {
        if (dateStr == null || dateStr.length < 4) return null
        return dateStr.substring(0, 4).toIntOrNull()
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

        return true
    }

    private data class ApiListResponse(
        val success: Boolean,
        val posts: List<ApiPostCard>?,
        val page: Int?,
        @JsonProperty("totalPages") val totalPages: Int?
    )

    private data class ApiPostCard(
        val slug: String,
        val title: String,
        val thumbnail: String?,
        val date: String?,
        val categories: List<String>?
    ) {
        fun toSearchResponse(provider: NaijaTapeProvider): SearchResponse {
            return provider.newMovieSearchResponse(
                title,
                "${provider.mainUrl}/post/$slug",
                TvType.Others
            ) {
                this.posterUrl = thumbnail
            }
        }
    }

    private data class ApiPostDetail(
        val success: Boolean,
        val slug: String?,
        val title: String,
        val thumbnail: String?,
        val date: String?,
        @JsonProperty("videoUrls") val videoUrls: List<String>?,
        val images: List<String>?,
        val categories: List<String>?,
        val tags: List<String>?
    )

    private data class ApiSearchResponse(
        val success: Boolean,
        val query: String?,
        val posts: List<ApiPostCard>?,
        val page: Int?,
        @JsonProperty("totalPages") val totalPages: Int?
    )
}
