package com.example.engine

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.model.DisplayScaleMode
import com.example.model.PlaybackEngineMode
import com.example.model.ResolvedVideoTarget
import com.example.model.SniffedStream
import com.example.model.UserAgentMode
import com.example.model.VideoQualityPreference

private val SHARED_POSTER_BITMAP: Bitmap by lazy {
    Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun UniversalWebPlayerView(
    target: ResolvedVideoTarget,
    engineMode: PlaybackEngineMode,
    userAgentMode: UserAgentMode,
    qualityPreference: VideoQualityPreference,
    isAdBlockerEnabled: Boolean,
    isPlaying: Boolean,
    isMuted: Boolean,
    isAudioDucked: Boolean,
    scaleMode: DisplayScaleMode,
    reloadNonce: Int,
    onEmbedBlocked: (String) -> Unit,
    onStreamSniffed: (SniffedStream) -> Unit,
    onAdStateChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnEmbedBlocked by rememberUpdatedState(onEmbedBlocked)
    val currentOnStreamSniffed by rememberUpdatedState(onStreamSniffed)
    val currentOnAdStateChanged by rememberUpdatedState(onAdStateChanged)
    val currentIsPlaying by rememberUpdatedState(isPlaying)
    val currentIsMuted by rememberUpdatedState(isMuted)
    val currentIsAudioDucked by rememberUpdatedState(isAudioDucked)
    val currentQualityPref by rememberUpdatedState(qualityPreference)
    val currentAdBlockEnabled by rememberUpdatedState(isAdBlockerEnabled)
    val currentScaleMode by rememberUpdatedState(scaleMode)
    val currentEngineMode by rememberUpdatedState(engineMode)

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var lastLoadedSignature by remember { mutableStateOf("") }

    val desiredUa = if (userAgentMode == UserAgentMode.DESKTOP) {
        UniversalVideoResolver.DESKTOP_CHROME_UA
    } else {
        UniversalVideoResolver.MOBILE_CHROME_UA
    }

    val isInteractive = engineMode == PlaybackEngineMode.INTERACTIVE_WEB
    val shouldUseSmartEmbed = engineMode == PlaybackEngineMode.SMART_EMBED &&
        !target.embedHtml.isNullOrBlank()

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                runCatching {
                    stopLoading()
                    loadUrl("about:blank")
                    clearHistory()
                    clearCache(false)
                    (parent as? ViewGroup)?.removeView(this)
                    removeAllViews()
                    destroy()
                }
            }
            webViewRef = null
        }
    }

    // Re-sync play/pause, mute, audio ducking, 720p+ quality, scale mode, and isolator dynamically
    LaunchedEffect(
        isPlaying,
        isMuted,
        isAudioDucked,
        qualityPreference,
        scaleMode,
        engineMode,
        webViewRef
    ) {
        val wv = webViewRef ?: return@LaunchedEffect
        val script = if (shouldUseSmartEmbed) {
            WebIsolatorScripts.buildEmbedSyncJs(
                shouldPlay = isPlaying,
                shouldMute = isMuted,
                isAudioDucked = isAudioDucked,
                qualityPreference = qualityPreference,
                scaleMode = scaleMode
            )
        } else {
            WebIsolatorScripts.buildCinemaIsolatorJs(
                shouldPlay = isPlaying,
                shouldMute = isMuted,
                isAudioDucked = isAudioDucked,
                qualityPreference = qualityPreference,
                scaleMode = scaleMode,
                isolateChrome = engineMode != PlaybackEngineMode.INTERACTIVE_WEB
            )
        }
        wv.evaluateJavascript(script, null)
    }

    // Re-sync Ad Blocker & popup guard CSS/JS toggle dynamically
    LaunchedEffect(isAdBlockerEnabled, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        wv.evaluateJavascript(AdBlockerEngine.buildAdBlockAndPopupScript(isAdBlockerEnabled), null)
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val container = FrameLayout(ctx).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val fullscreenHolder = FrameLayout(ctx).apply {
                setBackgroundColor(Color.BLACK)
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val wv = WebView(ctx).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    loadsImagesAutomatically = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    // Block popups & unrequested window.open
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(true)
                    allowContentAccess = true
                    userAgentString = desiredUa
                }

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onEmbedBlocked(reason: String) {
                            mainHandler.post { currentOnEmbedBlocked(reason) }
                        }

                        @JavascriptInterface
                        fun onStreamSniffed(url: String, source: String) {
                            val pageRef = mainHandler.let { target.isolatorPageUrl }
                            mainHandler.post {
                                currentOnStreamSniffed(
                                    SniffedStream(
                                        url = url,
                                        mimeHint = guessStreamLabel(url),
                                        referer = pageRef,
                                        userAgent = desiredUa,
                                        label = "${guessStreamLabel(url)} ($source)"
                                    )
                                )
                            }
                        }

                        @JavascriptInterface
                        fun onAdStateChanged(isAd: Boolean) {
                            mainHandler.post { currentOnAdStateChanged(isAd) }
                        }
                    },
                    "RxlBridge"
                )

                webChromeClient = object : WebChromeClient() {
                    private var customView: View? = null
                    private var customCallback: CustomViewCallback? = null

                    override fun getDefaultVideoPoster(): Bitmap = SHARED_POSTER_BITMAP

                    // Block popup windows / tab hijacks
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?
                    ): Boolean {
                        return false
                    }

                    override fun onPermissionRequest(request: PermissionRequest?) {
                        request?.let { req ->
                            val allowed = req.resources.filter {
                                it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                            }.toTypedArray()
                            if (allowed.isNotEmpty()) req.grant(allowed) else req.deny()
                        }
                    }

                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                        if (customView != null) {
                            callback?.onCustomViewHidden()
                            return
                        }
                        customView = view
                        customCallback = callback
                        if (view != null) {
                            fullscreenHolder.removeAllViews()
                            fullscreenHolder.addView(
                                view,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            )
                            fullscreenHolder.visibility = View.VISIBLE
                        }
                    }

                    override fun onHideCustomView() {
                        fullscreenHolder.removeAllViews()
                        fullscreenHolder.visibility = View.GONE
                        customView = null
                        customCallback?.onCustomViewHidden()
                        customCallback = null
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val uri = request?.url ?: return false
                        val scheme = uri.scheme?.lowercase().orEmpty()
                        // Block intent://, market://, tiktok://, vnd.youtube:// deep links
                        if (scheme != "http" && scheme != "https") {
                            return true
                        }
                        val reqUrl = uri.toString()
                        // Block ad-domain navigations and automatic non-gesture popup redirects
                        if (currentAdBlockEnabled && AdBlockerEngine.shouldBlockRequest(reqUrl)) {
                            return true
                        }
                        if (currentAdBlockEnabled &&
                            request.isForMainFrame &&
                            !request.hasGesture() &&
                            request.isRedirect.not() &&
                            !sameBaseDomain(reqUrl, target.isolatorPageUrl)
                        ) {
                            return true
                        }
                        return false
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString().orEmpty()
                        if (currentAdBlockEnabled && AdBlockerEngine.shouldBlockRequest(reqUrl)) {
                            return AdBlockerEngine.createEmptyBlockedResponse()
                        }
                        val lower = reqUrl.lowercase()
                        if (isLikelyVideoStreamUrl(lower)) {
                            val ref = request?.requestHeaders?.get("Referer")
                                ?: target.isolatorPageUrl
                            val ua = request?.requestHeaders?.get("User-Agent") ?: desiredUa
                            mainHandler.post {
                                currentOnStreamSniffed(
                                    SniffedStream(
                                        url = reqUrl,
                                        mimeHint = guessStreamLabel(reqUrl),
                                        referer = ref,
                                        userAgent = ua,
                                        label = guessStreamLabel(reqUrl)
                                    )
                                )
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view?.evaluateJavascript(WebIsolatorScripts.STREAM_SNIFFER_AND_AD_GUARD_JS, null)
                        view?.evaluateJavascript(
                            AdBlockerEngine.buildAdBlockAndPopupScript(currentAdBlockEnabled),
                            null
                        )
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(WebIsolatorScripts.STREAM_SNIFFER_AND_AD_GUARD_JS, null)
                        view?.evaluateJavascript(
                            AdBlockerEngine.buildAdBlockAndPopupScript(currentAdBlockEnabled),
                            null
                        )
                        val mode = currentEngineMode
                        val syncScript = if (mode == PlaybackEngineMode.SMART_EMBED && !target.embedHtml.isNullOrBlank()) {
                            WebIsolatorScripts.buildEmbedSyncJs(
                                shouldPlay = currentIsPlaying,
                                shouldMute = currentIsMuted,
                                isAudioDucked = currentIsAudioDucked,
                                qualityPreference = currentQualityPref,
                                scaleMode = currentScaleMode
                            )
                        } else {
                            WebIsolatorScripts.buildCinemaIsolatorJs(
                                shouldPlay = currentIsPlaying,
                                shouldMute = currentIsMuted,
                                isAudioDucked = currentIsAudioDucked,
                                qualityPreference = currentQualityPref,
                                scaleMode = currentScaleMode,
                                isolateChrome = mode != PlaybackEngineMode.INTERACTIVE_WEB
                            )
                        }
                        view?.evaluateJavascript(syncScript, null)
                    }
                }
            }

            container.addView(wv)
            container.addView(fullscreenHolder)
            webViewRef = wv
            container
        },
        update = {
            val wv = webViewRef ?: return@AndroidView
            if (wv.settings.userAgentString != desiredUa) {
                wv.settings.userAgentString = desiredUa
            }

            wv.setOnTouchListener { _, event ->
                if (!isInteractive) {
                    event.action == MotionEvent.ACTION_MOVE
                } else {
                    wv.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
            }

            val signature = buildString {
                append(target.resolvedUrl)
                append("|")
                append(if (shouldUseSmartEmbed) "EMBED" else "PAGE")
                append("|")
                append(userAgentMode.name)
                append("|")
                append(reloadNonce)
            }

            if (signature != lastLoadedSignature) {
                lastLoadedSignature = signature
                if (shouldUseSmartEmbed && !target.embedHtml.isNullOrBlank()) {
                    wv.loadDataWithBaseURL(
                        target.embedBaseUrl,
                        target.embedHtml,
                        "text/html",
                        "utf-8",
                        null
                    )
                } else {
                    wv.loadUrl(target.isolatorPageUrl)
                }
            }
        }
    )
}

