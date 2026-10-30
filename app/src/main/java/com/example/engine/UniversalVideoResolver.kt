package com.example.engine

import android.net.Uri
import com.example.model.DetectedPlatform
import com.example.model.PlaybackEngineMode
import com.example.model.ResolvedVideoTarget
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class UniversalVideoResolver {

    companion object {
        const val MOBILE_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        const val DESKTOP_CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val URL_EXTRACT_REGEX = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
        private val YOUTUBE_ID_REGEX = Regex(
            """(?:youtu\.be/|youtube(?:-nocookie)?\.com/(?:watch\?(?:.*&)?v=|shorts/|embed/|live/|v/))([\w-]{11})""",
            RegexOption.IGNORE_CASE
        )
        private val DIRECT_EXT_REGEX = Regex(
            """\.(mp4|m3u8|webm|mkv|mov|3gp|mpd|m4v|ts)(\?.*|#.*)?$""",
            RegexOption.IGNORE_CASE
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun normalizeInputToUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        URL_EXTRACT_REGEX.find(trimmed)?.value?.let { return it.trimEnd('.', ',', ')', ']') }
        if (trimmed.contains('.') && !trimmed.contains(' ')) {
            return "https://$trimmed"
        }
        val query = URLEncoder.encode(trimmed, "UTF-8")
        return "https://m.youtube.com/results?search_query=$query"
    }

    suspend fun resolve(rawInput: String): ResolvedVideoTarget = withContext(Dispatchers.IO) {
        val initialUrl = normalizeInputToUrl(rawInput) ?: rawInput.trim()
        val (unwrappedUrl, contentType, htmlSample) = probeUrlAndFollowRedirects(initialUrl)
        val effectiveUrl = convertCloudSharingUrl(unwrappedUrl)
        val lowerUrl = effectiveUrl.lowercase()

        // 1. Direct media files or HLS/DASH manifests
        if (DIRECT_EXT_REGEX.containsMatchIn(lowerUrl) ||
            contentType.startsWith("video/") ||
            contentType.contains("mpegurl") ||
            contentType.contains("dash+xml")
        ) {
            return@withContext ResolvedVideoTarget(
                originalInput = rawInput,
                resolvedUrl = effectiveUrl,
                platform = DetectedPlatform.DIRECT_MEDIA,
                recommendedEngine = PlaybackEngineMode.DIRECT_STREAM,
                primaryStreamUrl = effectiveUrl,
                requestHeaders = mapOf("User-Agent" to MOBILE_CHROME_UA)
            )
        }

        // 2. YouTube (Watch, Shorts, Live, Music, Embed)
        val ytId = YOUTUBE_ID_REGEX.find(effectiveUrl)?.groupValues?.getOrNull(1)
            ?: YOUTUBE_ID_REGEX.find(initialUrl)?.groupValues?.getOrNull(1)
        if (ytId != null || lowerUrl.contains("youtube.com") || lowerUrl.contains("youtu.be")) {
            if (ytId != null) {
                return@withContext ResolvedVideoTarget(
                    originalInput = rawInput,
                    resolvedUrl = effectiveUrl,
                    platform = DetectedPlatform.YOUTUBE,
                    recommendedEngine = PlaybackEngineMode.SMART_EMBED,
                    embedHtml = buildYouTubeEmbedHtml(ytId),
                    embedBaseUrl = "https://www.youtube-nocookie.com",
                    isolatorPageUrl = "https://m.youtube.com/watch?v=$ytId",
                    youtubeId = ytId
                )
            } else {
                return@withContext ResolvedVideoTarget(
                    originalInput = rawInput,
                    resolvedUrl = effectiveUrl,
                    platform = DetectedPlatform.YOUTUBE,
                    recommendedEngine = PlaybackEngineMode.WEB_ISOLATOR,
                    isolatorPageUrl = effectiveUrl
                )
            }
        }

        // 3. TikTok
        if (lowerUrl.contains("tiktok.com")) {
            val directMp4 = resolveTikTokDirectMp4(effectiveUrl)
            val videoId = Regex("""/video/(\d+)""").find(effectiveUrl)?.groupValues?.getOrNull(1)
            val embedHtml = videoId?.let { id ->
                buildFullBleedIframeHtml("https://www.tiktok.com/embed/v2/$id?lang=en-US")
            }
            return@withContext ResolvedVideoTarget(
                originalInput = rawInput,
                resolvedUrl = effectiveUrl,
                platform = DetectedPlatform.TIKTOK,
                recommendedEngine = if (directMp4 != null) {
                    PlaybackEngineMode.DIRECT_STREAM
                } else {
                    PlaybackEngineMode.WEB_ISOLATOR
                },
                primaryStreamUrl = directMp4,
                requestHeaders = mapOf(
                    "Referer" to "https://www.tiktok.com/",
                    "User-Agent" to MOBILE_CHROME_UA
                ),
                embedHtml = embedHtml,
                embedBaseUrl = "https://www.tiktok.com",
                isolatorPageUrl = effectiveUrl
            )
        }

        // 4. X / Twitter
        if (lowerUrl.contains("twitter.com") || lowerUrl.contains("x.com")) {
            val statusId = Regex("""/status/(\d+)""").find(effectiveUrl)?.groupValues?.getOrNull(1)
            val directMp4 = statusId?.let { resolveTwitterDirectMp4(it) }
            val embedHtml = statusId?.let { id ->
                buildFullBleedIframeHtml(
                    "https://platform.twitter.com/embed/Tweet.html?id=$id&theme=dark&dnt=true"
                )
            }
            return@withContext ResolvedVideoTarget(
                originalInput = rawInput,
                resolvedUrl = effectiveUrl,
                platform = DetectedPlatform.TWITTER_X,
                recommendedEngine = if (directMp4 != null) {
                    PlaybackEngineMode.DIRECT_STREAM
                } else {
                    PlaybackEngineMode.WEB_ISOLATOR
                },
                primaryStreamUrl = directMp4,
                requestHeaders = mapOf("User-Agent" to MOBILE_CHROME_UA),
                embedHtml = embedHtml,
                embedBaseUrl = "https://platform.twitter.com",
                isolatorPageUrl = effectiveUrl
            )
        }

        // 5. Reddit
        if (lowerUrl.contains("reddit.com") || lowerUrl.contains("redd.it")) {
            val redditStream = resolveRedditStream(effectiveUrl)
            return@withContext ResolvedVideoTarget(
                originalInput = rawInput,
                resolvedUrl = effectiveUrl,
                platform = DetectedPlatform.REDDIT,
                recommendedEngine = if (redditStream != null) {
                    PlaybackEngineMode.DIRECT_STREAM
                } else {
                    PlaybackEngineMode.WEB_ISOLATOR
                },
                primaryStreamUrl = redditStream,
                requestHeaders = mapOf("User-Agent" to MOBILE_CHROME_UA),
                isolatorPageUrl = effectiveUrl
            )
        }

        // 6. Twitch (Live Channels, VODs, Clips)
        if (lowerUrl.contains("twitch.tv")) {
            val embedSrc = buildTwitchEmbedSrc(effectiveUrl)
            return@withContext ResolvedVideoTarget(
                originalInput = rawInput,
                resolvedUrl = effectiveUrl,
                platform = DetectedPlatform.TWITCH,
                recommendedEngine = if (embedSrc != null) {
                    PlaybackEngineMode.SMART_EMBED
                } else {
                    PlaybackEngineMode.WEB_ISOLATOR
                },
                embedHtml = embedSrc?.let { buildFullBleedIframeHtml(it) },
                embedBaseUrl = "https://www.twitch.tv",
                isolatorPageUrl = effectiveUrl
            )
        }

        // 7. Kick
        if (lowerUrl.contains("kick.com")) {
            val pathSegments = Uri.parse(effectiveUrl).pathSegments
            val channel = pathSegments.firstOrNull()?.takeIf {
                it.isNotBlank() && it != "video" && it != "categories"
            }
            val embedSrc = channel?.let { "https://player.kick.com/$it?autoplay=true&muted=false" }
            return@withContext ResolvedVideoTarget(
                originalInput = rawInput,
                resolvedUrl = effectiveUrl,
                platform = DetectedPlatform.KICK,
                recommendedEngine = if (embedSrc != null) {
                    PlaybackEngineMode.SMART_EMBED
                } else {
                    PlaybackEngineMode.WEB_ISOLATOR
                },
                embedHtml = embedSrc?.let { buildFullBleedIframeHtml(it) },
                embedBaseUrl = "https://kick.com",
                isolatorPageUrl = effectiveUrl
            )
        }

        // 8. Facebook (Watch, Videos, Reels, Share)
        if (lowerUrl.contains("facebook.com") || lowerUrl.contains("fb.watch")) {
            val directFb = extractFacebookDirectStream(htmlSample)
            val encodedHref = URLEncoder.encode(effectiveUrl, "UTF-8")
            val fbPluginUrl =
                "https://www.facebook.com/plugins/video.php?href=$encodedHref&show_text=false&autoplay=true"
            return@withContext ResolvedVideoTarget(
                originalInput = rawInput,
                resolvedUrl = effectiveUrl,
                platform = DetectedPlatform.FACEBOOK,
                recommendedEngine = when {
                    directFb != null -> PlaybackEngineMode.DIRECT_STREAM
                    else -> PlaybackEngineMode.SMART_EMBED
                },
                primaryStreamUrl = directFb,
                requestHeaders = mapOf("User-Agent" to MOBILE_CHROME_UA),
                embedHtml = buildFullBleedIframeHtml(fbPluginUrl),
                embedBaseUrl = "https://www.facebook.com",
                isolatorPageUrl = effectiveUrl
            )
        }

        // 9. Instagram (Reels, Posts, IGTV)
        if (lowerUrl.contains("instagram.com")) {
            val directIg = extractMetaVideoUrl(htmlSample)
            val match = Regex("""instagram\.com/(?:reel|reels|p|tv)/([\w-]+)""", RegexOption.IGNORE_CASE)
                .find(effectiveUrl)
            val shortcode = match?.groupValues?.getOrNull(1)
            val embedUrl = shortcode?.let { "https://www.instagram.com/reel/$it/embed/" } ?: effectiveUrl
            return@withContext ResolvedVideoTarget(
                originalInput = rawInput,
                resolvedUrl = effectiveUrl,
                platform = DetectedPlatform.INSTAGRAM,
                recommendedEngine = if (directIg != null) {
                    PlaybackEngineMode.DIRECT_STREAM
                } else {
                    PlaybackEngineMode.WEB_ISOLATOR
                },
                primaryStreamUrl = directIg,
                requestHeaders = mapOf(
                    "Referer" to "https://www.instagram.com/",
                    "User-Agent" to MOBILE_CHROME_UA
                ),
                embedHtml = buildFullBleedIframeHtml(embedUrl),
                embedBaseUrl = "https://www.instagram.com",
                isolatorPageUrl = embedUrl
            )
        }

        // 10. Vimeo
        if (lowerUrl.contains("vimeo.com")) {
            val vimeoId = Regex("""vimeo\.com/(?:video/)?(\d+)""").find(effectiveUrl)?.groupValues?.getOrNull(1)
            val directVimeo = vimeoId?.let { resolveVimeoStream(it) }
            val embedSrc = vimeoId?.let {
                "https://player.vimeo.com/video/$it?autoplay=1&loop=1&autopause=0&title=0&byline=0&portrait=0"
            }
            return@withContext ResolvedVideoTarget(
                originalInput = rawInput,
                resolvedUrl = effectiveUrl,
                platform = DetectedPlatform.VIMEO,
                recommendedEngine = when {
                    directVimeo != null -> PlaybackEngineMode.DIRECT_STREAM
                    embedSrc != null -> PlaybackEngineMode.SMART_EMBED
                    else -> PlaybackEngineMode.WEB_ISOLATOR
                },
                primaryStreamUrl = directVimeo,
                embedHtml = embedSrc?.let { buildFullBleedIframeHtml(it) },
                embedBaseUrl = "https://vimeo.com",
                isolatorPageUrl = effectiveUrl
            )
        }

        // 11. Dailymotion
        if (lowerUrl.contains("dailymotion.com") || lowerUrl.contains("dai.ly")) {
            val dmId = Regex("""(?:dailymotion\.com/video/|dai\.ly/)([a-zA-Z0-9]+)""")
                .find(effectiveUrl)?.groupValues?.getOrNull(1)
            val embedSrc = dmId?.let {
                "https://geo.dailymotion.com/player.html?video=$it&autoplay=1&loop=1&Controls=false"
            }
            return@withContext ResolvedVideoTarget(
                originalInput = rawInput,
                resolvedUrl = effectiveUrl,
                platform = DetectedPlatform.DAILYMOTION,
                recommendedEngine = if (embedSrc != null) {
                    PlaybackEngineMode.SMART_EMBED
                } else {
                    PlaybackEngineMode.WEB_ISOLATOR
                },
                embedHtml = embedSrc?.let { buildFullBleedIframeHtml(it) },
                embedBaseUrl = "https://www.dailymotion.com",
                isolatorPageUrl = effectiveUrl
            )
        }

        // 12. Streamable
        if (lowerUrl.contains("streamable.com")) {
            val code = Uri.parse(effectiveUrl).pathSegments.lastOrNull()?.takeIf { it != "e" }
            val directStreamable = code?.let { resolveStreamableMp4(it) }
            val embedSrc = code?.let { "https://streamable.com/e/$it?autoplay=1&loop=1&nocontrols=1" }
            return@withContext ResolvedVideoTarget(
                originalInput = rawInput,
                resolvedUrl = effectiveUrl,
                platform = DetectedPlatform.STREAMABLE,
                recommendedEngine = when {
                    directStreamable != null -> PlaybackEngineMode.DIRECT_STREAM
                    embedSrc != null -> PlaybackEngineMode.SMART_EMBED
                    else -> PlaybackEngineMode.WEB_ISOLATOR
                },
                primaryStreamUrl = directStreamable,
                embedHtml = embedSrc?.let { buildFullBleedIframeHtml(it) },
                embedBaseUrl = "https://streamable.com",
                isolatorPageUrl = effectiveUrl
            )
        }

        // 13. Rumble
        if (lowerUrl.contains("rumble.com")) {
            val rumbleEmbed = resolveRumbleEmbedUrl(effectiveUrl)
            return@withContext ResolvedVideoTarget(
                originalInput = rawInput,
                resolvedUrl = effectiveUrl,
                platform = DetectedPlatform.RUMBLE,
                recommendedEngine = if (rumbleEmbed != null) {
                    PlaybackEngineMode.SMART_EMBED
                } else {
                    PlaybackEngineMode.WEB_ISOLATOR
                },
                embedHtml = rumbleEmbed?.let { buildFullBleedIframeHtml("$it?pub=4") },
                embedBaseUrl = "https://rumble.com",
                isolatorPageUrl = effectiveUrl
            )
        }

        // 14. Google Drive
        if (lowerUrl.contains("drive.google.com")) {
            val driveId = Regex("""/file/d/([\w-]+)""").find(effectiveUrl)?.groupValues?.getOrNull(1)
                ?: Uri.parse(effectiveUrl).getQueryParameter("id")
            val previewUrl = driveId?.let { "https://drive.google.com/file/d/$it/preview" } ?: effectiveUrl
            return@withContext ResolvedVideoTarget(
                originalInput = rawInput,
                resolvedUrl = effectiveUrl,
                platform = DetectedPlatform.GOOGLE_DRIVE,
                recommendedEngine = PlaybackEngineMode.WEB_ISOLATOR,
                embedHtml = buildFullBleedIframeHtml(previewUrl),
                embedBaseUrl = "https://drive.google.com",
                isolatorPageUrl = previewUrl
            )
        }

        // 15. Bilibili / VK / OK.ru
        if (lowerUrl.contains("bilibili.com") || lowerUrl.contains("b23.tv")) {
            val bvid = Regex("""(BV[\w]+)""").find(effectiveUrl)?.groupValues?.getOrNull(1)
            val embedSrc = bvid?.let {
                "https://player.bilibili.com/player.html?bvid=$it&high_quality=1&autoplay=1"
            }
            return@withContext ResolvedVideoTarget(
                originalInput = rawInput,
                resolvedUrl = effectiveUrl,
                platform = DetectedPlatform.BILIBILI,
                recommendedEngine = if (embedSrc != null) PlaybackEngineMode.SMART_EMBED else PlaybackEngineMode.WEB_ISOLATOR,
                embedHtml = embedSrc?.let { buildFullBleedIframeHtml(it) },
                embedBaseUrl = "https://www.bilibili.com",
                isolatorPageUrl = effectiveUrl
            )
        }

        if (lowerUrl.contains("ok.ru/video/") || lowerUrl.contains("odnoklassniki.ru/video/")) {
            val okId = Regex("""/video/(\d+)""").find(effectiveUrl)?.groupValues?.getOrNull(1)
            val embedSrc = okId?.let { "https://ok.ru/videoembed/$it?autoplay=1" }
            return@withContext ResolvedVideoTarget(
                originalInput = rawInput,
                resolvedUrl = effectiveUrl,
                platform = DetectedPlatform.OK_RU,
                recommendedEngine = if (embedSrc != null) PlaybackEngineMode.SMART_EMBED else PlaybackEngineMode.WEB_ISOLATOR,
                embedHtml = embedSrc?.let { buildFullBleedIframeHtml(it) },
                embedBaseUrl = "https://ok.ru",
                isolatorPageUrl = effectiveUrl
            )
        }

        if (lowerUrl.contains("vk.com/video") || lowerUrl.contains("vkvideo.ru")) {
            return@withContext ResolvedVideoTarget(
                originalInput = rawInput,
                resolvedUrl = effectiveUrl,
                platform = DetectedPlatform.VK,
                recommendedEngine = PlaybackEngineMode.WEB_ISOLATOR,
                isolatorPageUrl = effectiveUrl
            )
        }

        // 16. Universal Generic Webpage: check OpenGraph / JSON-LD / inline .m3u8 or .mp4 first,
        // and configure Universal Cinema Video Isolator + Live Stream Sniffer!
        val scrapedDirect = extractMetaVideoUrl(htmlSample)
        val origin = runCatching {
            val u = Uri.parse(effectiveUrl)
            "${u.scheme}://${u.host}"
        }.getOrDefault("https://google.com")

        ResolvedVideoTarget(
            originalInput = rawInput,
            resolvedUrl = effectiveUrl,
            platform = DetectedPlatform.GENERIC_WEB,
            recommendedEngine = if (scrapedDirect != null) {
                PlaybackEngineMode.DIRECT_STREAM
            } else {
                PlaybackEngineMode.WEB_ISOLATOR
            },
            primaryStreamUrl = scrapedDirect,
            requestHeaders = mapOf(
                "Referer" to effectiveUrl,
                "Origin" to origin,
                "User-Agent" to MOBILE_CHROME_UA
            ),
            embedHtml = buildFullBleedIframeHtml(effectiveUrl),
            embedBaseUrl = origin,
            isolatorPageUrl = effectiveUrl
        )
    }

    private fun probeUrlAndFollowRedirects(url: String): Triple<String, String, String> {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", MOBILE_CHROME_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Range", "bytes=0-49152")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                val sample = if (ct.contains("html") || ct.contains("text")) {
                    resp.body?.byteStream()?.bufferedReader()?.use { reader ->
                        val buf = CharArray(49152)
                        val read = reader.read(buf)
                        if (read > 0) String(buf, 0, read) else ""
                    }.orEmpty()
                } else {
                    ""
                }
                Triple(finalUrl, ct, sample)
            }
        }.getOrElse { Triple(url, "", "") }
    }

    private fun convertCloudSharingUrl(url: String): String {
        if (url.contains("dropbox.com") && url.contains("dl=0")) {
            return url.replace("dl=0", "raw=1")
        }
        return url
    }

    private fun resolveTikTokDirectMp4(url: String): String? = runCatching {
        val encoded = URLEncoder.encode(url, "UTF-8")
        val req = Request.Builder()
            .url("https://www.tikwm.com/api/?url=$encoded&hd=1")
            .header("User-Agent", MOBILE_CHROME_UA)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            if (json.optInt("code", -1) == 0) {
                val data = json.optJSONObject("data")
                val hd = data?.optString("hdplay").orEmpty()
                val sd = data?.optString("play").orEmpty()
                val chosen = hd.ifBlank { sd }
                if (chosen.startsWith("http")) chosen else null
            } else null
        }
    }.getOrNull()

    private fun resolveTwitterDirectMp4(statusId: String): String? = runCatching {
        val req = Request.Builder()
            .url("https://api.vxtwitter.com/Twitter/status/$statusId")
            .header("User-Agent", MOBILE_CHROME_UA)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            val mediaArr = json.optJSONArray("media_extended") ?: return@use null
            for (i in 0 until mediaArr.length()) {
                val item = mediaArr.optJSONObject(i) ?: continue
                val type = item.optString("type")
                val mediaUrl = item.optString("url")
                if ((type == "video" || type == "gif") && mediaUrl.startsWith("http")) {
                    return@use mediaUrl
                }
            }
            null
        }
    }.getOrNull()

    private fun resolveRedditStream(url: String): String? = runCatching {
        val cleanBase = url.substringBefore("?").trimEnd('/')
        val jsonUrl = "$cleanBase.json"
        val req = Request.Builder()
            .url(jsonUrl)
            .header("User-Agent", DESKTOP_CHROME_UA)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val root = JSONArray(body)
            val postData = root.optJSONObject(0)
                ?.optJSONObject("data")
                ?.optJSONArray("children")
                ?.optJSONObject(0)
                ?.optJSONObject("data")
            val redditVideo = postData?.optJSONObject("secure_media")?.optJSONObject("reddit_video")
                ?: postData?.optJSONArray("crosspost_parent_list")
                    ?.optJSONObject(0)
                    ?.optJSONObject("secure_media")
                    ?.optJSONObject("reddit_video")
            val hls = redditVideo?.optString("hls_url")?.replace("&amp;", "&").orEmpty()
            val fallback = redditVideo?.optString("fallback_url")?.replace("&amp;", "&").orEmpty()
            hls.ifBlank { fallback }.takeIf { it.startsWith("http") }
        }
    }.getOrNull()

    private fun resolveVimeoStream(videoId: String): String? = runCatching {
        val req = Request.Builder()
            .url("https://player.vimeo.com/video/$videoId/config")
            .header("User-Agent", DESKTOP_CHROME_UA)
            .header("Referer", "https://vimeo.com/")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            val files = json.optJSONObject("request")?.optJSONObject("files")
            val progressive = files?.optJSONArray("progressive")
            if (progressive != null && progressive.length() > 0) {
                var bestUrl: String? = null
                var bestHeight = -1
                for (i in 0 until progressive.length()) {
                    val obj = progressive.optJSONObject(i) ?: continue
                    val h = obj.optInt("height", 0)
                    val u = obj.optString("url")
                    if (u.startsWith("http") && h > bestHeight) {
                        bestHeight = h
                        bestUrl = u
                    }
                }
                if (bestUrl != null) return@use bestUrl
            }
            val cdns = files?.optJSONObject("hls")?.optJSONObject("cdns")
            val firstKey = cdns?.keys()?.asSequence()?.firstOrNull()
            firstKey?.let { cdns.optJSONObject(it)?.optString("url") }?.takeIf { it.startsWith("http") }
        }
    }.getOrNull()

    private fun resolveStreamableMp4(code: String): String? = runCatching {
        val req = Request.Builder()
            .url("https://api.streamable.com/videos/$code")
            .header("User-Agent", MOBILE_CHROME_UA)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            val mp4Url = json.optJSONObject("files")?.optJSONObject("mp4")?.optString("url").orEmpty()
            when {
                mp4Url.startsWith("http") -> mp4Url
                mp4Url.startsWith("//") -> "https:$mp4Url"
                else -> null
            }
        }
    }.getOrNull()

    private fun resolveRumbleEmbedUrl(url: String): String? = runCatching {
        val encoded = URLEncoder.encode(url, "UTF-8")
        val req = Request.Builder()
            .url("https://rumble.com/api/Media/oembed.json?url=$encoded")
            .header("User-Agent", MOBILE_CHROME_UA)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val html = JSONObject(resp.body?.string().orEmpty()).optString("html")
            Regex("""src=["'](https://rumble\.com/embed/[^"'?]+)""")
                .find(html)?.groupValues?.getOrNull(1)
        }
    }.getOrNull()

    private fun buildTwitchEmbedSrc(url: String): String? {
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty().lowercase()
        val segments = uri.pathSegments
        if (host.contains("clips.twitch.tv") && segments.isNotEmpty()) {
            return "https://clips.twitch.tv/embed?clip=${segments.first()}&parent=www.twitch.tv&autoplay=true"
        }
        val clipIdx = segments.indexOf("clip")
        if (clipIdx != -1 && segments.size > clipIdx + 1) {
            return "https://clips.twitch.tv/embed?clip=${segments[clipIdx + 1]}&parent=www.twitch.tv&autoplay=true"
        }
        if (segments.size >= 2 && segments[0] == "videos") {
            return "https://player.twitch.tv/?video=v${segments[1]}&parent=www.twitch.tv&autoplay=true"
        }
        val channel = segments.firstOrNull()?.takeIf { it.isNotBlank() && it != "directory" }
        return channel?.let {
            "https://player.twitch.tv/?channel=$it&parent=www.twitch.tv&autoplay=true"
        }
    }

    private fun extractFacebookDirectStream(html: String): String? {
        if (html.isBlank()) return null
        val patterns = listOf(
            Regex(""""browser_native_hd_url"\s*:\s*"([^"]+)""""),
            Regex(""""hd_src_no_ratelimit"\s*:\s*"([^"]+)""""),
            Regex(""""hd_src"\s*:\s*"([^"]+)""""),
            Regex(""""browser_native_sd_url"\s*:\s*"([^"]+)""""),
            Regex(""""sd_src"\s*:\s*"([^"]+)"""")
        )
        for (regex in patterns) {
            val raw = regex.find(html)?.groupValues?.getOrNull(1) ?: continue
            val decoded = raw.replace("\\/", "/").replace("\\u0025", "%").replace("\\u0026", "&")
            if (decoded.startsWith("http")) return decoded
        }
        return extractMetaVideoUrl(html)
    }

    private fun extractMetaVideoUrl(html: String): String? {
        if (html.isBlank()) return null
        val metaPatterns = listOf(
            Regex("""property=["']og:video:secure_url["'][^>]*content=["'](https?://[^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""content=["'](https?://[^"']+)["'][^>]*property=["']og:video:secure_url["']""", RegexOption.IGNORE_CASE),
            Regex("""property=["']og:video:url["'][^>]*content=["'](https?://[^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""property=["']og:video["'][^>]*content=["'](https?://[^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""content=["'](https?://[^"']+)["'][^>]*property=["']og:video["']""", RegexOption.IGNORE_CASE),
            Regex(""""contentUrl"\s*:\s*"(https?://[^"]+\.(?:mp4|m3u8)[^"]*)"""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^\s"'<>\\]+\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""", RegexOption.IGNORE_CASE)
        )
        for (regex in metaPatterns) {
            val found = regex.find(html)?.groupValues?.getOrNull(1)
                ?.replace("&amp;", "&")
                ?.replace("\\/", "/")
                ?: continue
            val lower = found.lowercase()
            if ((lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains("video")) &&
                !lower.endsWith(".swf") && !lower.contains("/embed/")
            ) {
                return found
            }
        }
        return null
    }

    private fun buildFullBleedIframeHtml(iframeSrc: String): String = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
        <style>
        *{margin:0;padding:0;box-sizing:border-box}
        html,body{width:100%;height:100%;background:#000;overflow:hidden}
        iframe{position:fixed;inset:0;width:100vw;height:100vh;border:0;background:#000}
        </style>
        </head>
        <body>
        <iframe src="$iframeSrc"
            allow="autoplay; encrypted-media; picture-in-picture; fullscreen; web-share"
            allowfullscreen
            referrerpolicy="strict-origin-when-cross-origin"></iframe>
        </body>
        </html>
    """.trimIndent()

    private fun buildYouTubeEmbedHtml(videoId: String): String = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
        <style>
        *{margin:0;padding:0;box-sizing:border-box}
        html,body{width:100%;height:100%;background:#000;overflow:hidden}
        #player,iframe{position:fixed;inset:0;width:100vw;height:100vh;border:0;background:#000}
        </style>
        </head>
        <body>
        <div id="player"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
        var mainDur = 0;
        window.__rxlYtQualityMode = 'AUTO_720P_MIN';

        function __rxlEnforceYtQuality(player) {
            try {
                if (!player) return;
                var levels = (typeof player.getAvailableQualityLevels === 'function')
                    ? (player.getAvailableQualityLevels() || [])
                    : [];
                var mode = window.__rxlYtQualityMode || 'AUTO_720P_MIN';
                var hdRanks = ['highres', 'hd2160', 'hd1440', 'hd1080', 'hd720'];
                var hasAny720Plus = false;
                for (var i = 0; i < levels.length; i++) {
                    if (hdRanks.indexOf(levels[i]) !== -1) {
                        hasAny720Plus = true;
                        break;
                    }
                }

                var minQ = 'hd720';
                var maxQ = 'highres';
                if (mode === 'FORCE_1080P') {
                    minQ = (!levels.length || levels.indexOf('hd1080') !== -1) ? 'hd1080' : (hasAny720Plus ? 'hd720' : (levels[0] || 'hd1080'));
                    maxQ = 'hd1080';
                } else if (mode === 'FORCE_720P') {
                    minQ = (!levels.length || levels.indexOf('hd720') !== -1) ? 'hd720' : (levels[0] || 'hd720');
                    maxQ = minQ;
                } else {
                    // AUTO_720P_MIN: lock floor to hd720, fallback to best available if video max < 720p
                    minQ = (!levels.length || hasAny720Plus) ? 'hd720' : (levels[0] || 'hd720');
                    maxQ = (!levels.length || hasAny720Plus) ? 'highres' : minQ;
                }

                if (typeof player.setPlaybackQualityRange === 'function') {
                    player.setPlaybackQualityRange(minQ, maxQ);
                }
                if (typeof player.setPlaybackQuality === 'function') {
                    player.setPlaybackQuality(mode === 'FORCE_1080P' ? 'hd1080' : minQ);
                }
            } catch (err) {}
        }

        window.__rxlApplyYtQuality = function(mode) {
            window.__rxlYtQualityMode = mode || 'AUTO_720P_MIN';
            if (window.__rxlYtPlayer) {
                __rxlEnforceYtQuality(window.__rxlYtPlayer);
            }
        };

        function onYouTubeIframeAPIReady() {
            window.__rxlYtPlayer = new YT.Player('player', {
                videoId: '$videoId',
                host: 'https://www.youtube-nocookie.com',
                playerVars: {
                    autoplay: 1,
                    controls: 0,
                    rel: 0,
                    playsinline: 1,
                    loop: 1,
                    playlist: '$videoId',
                    iv_load_policy: 3,
                    modestbranding: 1,
                    fs: 0,
                    disablekb: 1,
                    vq: 'hd1080',
                    origin: 'https://www.youtube-nocookie.com'
                },
                events: {
                    onReady: function(e) {
                        try {
                            __rxlEnforceYtQuality(e.target);
                            e.target.playVideo();
                        } catch (_) {}
                    },
                    onStateChange: function(e) {
                        // Re-apply 720p+ quality range on every new video / play / buffer transition
                        __rxlEnforceYtQuality(e.target);
                        if (e.data === 0) {
                            try { e.target.seekTo(0); e.target.playVideo(); } catch (_) {}
                        }
                    },
                    onPlaybackQualityChange: function(e) {
                        var q = e.data || '';
                        if (q === 'tiny' || q === 'small' || q === 'medium' || q === 'large') {
                            __rxlEnforceYtQuality(e.target);
                        } else if (window.__rxlYtQualityMode === 'FORCE_1080P' && q !== 'hd1080') {
                            __rxlEnforceYtQuality(e.target);
                        }
                    },
                    onError: function(e) {
                        // Error 101 / 150 / 153 -> owner disabled iframe embedding; trigger instant Web Isolator fallback!
                        if (window.RxlBridge && window.RxlBridge.onEmbedBlocked) {
                            window.RxlBridge.onEmbedBlocked('yt_err_' + e.data);
                        }
                    }
                }
            });
        }
        setInterval(function() {
            try {
                var p = window.__rxlYtPlayer;
                if (!p || typeof p.getDuration !== 'function') return;
                __rxlEnforceYtQuality(p);
                var d = p.getDuration() || 0;
                if (d > mainDur + 2) mainDur = d;
                var data = p.getVideoData ? p.getVideoData() : null;
                var vid = data && data.video_id;
                var isAd = (mainDur > 0 && d > 0 && d < mainDur - 2) || !!(vid && vid !== '$videoId');
                if (window.__rxlYtAd !== isAd) {
                    window.__rxlYtAd = isAd;
                    if (window.RxlBridge && window.RxlBridge.onAdStateChanged) {
                        window.RxlBridge.onAdStateChanged(isAd);
                    }
                }
            } catch (e) {}
        }, 800);
        </script>
        </body>
        </html>
    """.trimIndent()
}
