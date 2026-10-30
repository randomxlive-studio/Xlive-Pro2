package com.example.model

enum class PlaybackEngineMode {
    AUTO,
    WEB_ISOLATOR,
    SMART_EMBED,
    DIRECT_STREAM,
    INTERACTIVE_WEB
}

enum class VideoQualityPreference(val label: String, val minWidth: Int, val minHeight: Int) {
    AUTO_720P_MIN("Auto (720p+)", 1280, 720),
    FORCE_1080P("1080p", 1920, 1080),
    FORCE_720P("720p", 1280, 720)
}

enum class LlmProviderChoice(val displayName: String) {
    GEMINI_FREE("Gemini Free"),
    GROQ_FREE("Groq Free")
}

enum class DisplayScaleMode {
    FIT,
    FILL,
    STRETCH
}

enum class GameplayAspectPreset {
    LANDSCAPE_16_9,
    PORTRAIT_9_16,
    FREE
}

enum class UserAgentMode {
    MOBILE,
    DESKTOP
}

enum class DetectedPlatform(val displayLabel: String) {
    YOUTUBE("YouTube"),
    TIKTOK("TikTok"),
    TWITCH("Twitch"),
    KICK("Kick"),
    FACEBOOK("Facebook"),
    INSTAGRAM("Instagram"),
    TWITTER_X("X / Twitter"),
    REDDIT("Reddit"),
    VIMEO("Vimeo"),
    DAILYMOTION("Dailymotion"),
    RUMBLE("Rumble"),
    STREAMABLE("Streamable"),
    GOOGLE_DRIVE("Google Drive"),
    BILIBILI("Bilibili"),
    VK("VK Video"),
    OK_RU("OK.ru"),
    DIRECT_MEDIA("Direct Stream"),
    GENERIC_WEB("Universal Web")
}

data class SniffedStream(
    val url: String,
    val mimeHint: String = "",
    val referer: String = "",
    val userAgent: String = "",
    val label: String = ""
)

data class TtsVoiceOption(
    val id: String,
    val displayName: String,
    val localeTag: String
)

data class LiveCommentItem(
    val username: String,
    val text: String,
    val hash: String,
    val isQuestion: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

data class WindowRectState(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float
) {
    fun clamped(): WindowRectState {
        val safeW = w.coerceIn(0.12f, 1.2f)
        val safeH = h.coerceIn(0.08f, 1.2f)
        val minX = minOf(0f, 1f - safeW)
        val maxX = maxOf(0f, 1f - safeW)
        val minY = minOf(0f, 1f - safeH)
        val maxY = maxOf(0f, 1f - safeH)
        return copy(
            w = safeW,
            h = safeH,
            x = x.coerceIn(minX, maxX),
            y = y.coerceIn(minY, maxY)
        )
    }

    companion object {
        val DefaultGameplay = WindowRectState(
            x = 0.04f,
            y = 0.19f,
            w = 0.92f,
            h = 0.92f * 81f / 256f
        )
        val DefaultCharacterPortrait = WindowRectState(
            x = 0.68f,
            y = 0.03f,
            w = 0.26f,
            h = 0.26f
        )
        val DefaultCharacterLandscape = WindowRectState(
            x = 0.80f,
            y = 0.05f,
            w = 0.22f,
            h = 0.22f
        )
    }
}

data class ResolvedVideoTarget(
    val originalInput: String,
    val resolvedUrl: String,
    val platform: DetectedPlatform,
    val recommendedEngine: PlaybackEngineMode,
    val primaryStreamUrl: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val embedHtml: String? = null,
    val embedBaseUrl: String = "https://www.youtube-nocookie.com",
    val isolatorPageUrl: String = resolvedUrl,
    val youtubeId: String? = null
)

data class StudioUiState(
    val accountName: String = "RandomXLive",
    val avatarLocalPath: String? = null,

    // Gameplay media state
    val urlInput: String = "",
    val activeUrl: String = "",
    val gameplayLocalPath: String? = null,
    val resolvedTarget: ResolvedVideoTarget? = null,
    val engineMode: PlaybackEngineMode = PlaybackEngineMode.AUTO,
    val qualityPreference: VideoQualityPreference = VideoQualityPreference.AUTO_720P_MIN,
    val isAdBlockerEnabled: Boolean = true,
    val userAgentMode: UserAgentMode = UserAgentMode.MOBILE,
    val sniffedStreams: List<SniffedStream> = emptyList(),
    val selectedSniffedStream: SniffedStream? = null,
    val isResolvingUrl: Boolean = false,
    val playerReloadNonce: Int = 0,

    // Gameplay playback & visual settings
    val gameplayRect: WindowRectState = WindowRectState.DefaultGameplay,
    val aspectPreset: GameplayAspectPreset = GameplayAspectPreset.LANDSCAPE_16_9,
    val displayScaleMode: DisplayScaleMode = DisplayScaleMode.FIT,
    val isGameplayPlaying: Boolean = true,
    val isGameplaySoundOn: Boolean = true,
    val isAutoHideAdsOn: Boolean = true,
    val isAdDetectedNow: Boolean = false,
    val isManualCoverOn: Boolean = false,
    val isVoiceReduceOn: Boolean = false,

    // Circular Character & Camera settings
    val characterLocalPath: String? = null,
    val isCharacterImage: Boolean = false,
    val isCameraActive: Boolean = false,
    val isFrontCamera: Boolean = true,
    val isCharacterPlaying: Boolean = true,
    val isCharacterSoundOn: Boolean = false,
    val isPulseOnAiSpeakEnabled: Boolean = true,
    val characterPortraitRect: WindowRectState = WindowRectState.DefaultCharacterPortrait,
    val characterLandscapeRect: WindowRectState = WindowRectState.DefaultCharacterLandscape,

    // TikTok LIVE Comment Reader & Sarcastic AI Voice state
    val isCommentReaderRunning: Boolean = false,
    val isAccessibilityServiceConnected: Boolean = false,
    val isOcrFallbackRunning: Boolean = false,
    val isSetupGuideOpen: Boolean = false,
    val isAiRepliesEnabled: Boolean = true,
    val isVoiceTtsEnabled: Boolean = true,
    val llmProvider: LlmProviderChoice = LlmProviderChoice.GEMINI_FREE,
    val llmApiKey: String = "",
    val availableTtsVoices: List<TtsVoiceOption> = emptyList(),
    val selectedTtsVoiceId: String = "",
    val ttsSpeed: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val aiCooldownSeconds: Int = 15,
    val queuedCommentsCount: Int = 0,
    val dailyAiRequestCount: Int = 0,
    val isAiSpeaking: Boolean = false,

    // Studio UI state
    val isBroadcastMode: Boolean = false,
    val isPanelMinimized: Boolean = false,
    val isBrowserSheetOpen: Boolean = false,
    val statusBannerMessage: String? = null,
    val isStatusSuccess: Boolean = true
) {
    val isCoverVisible: Boolean
        get() = isManualCoverOn || (isAutoHideAdsOn && isAdDetectedNow)

    val effectiveEngine: PlaybackEngineMode
        get() {
            if (selectedSniffedStream != null &&
                (engineMode == PlaybackEngineMode.DIRECT_STREAM || engineMode == PlaybackEngineMode.AUTO)
            ) {
                return PlaybackEngineMode.DIRECT_STREAM
            }
            if (engineMode != PlaybackEngineMode.AUTO) return engineMode
            return resolvedTarget?.recommendedEngine ?: PlaybackEngineMode.WEB_ISOLATOR
        }
}