private fun sameBaseDomain(urlA: String, urlB: String): Boolean {
    val hostA = runCatching { android.net.Uri.parse(urlA).host.orEmpty().lowercase() }.getOrDefault("")
    val hostB = runCatching { android.net.Uri.parse(urlB).host.orEmpty().lowercase() }.getOrDefault("")
    if (hostA.isEmpty() || hostB.isEmpty()) return true
    if (hostA == hostB || hostA.endsWith(".$hostB") || hostB.endsWith(".$hostA")) return true
    // Treat youtube.com, m.youtube.com, youtu.be, youtube-nocookie.com as same family
    if (hostA.contains("youtu") && hostB.contains("youtu")) return true
    return false
}

fun isLikelyVideoStreamUrl(lowerUrl: String): Boolean {
    if (lowerUrl.startsWith("blob:") || lowerUrl.startsWith("data:")) return false
    if (lowerUrl.contains("doubleclick") ||
        lowerUrl.contains("googlesyndication") ||
        lowerUrl.contains("googleadservices") ||
        lowerUrl.endsWith(".js") ||
        lowerUrl.endsWith(".css") ||
        lowerUrl.endsWith(".png") ||
        lowerUrl.endsWith(".jpg") ||
        lowerUrl.endsWith(".webp") ||
        lowerUrl.endsWith(".woff2")
    ) {
        return false
    }
    return lowerUrl.contains(".m3u8") ||
        lowerUrl.contains(".mp4") ||
        lowerUrl.contains(".mpd") ||
        (lowerUrl.contains("videoplayback") && lowerUrl.contains("mime=video"))
}

private fun guessStreamLabel(url: String): String {
    val lower = url.lowercase()
    return when {
        lower.contains(".m3u8") -> "HLS M3U8"
        lower.contains(".mpd") -> "DASH MPD"
        lower.contains(".mp4") -> "Direct MP4"
        lower.contains(".webm") -> "WebM Video"
        else -> "Video Stream"
    }
}
