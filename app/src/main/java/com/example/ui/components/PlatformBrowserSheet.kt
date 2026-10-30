package com.example.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.engine.AdBlockerEngine
import com.example.engine.UniversalVideoResolver
import com.example.engine.WebIsolatorScripts
import com.example.engine.isLikelyVideoStreamUrl
import com.example.model.SniffedStream
import com.example.ui.theme.NeonBroadcastRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.StudioBorderBlue
import com.example.ui.theme.StudioCardBg
import com.example.ui.theme.StudioDeepNavy
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.StudioVoid
import java.net.URLEncoder

private val QUICK_PLATFORMS = listOf(
    "YouTube" to "https://m.youtube.com",
    "TikTok" to "https://www.tiktok.com",
    "Twitch" to "https://m.twitch.tv",
    "Kick" to "https://kick.com",
    "Facebook" to "https://m.facebook.com/watch",
    "Instagram" to "https://www.instagram.com/reels/",
    "X / Twitter" to "https://x.com/explore",
    "Reddit" to "https://www.reddit.com",
    "Dailymotion" to "https://www.dailymotion.com",
    "Vimeo" to "https://vimeo.com/watch",
    "Google" to "https://www.google.com"
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlatformBrowserSheet(
    initialUrl: String,
    isAdBlockerEnabled: Boolean,
    onDismiss: () -> Unit,
    onSelectPageForIsolator: (String, List<SniffedStream>) -> Unit,
    onSelectDirectStream: (SniffedStream, String) -> Unit
) {
    val startUrl = remember(initialUrl) {
        if (initialUrl.startsWith("http")) initialUrl else "https://m.youtube.com"
    }
    var addressBarText by remember { mutableStateOf(startUrl) }
    var currentPageUrl by remember { mutableStateOf(startUrl) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val capturedStreams = remember { mutableStateListOf<SniffedStream>() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun navigateToInput(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        val dest = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains('.') && !trimmed.contains(' ') -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${URLEncoder.encode(trimmed, "UTF-8")}&tbm=vid"
        }
        capturedStreams.clear()
        currentPageUrl = dest
        addressBarText = dest
        webViewRef?.loadUrl(dest)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler {
            val wv = webViewRef
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
            } else {
                onDismiss()
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                webViewRef?.apply {
                    stopLoading()
                    destroy()
                }
                webViewRef = null
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = StudioVoid
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                // Header + Address Bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StudioDeepNavy)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(
                            onClick = {
                                val wv = webViewRef
                                if (wv != null && wv.canGoBack()) wv.goBack() else onDismiss()
                            },
                            modifier = Modifier.testTag("browser_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_close_browser),
                                tint = NeonCyan
                            )
                        }
                        Text(
                            text = stringResource(R.string.browser_sheet_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("browser_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.cd_close_browser),
                                tint = StudioTextSecondary
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = addressBarText,
                            onValueChange = { addressBarText = it },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.browser_url_hint),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { navigateToInput(addressBarText) }),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("browser_address_input")
                        )
                        Button(
                            onClick = { navigateToInput(addressBarText) },
                            colors = ButtonDefaults.buttonColors(containerColor = StudioCardBg),
                            modifier = Modifier
                                .height(52.dp)
                                .border(1.dp, NeonCyan, RoundedCornerShape(10.dp))
                                .testTag("browser_go_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = NeonCyan
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.browser_go),
                                color = StudioTextPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Quick Platform Chips
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        QUICK_PLATFORMS.forEach { (label, url) ->
                            AssistChip(
                                onClick = { navigateToInput(url) },
                                label = { Text(label, color = StudioTextPrimary) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = StudioCardBg
                                ),
                                border = AssistChipDefaults.assistChipBorder(
                                    enabled = true,
                                    borderColor = StudioBorderBlue
                                )
                            )
                        }
                    }
                }

                if (loadProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { loadProgress / 100f },
                        color = NeonCyan,
                        trackColor = StudioDeepNavy,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Embedded Full Browser WebView
                Box(modifier = Modifier.weight(1f)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                setBackgroundColor(AndroidColor.BLACK)
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    mediaPlaybackRequiresUserGesture = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    javaScriptCanOpenWindowsAutomatically = false
                                    setSupportMultipleWindows(true)
                                    userAgentString = UniversalVideoResolver.MOBILE_CHROME_UA
                                }
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                addJavascriptInterface(
                                    object {
                                        @JavascriptInterface
                                        fun onStreamSniffed(url: String, source: String) {
                                            mainHandler.post {
                                                if (capturedStreams.none { it.url == url }) {
                                                    capturedStreams.add(
                                                        0,
                                                        SniffedStream(
                                                            url = url,
                                                            mimeHint = source,
                                                            referer = currentPageUrl,
                                                            userAgent = UniversalVideoResolver.MOBILE_CHROME_UA,
                                                            label = if (url.contains(".m3u8")) "HLS M3U8" else "MP4 Stream"
                                                        )
                                                    )
                                                }
                                            }
                                        }

                                        @JavascriptInterface
                                        fun onEmbedBlocked(reason: String) = Unit

                                        @JavascriptInterface
                                        fun onAdStateChanged(isAd: Boolean) = Unit
                                    },
                                    "RxlBridge"
                                )

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        loadProgress = newProgress
                                    }

                                    override fun onCreateWindow(
                                        view: WebView?,
                                        isDialog: Boolean,
                                        isUserGesture: Boolean,
                                        resultMsg: Message?
                                    ): Boolean = false
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val scheme = request?.url?.scheme?.lowercase().orEmpty()
                                        if (scheme != "http" && scheme != "https") return true
                                        val reqUrl = request?.url?.toString().orEmpty()
                                        if (isAdBlockerEnabled && AdBlockerEngine.shouldBlockRequest(reqUrl)) {
                                            return true
                                        }
                                        return false
                                    }

                                    override fun shouldInterceptRequest(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): WebResourceResponse? {
                                        val reqUrl = request?.url?.toString().orEmpty()
                                        if (isAdBlockerEnabled && AdBlockerEngine.shouldBlockRequest(reqUrl)) {
                                            return AdBlockerEngine.createEmptyBlockedResponse()
                                        }
                                        if (isLikelyVideoStreamUrl(reqUrl.lowercase())) {
                                            val ref = request?.requestHeaders?.get("Referer") ?: currentPageUrl
                                            mainHandler.post {
                                                if (capturedStreams.none { it.url == reqUrl }) {
                                                    capturedStreams.add(
                                                        0,
                                                        SniffedStream(
                                                            url = reqUrl,
                                                            referer = ref,
                                                            userAgent = UniversalVideoResolver.MOBILE_CHROME_UA,
                                                            label = if (reqUrl.contains(".m3u8")) "HLS M3U8" else "Direct Video"
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                        return super.shouldInterceptRequest(view, request)
                                    }

                                    override fun onPageStarted(
                                        view: WebView?,
                                        url: String?,
                                        favicon: Bitmap?
                                    ) {
                                        super.onPageStarted(view, url, favicon)
                                        if (!url.isNullOrBlank()) {
                                            currentPageUrl = url
                                            addressBarText = url
                                        }
                                        view?.evaluateJavascript(
                                            WebIsolatorScripts.STREAM_SNIFFER_AND_AD_GUARD_JS,
                                            null
                                        )
                                        view?.evaluateJavascript(
                                            AdBlockerEngine.buildAdBlockAndPopupScript(isAdBlockerEnabled),
                                            null
                                        )
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        if (!url.isNullOrBlank()) {
                                            currentPageUrl = url
                                            addressBarText = url
                                        }
                                        view?.evaluateJavascript(
                                            WebIsolatorScripts.STREAM_SNIFFER_AND_AD_GUARD_JS,
                                            null
                                        )
                                        view?.evaluateJavascript(
                                            AdBlockerEngine.buildAdBlockAndPopupScript(isAdBlockerEnabled),
                                            null
                                        )
                                    }
                                }

                                loadUrl(startUrl)
                                webViewRef = this
                            }
                        }
                    )
                }

                // Bottom Capture & Send Bar
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StudioDeepNavy)
                        .padding(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.browser_tip),
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioTextSecondary
                    )

                    if (capturedStreams.isNotEmpty()) {
                        Button(
                            onClick = {
                                onSelectDirectStream(capturedStreams.first(), currentPageUrl)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonEmerald,
                                contentColor = StudioVoid
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("browser_play_sniffed_button")
                        ) {
                            Icon(imageVector = Icons.Default.Bolt, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(
                                    R.string.browser_use_sniffed_stream,
                                    capturedStreams.size
                                ),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val activeUrl = webViewRef?.url ?: currentPageUrl
                            onSelectPageForIsolator(activeUrl, capturedStreams.toList())
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AndroidColor.TRANSPARENT.let { androidx.compose.ui.graphics.Color.Transparent }),
                        contentPadding = ButtonDefaults.ContentPadding,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .background(
                                brush = Brush.horizontalGradient(listOf(NeonBroadcastRed, NeonPurple)),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .testTag("browser_send_to_studio_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayCircle,
                            contentDescription = null,
                            tint = StudioTextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.browser_use_page_isolated),
                            color = StudioTextPrimary,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}
