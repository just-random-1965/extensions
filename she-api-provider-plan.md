# She's Freaky API — CloudStream Extension Plan

## Overview

Build a CloudStream 3 extension that uses the deployed REST API at `https://she-api.vercel.app/api` as the backend. The extension will provide videos (and optionally gallery images) from shesfreaky.com through CloudStream's native interface — home page, search, categories, tags, and detail views.

---

## 1. Project Context

### Existing Project Structure

```
extensions/
├── build.gradle.kts          # Root build — auto-includes subprojects
├── settings.gradle.kts        # Auto-discovers provider dirs with build.gradle.kts
├── gradle.properties
├── repo.json
├── NaijaTapeProvider/         # Reference implementation
│   ├── build.gradle.kts
│   └── src/main/kotlin/recloudstream/
│       ├── NaijaTapePlugin.kt
│       └── NaijaTapeProvider.kt
```

### Key Patterns from NaijaTapeProvider (reference)

| Aspect | Pattern |
|---|---|
| Plugin class | `@CloudstreamPlugin class XPlugin : BasePlugin()` — calls `registerMainAPI(XProvider())` |
| Provider class | `class XProvider : MainAPI()` — sets `mainUrl`, `name`, `supportedTypes`, `lang`, `hasMainPage` |
| HTTP client | `app.get(url).text` (NiceHttp) |
| JSON parsing | `tryParseJson<DataClass>(json)` / `.toJson()` (Jackson) |
| Build config | `version = 1` in module `build.gradle.kts` |
| Icon URL | `https://www.google.com/s2/favicons?domain=DOMAIN&sz=%size%` |

---

## 2. API Endpoint Mapping

| CloudStream Function | She's Freaky API Endpoint | Notes |
|---|---|---|
| `getMainPage(page, request)` | `GET /api/latest?page=X&type=videos` | Latest videos |
| `getMainPage` (2nd section) | `GET /api/featured?page=1` | Featured videos |
| `getMainPage` (3rd section) | `GET /api/top-rated/?page=X` | Top rated videos |
| `getMainPage` (4th section) | `GET /api/most-viewed/?page=X` | Most viewed videos |
| `getMainPage` (5th section) | `GET /api/latest?page=X&type=photos` | Latest galleries |
| `search(query, page)` | `GET /api/search?q=QUERY&type=videos&page=X` | Search |
| `load(url)` | `GET /api/video/ID` | Video detail |
| `load(url)` (gallery) | `GET /api/gallery/ID` | Gallery detail |
| `loadLinks(data, ...)` | Uses `videoUrl` from `/api/video/:id` | Extract video links |
| Categories | `GET /api/channels` → then `/api/category/ID` | Channel listing |

---

## 3. API Response Shapes

### Listing item (videos & galleries)

```json
{
  "success": true,
  "items": [
    {
      "id": 1185786,
      "slug": "bonnet-bop",
      "title": "Bonnet bop",
      "type": "video",
      "thumbnail": "https://cdn2.shesfreaky.com/thumbs/...jpg",
      "previewUrl": "https://c89d6d4fd0.mjedge.net/videos/...mp4?expires=...&token=...",
      "duration": "01:06",
      "views": "2,969 views",
      "url": "https://www.shesfreaky.com/video/bonnet-bop-1185786.html"
    },
    {
      "id": 1185780,
      "slug": "natural-latina",
      "title": "Natural latina",
      "type": "gallery",
      "thumbnail": "data:image/png;base64,...",
      "views": "198 views",
      "url": "https://www.shesfreaky.com/gallery/natural-latina-1185780.html",
      "photoCount": 42
    }
  ],
  "page": 1,
  "totalPages": 500
}
```

### Video detail

```json
{
  "success": true,
  "id": 1185786,
  "slug": "bonnet-bop",
  "title": "Bonnet bop",
  "description": "...",
  "thumbnail": "https://cdn2.shesfreaky.com/thumbs/...mp4-1.jpg",
  "videoUrl": "https://c89d6d4fd0.mjedge.net/videos/...mp4?expires=...&token=...",
  "duration": "01:06",
  "views": "2969",
  "rating": "Thanks! (197)",
  "date": "2026-05-20",
  "categories": [
    { "id": 102, "name": "Masturbation", "slug": "masturbation", "url": "/channels/102/masturbation/page1.html" },
    { "id": 118, "name": "Thot", "slug": "thot", "url": "/channels/118/thot/page1.html" }
  ],
  "tags": ["ass", "pussy", "tits", "mouf"],
  "uploader": {
    "username": "Kidcumbacc",
    "profileUrl": "/profile/kidcumbacc/",
    "avatar": null
  },
  "comments": []
}
```

