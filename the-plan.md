 Here's the plan:
---
NaijaTape CloudStream Extension — Implementation Plan
Step 1: Strip down to essentials
Remove all existing provider directories and unneeded files:
rm -rf DailymotionProvider InternetArchiveProvider InvidiousProvider TwitchProvider YoutubeProvider
Keep: build.gradle.kts, settings.gradle.kts, repo.json, gradle/, gradlew, gradlew.bat, gradle.properties, .gitignore, .github/workflows/build.yml, README.md
Step 2: Create provider structure
NaijaTapeProvider/
├── build.gradle.kts
└── src/main/
    ├── AndroidManifest.xml
    └── kotlin/recloudstream/
        ├── NaijaTapePlugin.kt
        └── NaijaTapeProvider.kt
Step 3: build.gradle.kts — Provider metadata
version = 1
cloudstream {
    description = "Watch content from NaijaTape.com"
    authors = listOf("snowballons")
    status = 1
    tvTypes = listOf("Others")
    iconUrl = "https://www.google.com/s2/favicons?domain=naijatape.com&sz=%size%"
    isCrossPlatform = true
}
Step 4: NaijaTapePlugin.kt — Plugin entry point
Trivial — registers NaijaTapeProvider via registerMainAPI().
Step 5: NaijaTapeProvider.kt — Core provider (the meat)
API base: Configurable via mainUrl, defaulting to https://naijatape-api.vercel.app/api
Data flow:
Method
getMainPage(page)
search(query, page)
load(url)
loadLinks(data, callback)
Data classes:
// API envelope
data class ApiListResponse(val success: Boolean, val posts: List<ApiPostCard>?, val page: Int?, val totalPages: Int?)
data class ApiPostCard(val slug: String, val title: String, val thumbnail: String?, val date: String?, val categories: List<String>?)
data class ApiPostDetail(val success: Boolean, val slug: String, val title: String, val thumbnail: String?, val date: String?, val videoUrls: List<String>?, val images: List<String>?, val categories: List<String>?, val type: String?)
data class ApiSearchResponse(val success: Boolean, val query: String?, val posts: List<ApiPostCard>?, val page: Int?, val totalPages: Int?)
getMainPage / search pagination: The API returns totalPages, the CS3 page parameter maps directly.
load() URL format: https://naijatape-api.vercel.app/api/post/slug-here passed as the URL. Extract slug from the URL path. Fetch our API, embed videoUrls as JSON in the data parameter of newMovieLoadResponse.
loadLinks(): Deserialize data string as List<String> of video URLs. Call newExtractorLink() for each. Mark quality as Qualities.Unknown (direct MP4, quality unknown from API).
Thumbnails: Use thumbnail field as posterUrl in search responses and load response.
Categories: Map categories[0] as a tag in load response.
Step 6: Remove other providers from CI
- No changes needed to settings.gradle.kts (auto-discovery picks up only remaining directories)
- Remove other provider dirs — they won't be built
- repo.json stays the same (points to builds/plugins.json)
Step 7: Build verification
chmod +x gradlew
./gradlew make
This produces NaijaTapeProvider/build/NaijaTapeProvider.cs3 — the plugin file.
Step 8: Install on device
Transfer .cs3 to Android device → open in CloudStream → it auto-installs.
---