### Gallery detail

```json
{
  "success": true,
  "id": 1185806,
  "slug": "natural-latina",
  "title": "Natural latina",
  "views": "2969",
  "date": "2026-05-20",
  "photoCount": 4,
  "images": [
    "//cdn2.shesfreaky.com/galleries/5100906a0d6e9a576b0/6a0d6ea492894.jpg",
    "//cdn2.shesfreaky.com/galleries/5100906a0d6e9a576b0/6a0d6ea55d0fe.png"
  ],
  "thumbnails": [
    "//cdn2.shesfreaky.com/galleries/5100906a0d6e9a576b0/thumbs/6a0d6ea492894.jpg",
    "//cdn2.shesfreaky.com/galleries/5100906a0d6e9a576b0/thumbs/6a0d6ea55d0fe.png"
  ],
  "categories": [
    { "id": 65, "name": "Latina", "slug": "latina" },
    { "id": 86, "name": "Social Media", "slug": "social-media" }
  ],
  "tags": ["maria bejarano maria latina sexy teen thick"]
}
```

### Channels

```json
{
  "success": true,
  "channels": [
    { "id": 64, "name": "Black", "slug": "black", "url": "/channels/64/black/page1.html" }
  ]
}
```

### Search

```json
{
  "success": true,
  "query": "black",
  "type": "videos",
  "count": 68,
  "items": [...],
  "page": 1,
  "totalPages": 50
}
```

### Tags

```json
{
  "success": true,
  "tags": [
    { "name": "Amateur", "slug": "amateur", "count": 0 },
    { "name": "Anal", "slug": "anal", "count": 0 },
    { "name": "Asian", "slug": "asian", "count": 0 },
    { "name": "Ass", "slug": "ass", "count": 0 },
    { "name": "Big Booty", "slug": "big%20booty", "count": 0 },
    { "name": "Black", "slug": "black", "count": 0 },
    { "name": "Ebony", "slug": "ebony", "count": 0 },
    { "name": "Thick", "slug": "thick", "count": 0 }
  ]
}
```

**Note:** Tags are extracted from the homepage footer's popular search links. Each tag has `count: 0` since counts are not available from this source.

---

## 4. Step-by-Step Implementation

### Step 1: Create Provider Module Directory

```
mkdir -p SheFreakyProvider/src/main/kotlin/recloudstream
```

### Step 2: Create `build.gradle.kts`

Same pattern as NaijaTapeProvider. Set:
- `version = 1`
- `description = "Watch content from shesfreaky.com"`
- `authors = listOf("your-username")`
- `status = 1` (1 = planned/untested, 3 = working, 4 = complete)
- `tvTypes = listOf("Others")` (since this isn't standard movies/series)
- `iconUrl = "https://www.google.com/s2/favicons?domain=shesfreaky.com&sz=%size%"`
- `isCrossPlatform = true`

### Step 3: Create `AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

### Step 4: Create Plugin Entry Point

`SheFreakyPlugin.kt`:
```kotlin
package recloudstream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SheFreakyPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(SheFreakyProvider())
    }
}
```

### Step 5: Create Main Provider — Scaffold

`SheFreakyProvider.kt` — start with:

```kotlin
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class SheFreakyProvider : MainAPI() {
    override var mainUrl = "https://she-api.vercel.app/api"
    override var name = "She's Freaky"
    override val supportedTypes = setOf(TvType.Others)
    override var lang = "en"
    override val hasMainPage = true
    override val hasSearchSupport = true

    // Base API call helper
    private suspend fun apiGet(path: String): String {
        return app.get("$mainUrl$path").text
    }
}
```

### Step 6: Define JSON Data Classes

Map the API responses to Kotlin data classes:

```kotlin
// Listing item from /api/latest, /api/search, etc.
private data class ApiListItem(
    val id: Int,
    val slug: String,
    val title: String,
    val type: String?,              // "video" or "gallery"
    val thumbnail: String?,
    @JsonProperty("previewUrl") val previewUrl: String?,
    val duration: String?,
    val views: String?,
    val url: String,
    @JsonProperty("photoCount") val photoCount: Int?
)

// Video detail from /api/video/:id
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

// Gallery detail from /api/gallery/:id
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

// Search response
private data class ApiSearchResponse(
    val success: Boolean,
    val query: String?,
    val type: String?,
    val count: Int?,
    val items: List<ApiListItem>?,
    val page: Int?,
    @JsonProperty("totalPages") val totalPages: Int?
)

// Channels response
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
```

### Step 7: Implement `getMainPage()` — Home Page Sections

CloudStream supports multiple `HomePageList` sections. Use `ApiListItem` from the listing endpoint and convert via `toSearchResponse()`. The `isHorizontal` flag controls card layout (false = vertical grid on phones, true = horizontal row).

```kotlin
override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    val sections = mutableListOf<HomePageList>()

    if (page == 1) {
        // Section 1: Featured (only on page 1)
        val featured = try {
            val json = apiGet("/featured?page=1")
            val resp = tryParseJson<ApiSearchResponse>(json)
            resp?.items?.map { it.toSearchResponse(this) } ?: emptyList()
        } catch (e: Exception) { emptyList() }
        if (featured.isNotEmpty()) sections.add(HomePageList("Featured", featured))

        // Section 2: Latest Videos
        val latestVideos = try {
            val json = apiGet("/latest?page=1&type=videos")
            val resp = tryParseJson<ApiSearchResponse>(json)
            resp?.items?.map { it.toSearchResponse(this) } ?: emptyList()
        } catch (e: Exception) { emptyList() }
        if (latestVideos.isNotEmpty()) sections.add(HomePageList("Latest Videos", latestVideos))

        // Section 3: Top Rated
        val topRated = try {
            val json = apiGet("/top-rated/?page=1")
            val resp = tryParseJson<ApiSearchResponse>(json)
            resp?.items?.map { it.toSearchResponse(this) } ?: emptyList()
        } catch (e: Exception) { emptyList() }
        if (topRated.isNotEmpty()) sections.add(HomePageList("Top Rated", topRated))

        // Section 4: Most Viewed
        val mostViewed = try {
            val json = apiGet("/most-viewed/?page=1")
            val resp = tryParseJson<ApiSearchResponse>(json)
            resp?.items?.map { it.toSearchResponse(this) } ?: emptyList()
        } catch (e: Exception) { emptyList() }
        if (mostViewed.isNotEmpty()) sections.add(HomePageList("Most Viewed", mostViewed))

        // Section 5: Latest Galleries
        val galleries = try {
            val json = apiGet("/latest?page=1&type=photos")
            val resp = tryParseJson<ApiSearchResponse>(json)
            resp?.items?.map { it.toSearchResponse(this) } ?: emptyList()
        } catch (e: Exception) { emptyList() }
        if (galleries.isNotEmpty()) sections.add(HomePageList("Latest Galleries", galleries))
    } else {
        // Page 2+: Latest Videos (paginated)
        val latest = try {
            val json = apiGet("/latest?page=$page&type=videos")
            val resp = tryParseJson<ApiSearchResponse>(json)
            resp?.items?.map { it.toSearchResponse(this) } ?: emptyList()
        } catch (e: Exception) { emptyList() }
        if (latest.isNotEmpty()) sections.add(HomePageList("Latest Videos", latest))
    }

    return newHomePageResponse(sections)
}
```

### Step 8: Create `ApiListItem.toSearchResponse()`

```kotlin
private fun ApiListItem.toSearchResponse(provider: SheFreakyProvider): SearchResponse {
    val posterFix = when {
        thumbnail == null -> null
        thumbnail.startsWith("data:") -> null  // Skip base64 placeholder images
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
```

### Step 9: Implement `search()`

```kotlin
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
```

### Step 10: Implement `load()` — Video & Gallery Detail

The URL format used in `toSearchResponse` determines what `/api/video/:id` or `/api/gallery/:id` is called.

```kotlin
override suspend fun load(url: String): LoadResponse? {
    // URL is like "https://she-api.vercel.app/api/video/1185786"
    // or "https://she-api.vercel.app/api/gallery/1185806"
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

    // Store videoUrl in data string for loadLinks
    val dataJson = listOfNotNull(detail.videoUrl).toJson()

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

    // Store image URLs in data string for loadLinks
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
```

### Step 11: Implement `loadLinks()` — Video URLs & Gallery Images

When CloudStream calls `loadLinks()`, the `data` string is the JSON we stored in `load()`. Check if it's a single video URL or an array of image URLs.

```kotlin
override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    // Try as video URL first (single string)
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

    // Try as list of image URLs (gallery)
    val imageUrls = tryParseJson<List<String>>(data)
    if (imageUrls != null && imageUrls.isNotEmpty()) {
        imageUrls.forEachIndexed { index, imgUrl ->
            callback(
                newExtractorLink(name, "Image ${index + 1}", imgUrl) {
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
```

### Step 12: Add Category Browsing (Optional Enhancement)

Add a method to fetch channels and load category pages. This can be wired into the home page or accessed via search.

```kotlin
private suspend fun getChannels(): List<ApiChannel> {
    return try {
        val json = apiGet("/channels")
        val resp = tryParseJson<ApiChannelsResponse>(json)
        resp?.channels ?: emptyList()
    } catch (e: Exception) { emptyList() }
}

// Add channel browsing as a home page section (in getMainPage):
// Section: Categories (horizontal scrolling channel list)
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
```

### Step 13: Handle `load()` for Category URLs

If a category URL is passed to `load()`, fetch the category listing page:

```kotlin
// In load(), add this before the video/gallery check:
if (url.contains("/category/")) {
    val parts = url.substringAfter("/category/").substringBefore("?").split("/")
    val catId = parts.firstOrNull()?.toIntOrNull() ?: return null
    val page = (url.substringAfter("page=").substringBefore("&").toIntOrNull() ?: 1)
    return loadCategory(catId, page, url)
}

// New method:
private suspend fun loadCategory(catId: Int, page: Int, url: String): LoadResponse? {
    val json = apiGet("/category/$catId?page=$page")
    val resp = tryParseJson<ApiSearchResponse>(json) ?: return null
    val items = resp.items ?: emptyList()
    // Return the first item as the main content
    // Category browsing via search results is better UX
    return null // Skip for now — categories work via search/navigation
}
```

---

## 5. Backend Status (Post-Fixes)

The API backend was audited and fixed. Current state of all fields:

| Field | Status | Notes |
|---|---|---|
| `title` | ✅ Works | Extracted from `<h2>` |
| `slug` | ✅ Works | Extracted from canonical link, falls back to `<title>` tag |
| `thumbnail` | ✅ Works | Extracted from `video[poster]`, `og:image`, or `#content-thumbs img` |
| `description` | ✅ Works | Extracted from `#content-main` |
| `videoUrl` | ✅ Works | Extracted from `<video><source>` or `data-preview` |
| `views` / `date` / `duration` | ✅ Works | Extracted from metadata `<p>` by regex |
| `rating` | ✅ Works | Extracted from `#rating-thumbs` |
| `categories` | ✅ Works | Extracted from `#content-main a[href*="/channels/"]` |
| `tags` | ✅ Works | Extracted from `#content-main a[href*="/search/"]` |
| `uploader` | ✅ Works | Extracted from `a.redlinks[href*="/profile/"]` |
| `comments` | ❌ Not possible | Loaded dynamically via AJAX after page render |
| `images` (gallery) | ✅ Works | Extracted from `#gallery-container a[href*="galleries"]` |
| `thumbnails` (gallery) | ✅ Works | Extracted from `#gallery-container img[src*="galleries/thumbs"]` |
| `tags` endpoint | ✅ Works | Now scrapes homepage for popular search links |
| Placeholder thumbnails | ⚠️ Site issue | Gallery cards use base64 placeholders — filter out `data:` URIs |

### Remaining Caveats for the Extension

- **Comments always empty** — not statically available, ignore
- **Gallery thumbnails may still be base64 placeholders** — filter `thumbnail.startsWith("data:")` in `toSearchResponse()`
- **CDN video URLs expire ~10s after fetch** — set `Cache-Control: no-cache` on video detail in the API (already done)

---

## 6. Build & Test (dont do it locally, lets use github ci/cd )
### Publishing via GitHub

The `repo.json` points to `pluginLists` URLs. When ready:
1. Push to GitHub
2. Set up GitHub Actions workflow (see `.github/` in the extensions project)
3. The workflow builds all modules and deploys `plugins.json`
4. Update `repo.json` with the correct raw URL

---

## 7. Checklist

- [ ] Step 1: Create `SheFreakyProvider/` directory structure
- [ ] Step 2: Create `build.gradle.kts` with module config
- [ ] Step 3: Create `AndroidManifest.xml`
- [ ] Step 4: Create `SheFreakyPlugin.kt` entry point
- [ ] Step 5: Create `SheFreakyProvider.kt` scaffold (class, base URL, apiGet helper)
- [ ] Step 6: Define all JSON data classes (ApiListItem, ApiVideoDetail, etc.)
- [ ] Step 7: Implement `toSearchResponse()` converter
- [ ] Step 8: Implement `getMainPage()` with multiple sections
- [ ] Step 9: Implement `search()`
- [ ] Step 10: Implement `load()` for both video and gallery
- [ ] Step 11: Implement `loadLinks()` for video URLs and gallery images
- [ ] Step 12 (optional): Add category browsing
- [ ] Step 13: Build with `./gradlew :SheFreakyProvider:build`
- [ ] Step 14: Test APK in CloudStream on Android
- [ ] Step 15: Push to GitHub and set up CI/CD
