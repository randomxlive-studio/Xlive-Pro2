package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.ai.AiReplyConfig
import com.example.ai.AiReplyResult
import com.example.ai.SarcasticAiResponder
import com.example.ai.TtsVoiceManager
import com.example.crash.CrashReportManager
import com.example.data.StudioPreferencesRepository
import com.example.engine.UniversalVideoResolver
import com.example.model.DetectedPlatform
import com.example.model.DisplayScaleMode
import com.example.model.GameplayAspectPreset
import com.example.model.LlmProviderChoice
import com.example.model.PlaybackEngineMode
import com.example.model.ResolvedVideoTarget
import com.example.model.SniffedStream
import com.example.model.StudioUiState
import com.example.model.UserAgentMode
import com.example.model.VideoQualityPreference
import com.example.model.WindowRectState
import com.example.service.LiveCommentBus
import com.example.service.TikTokOcrFallbackService
import com.example.ui.components.isAccessibilityServiceEnabledInSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = StudioPreferencesRepository(application.applicationContext)
    private val resolver = UniversalVideoResolver()
    private val aiResponder = SarcasticAiResponder()
    private val ttsVoiceManager = TtsVoiceManager(application.applicationContext)

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private var resolveJob: Job? = null
    private var bannerJob: Job? = null
    private var previewVoiceJob: Job? = null

    private val characterSwitchMutex = Mutex()

    @Volatile
    private var isInitialStateLoaded: Boolean = false

    @Volatile
    private var isSilentlyPausedForRateLimit: Boolean = false

    init {
        viewModelScope.launch {
            val saved = runCatching { repo.loadInitialState() }.getOrElse { StudioUiState() }
            val crashLog = runCatching {
                CrashReportManager.readPendingCrashReport(application)
            }.getOrNull()

            LiveCommentBus.ownUsernameLower = saved.accountName.lowercase()
            _uiState.update { current ->
                saved.copy(
                    // If user picked a video while startup was finishing, preserve the newer pick
                    characterLocalPath = current.characterLocalPath ?: saved.characterLocalPath,
                    characterVideoSwitchNonce = maxOf(
                        current.characterVideoSwitchNonce,
                        saved.characterVideoSwitchNonce
                    ),
                    availableTtsVoices = ttsVoiceManager.availableVoices.value,
                    isAccessibilityServiceConnected = LiveCommentBus.isAccessibilityConnected.value ||
                        isAccessibilityServiceEnabledInSettings(application),
                    isOcrFallbackRunning = LiveCommentBus.isOcrFallbackRunning.value,
                    pendingCrashReport = crashLog
                )
            }
            isInitialStateLoaded = true

            if (saved.activeUrl.isNotBlank()) {
                loadVideoLink(saved.activeUrl, keepCurrentEngine = true)
            }
        }

        // Observe TTS voices & active speech state (controls audio ducking + circle scale pulse)
        viewModelScope.launch {
            ttsVoiceManager.availableVoices.collect { voices ->
                _uiState.update { it.copy(availableTtsVoices = voices) }
            }
        }

        viewModelScope.launch {
            ttsVoiceManager.isSpeaking.collect { speaking ->
                _uiState.update { it.copy(isAiSpeaking = speaking) }
            }
        }

        // Observe TikTok Accessibility & OCR Fallback states and waiting queue count
        viewModelScope.launch {
            LiveCommentBus.isAccessibilityConnected.collect { connected ->
                _uiState.update {
                    it.copy(
                        isAccessibilityServiceConnected = connected ||
                            isAccessibilityServiceEnabledInSettings(getApplication())
                    )
                }
            }
        }

        viewModelScope.launch {
            LiveCommentBus.isOcrFallbackRunning.collect { running ->
                _uiState.update { it.copy(isOcrFallbackRunning = running) }
            }
        }

        viewModelScope.launch {
            LiveCommentBus.waitingQueue.collect { queue ->
                _uiState.update { it.copy(queuedCommentsCount = queue.size) }
            }
        }

        // Single-worker sarcastic voice reply loop (handles 1 comment at a time with cooldown)
        viewModelScope.launch {
            while (isActive) {
                val snap = _uiState.value
                val canProcess = snap.isCommentReaderRunning &&
                    snap.isAiRepliesEnabled &&
                    snap.llmApiKey.isNotBlank() &&
                    !isSilentlyPausedForRateLimit &&
                    snap.dailyAiRequestCount < AiReplyConfig.DAILY_FREE_REQUEST_LIMIT

                if (!canProcess) {
                    delay(800L)
                    continue
                }

                val nextComment = LiveCommentBus.pollNextCommentForReply()
                if (nextComment == null) {
                    delay(500L)
                    continue
                }

                val result = aiResponder.generateSarcasticReply(
                    provider = snap.llmProvider,
                    apiKey = snap.llmApiKey,
                    username = nextComment.username,
                    commentText = nextComment.text
                )

                when (result) {
                    is AiReplyResult.Success -> {
                        _uiState.update {
                            it.copy(dailyAiRequestCount = it.dailyAiRequestCount + 1)
                        }
                        persistCurrentState()

                        // Speak audio ONLY (never draw any text bubble or overlay text)
                        val latest = _uiState.value
                        if (latest.isVoiceTtsEnabled && latest.isCommentReaderRunning && latest.isAiRepliesEnabled) {
                            ttsVoiceManager.speakAndAwait(
                                text = result.spokenReply,
                                selectedVoiceId = latest.selectedTtsVoiceId,
                                speed = latest.ttsSpeed,
                                pitch = latest.ttsPitch
                            )
                        }

                        val cooldownMs = (_uiState.value.aiCooldownSeconds.coerceIn(3, 120)) * 1000L
                        delay(cooldownMs)
                    }

                    AiReplyResult.RateLimited -> {
                        // Free-tier limit reached: pause silently without crashing or spamming
                        isSilentlyPausedForRateLimit = true
                        delay(5_000L)
                    }

                    AiReplyResult.SilentFailure -> {
                        // Silent skip without crashing or spamming
                        val cooldownMs = (_uiState.value.aiCooldownSeconds.coerceIn(3, 120)) * 1000L
                        delay(cooldownMs)
                    }
                }
            }
        }
    }

    private fun persistCurrentState() {
        if (!isInitialStateLoaded) return
        val snapshot = _uiState.value
        viewModelScope.launch {
            repo.saveState(snapshot)
        }
    }

    fun dismissCrashReport() {
        _uiState.update { it.copy(pendingCrashReport = null) }
        viewModelScope.launch {
            CrashReportManager.clearCrashReport(getApplication())
        }
    }

    fun showStatus(message: String, isSuccess: Boolean = true) {
        bannerJob?.cancel()
        _uiState.update {
            it.copy(statusBannerMessage = message, isStatusSuccess = isSuccess)
        }
        bannerJob = viewModelScope.launch {
            delay(if (isSuccess) 2800L else 5000L)
            _uiState.update { it.copy(statusBannerMessage = null) }
        }
    }

    fun onUrlInputChange(text: String) {
        _uiState.update { it.copy(urlInput = text) }
    }

    fun clearUrlAndReturnToLocal() {
        resolveJob?.cancel()
        _uiState.update {
            it.copy(
                urlInput = "",
                activeUrl = "",
                resolvedTarget = null,
                sniffedStreams = emptyList(),
                selectedSniffedStream = null,
                isAdDetectedNow = false,
                isResolvingUrl = false
            )
        }
        persistCurrentState()
    }

    fun loadVideoLink(rawInput: String = _uiState.value.urlInput, keepCurrentEngine: Boolean = false) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            clearUrlAndReturnToLocal()
            return
        }

        val normalized = resolver.normalizeInputToUrl(trimmed)
        if (normalized == null) {
            showStatus(getApplication<Application>().getString(R.string.status_invalid_url), false)
            return
        }

        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    urlInput = trimmed,
                    isResolvingUrl = true,
                    sniffedStreams = emptyList(),
                    selectedSniffedStream = null,
                    isAdDetectedNow = false
                )
            }
            showStatus(getApplication<Application>().getString(R.string.status_resolving), true)

            val resolved = resolver.resolve(trimmed)
            _uiState.update { prev ->
                prev.copy(
                    activeUrl = resolved.resolvedUrl,
                    urlInput = resolved.resolvedUrl,
                    resolvedTarget = resolved,
                    engineMode = if (keepCurrentEngine) prev.engineMode else PlaybackEngineMode.AUTO,
                    isResolvingUrl = false,
                    isGameplayPlaying = true,
                    playerReloadNonce = prev.playerReloadNonce + 1
                )
            }
            persistCurrentState()
            showStatus(
                getApplication<Application>().getString(
                    R.string.status_loaded_platform,
                    resolved.platform.displayLabel
                ),
                true
            )
        }
    }

    fun handleSharedText(sharedText: String) {
        if (sharedText.isBlank()) return
        _uiState.update { it.copy(urlInput = sharedText) }
        loadVideoLink(sharedText)
    }

    fun handlePickedGameplayVideo(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val saved = repo.copyPickedMediaToInternalStorage(uri, "gameplay")
                if (saved != null) {
                    val (savedPath, _) = saved
                    _uiState.update {
                        it.copy(
                            gameplayLocalPath = savedPath,
                            activeUrl = "",
                            urlInput = "",
                            resolvedTarget = null,
                            sniffedStreams = emptyList(),
                            selectedSniffedStream = null,
                            isGameplayPlaying = true,
                            playerReloadNonce = it.playerReloadNonce + 1
                        )
                    }
                    persistCurrentState()
                    showStatus(getApplication<Application>().getString(R.string.status_saved_local), true)
                    delay(1200L)
                    repo.cleanupOldSlotMedia("gameplay", savedPath)
                }
            }
        }
    }

    /**
     * Safe character video change flow:
     * 1. Takes persistable URI permission where applicable.
     * 2. Stops Camera / releases current video player cleanly before switching.
     * 3. Copies the picked video stream into internal `filesDir/character_videos` on `Dispatchers.IO`.
     * 4. Plays exclusively from the `filesDir` copy and cleans up older copies after switching.
     */
    fun handlePickedCharacterVideo(uri: Uri) {
        val app = getApplication<Application>()
        try {
            repo.tryTakePersistableReadPermission(uri)
        } catch (_: Throwable) {
        }

        viewModelScope.launch {
            characterSwitchMutex.withLock {
                val previousValidPath = _uiState.value.characterLocalPath
                    ?.takeIf { repo.isValidCharacterVideoFile(it) }

                try {
                    // Tear down active Camera or previous ExoPlayer first before loading the new video
                    _uiState.update {
                        it.copy(
                            isCameraActive = false,
                            isCharacterVideoSwitching = true
                        )
                    }

                    val copyResult = repo.copyCharacterVideoToInternalStorage(uri)
                    val savedPath = copyResult.getOrNull()

                    if (!savedPath.isNullOrBlank() && repo.isValidCharacterVideoFile(savedPath)) {
                        _uiState.update {
                            it.copy(
                                characterLocalPath = savedPath,
                                characterVideoSwitchNonce = it.characterVideoSwitchNonce + 1L,
                                isCharacterVideoSwitching = false,
                                isCameraActive = false,
                                isCharacterPlaying = true
                            )
                        }
                        if (isInitialStateLoaded) {
                            repo.saveState(_uiState.value)
                        }
                        showStatus(app.getString(R.string.status_saved_local), true)

                        // Clean up previous copied character videos safely after old player released
                        viewModelScope.launch(Dispatchers.IO) {
                            delay(1500L)
                            repo.cleanupOldCharacterVideos(
                                activePathToKeep = _uiState.value.characterLocalPath
                            )
                        }
                    } else {
                        val reason = copyResult.exceptionOrNull()?.localizedMessage
                            ?: "Unsupported video"
                        _uiState.update {
                            it.copy(
                                characterLocalPath = previousValidPath,
                                characterVideoSwitchNonce = if (previousValidPath != null) {
                                    it.characterVideoSwitchNonce + 1L
                                } else {
                                    it.characterVideoSwitchNonce
                                },
                                isCharacterVideoSwitching = false,
                                isCameraActive = false
                            )
                        }
                        val errMsg = app.getString(R.string.character_video_copy_failed, reason)
                        runCatching {
                            Toast.makeText(app, errMsg, Toast.LENGTH_LONG).show()
                        }
                        showStatus(errMsg, false)
                    }
                } catch (t: Throwable) {
                    val reason = t.localizedMessage ?: t.javaClass.simpleName
                    _uiState.update {
                        it.copy(
                            characterLocalPath = previousValidPath,
                            isCharacterVideoSwitching = false,
                            isCameraActive = false
                        )
                    }
                    val errMsg = app.getString(R.string.character_video_copy_failed, reason)
                    runCatching {
                        Toast.makeText(app, errMsg, Toast.LENGTH_LONG).show()
                    }
                    showStatus(errMsg, false)
                }
            }
        }
    }

    fun onCharacterVideoPlaybackError(errorDetail: String) {
        val app = getApplication<Application>()
        runCatching {
            _uiState.update {
                it.copy(
                    characterLocalPath = null,
                    isCharacterVideoSwitching = false,
                    isCameraActive = false
                )
            }
            persistCurrentState()
            viewModelScope.launch(Dispatchers.IO) {
                delay(500L)
                repo.cleanupOldCharacterVideos(activePathToKeep = null)
            }
            showStatus(
                app.getString(R.string.character_video_error_toast, errorDetail),
                false
            )
        }
    }

    fun onCharacterVideoFileMissing() {
        val app = getApplication<Application>()
        runCatching {
            _uiState.update {
                it.copy(
                    characterLocalPath = null,
                    isCharacterVideoSwitching = false,
                    isCameraActive = false
                )
            }
            persistCurrentState()
            showStatus(
                app.getString(R.string.character_video_missing_restored_default),
                false
            )
        }
    }

    fun onCharacterCameraError(errorDetail: String) {
        val app = getApplication<Application>()
        runCatching {
            _uiState.update { it.copy(isCameraActive = false) }
            showStatus(
                app.getString(R.string.character_camera_error_toast, errorDetail),
                false
            )
        }
    }

    fun handlePickedAvatarImage(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val saved = repo.copyPickedMediaToInternalStorage(uri, "avatar")
                if (saved != null) {
                    val (savedPath, _) = saved
                    _uiState.update { it.copy(avatarLocalPath = savedPath) }
                    persistCurrentState()
                    showStatus(getApplication<Application>().getString(R.string.status_saved_local), true)
                    delay(1000L)
                    repo.cleanupOldSlotMedia("avatar", savedPath)
                }
            }
        }
    }

    fun onEmbedBlockedOrFailed(reason: String) {
        val current = _uiState.value
        if (current.effectiveEngine != PlaybackEngineMode.WEB_ISOLATOR) {
            _uiState.update {
                it.copy(
                    engineMode = PlaybackEngineMode.WEB_ISOLATOR,
                    playerReloadNonce = it.playerReloadNonce + 1
                )
            }
            showStatus(
                getApplication<Application>().getString(R.string.status_fallback_isolator),
                true
            )
        }
    }

    fun onDirectStreamFailed(errorCode: String) {
        val current = _uiState.value
        if (current.activeUrl.isNotBlank()) {
            _uiState.update {
                it.copy(
                    selectedSniffedStream = null,
                    engineMode = PlaybackEngineMode.WEB_ISOLATOR,
                    playerReloadNonce = it.playerReloadNonce + 1
                )
            }
            showStatus(
                getApplication<Application>().getString(R.string.status_fallback_isolator),
                true
            )
        }
    }

    fun onStreamSniffed(stream: SniffedStream) {
        val lower = stream.url.lowercase()
        val canAutoPromoteToExo = (lower.contains(".m3u8") || lower.contains(".mp4")) &&
            !lower.contains("googlevideo.com")

        _uiState.update { prev ->
            if (prev.sniffedStreams.any { it.url == stream.url }) {
                prev
            } else {
                val updatedList = (listOf(stream) + prev.sniffedStreams).take(8)
                val shouldAutoSwitch = canAutoPromoteToExo &&
                    prev.engineMode == PlaybackEngineMode.AUTO &&
                    prev.selectedSniffedStream == null &&
                    prev.resolvedTarget?.platform != DetectedPlatform.YOUTUBE
                prev.copy(
                    sniffedStreams = updatedList,
                    selectedSniffedStream = if (shouldAutoSwitch) stream else prev.selectedSniffedStream,
                    playerReloadNonce = if (shouldAutoSwitch) prev.playerReloadNonce + 1 else prev.playerReloadNonce
                )
            }
        }
    }

    fun selectSniffedStream(stream: SniffedStream) {
        _uiState.update {
            it.copy(
                selectedSniffedStream = stream,
                engineMode = PlaybackEngineMode.DIRECT_STREAM,
                isGameplayPlaying = true,
                playerReloadNonce = it.playerReloadNonce + 1
            )
        }
    }

    fun applyFromBrowserIsolator(pageUrl: String, capturedStreams: List<SniffedStream>) {
        val firstPlayable = capturedStreams.firstOrNull { s ->
            val l = s.url.lowercase()
            (l.contains(".m3u8") || l.contains(".mp4")) && !l.contains("googlevideo.com")
        }
        val target = ResolvedVideoTarget(
            originalInput = pageUrl,
            resolvedUrl = pageUrl,
            platform = DetectedPlatform.GENERIC_WEB,
            recommendedEngine = if (firstPlayable != null) {
                PlaybackEngineMode.DIRECT_STREAM
            } else {
                PlaybackEngineMode.WEB_ISOLATOR
            },
            primaryStreamUrl = firstPlayable?.url,
            isolatorPageUrl = pageUrl
        )
        _uiState.update {
            it.copy(
                isBrowserSheetOpen = false,
                urlInput = pageUrl,
                activeUrl = pageUrl,
                resolvedTarget = target,
                sniffedStreams = capturedStreams,
                selectedSniffedStream = firstPlayable,
                engineMode = PlaybackEngineMode.AUTO,
                isGameplayPlaying = true,
                playerReloadNonce = it.playerReloadNonce + 1
            )
        }
        persistCurrentState()
    }

    fun applyFromBrowserDirectStream(stream: SniffedStream, pageUrl: String) {
        val target = ResolvedVideoTarget(
            originalInput = pageUrl,
            resolvedUrl = pageUrl,
            platform = DetectedPlatform.DIRECT_MEDIA,
            recommendedEngine = PlaybackEngineMode.DIRECT_STREAM,
            primaryStreamUrl = stream.url,
            requestHeaders = mapOf(
                "Referer" to stream.referer.ifBlank { pageUrl },
                "User-Agent" to stream.userAgent.ifBlank { UniversalVideoResolver.MOBILE_CHROME_UA }
            ),
            isolatorPageUrl = pageUrl
        )
        _uiState.update {
            it.copy(
                isBrowserSheetOpen = false,
                urlInput = pageUrl,
                activeUrl = pageUrl,
                resolvedTarget = target,
                sniffedStreams = listOf(stream),
                selectedSniffedStream = stream,
                engineMode = PlaybackEngineMode.DIRECT_STREAM,
                isGameplayPlaying = true,
                playerReloadNonce = it.playerReloadNonce + 1
            )
        }
        persistCurrentState()
    }

    fun onAdStateChanged(isAdShowing: Boolean) {
        _uiState.update { it.copy(isAdDetectedNow = isAdShowing) }
    }

    fun setQualityPreference(pref: VideoQualityPreference) {
        _uiState.update { it.copy(qualityPreference = pref) }
        persistCurrentState()
    }

    fun toggleAdBlocker() {
        _uiState.update { it.copy(isAdBlockerEnabled = !it.isAdBlockerEnabled) }
        persistCurrentState()
    }

    fun setEngineMode(mode: PlaybackEngineMode) {
        _uiState.update {
            it.copy(
                engineMode = mode,
                playerReloadNonce = it.playerReloadNonce + 1
            )
        }
        persistCurrentState()
    }

    fun toggleUserAgentMode() {
        _uiState.update {
            val next = if (it.userAgentMode == UserAgentMode.MOBILE) {
                UserAgentMode.DESKTOP
            } else {
                UserAgentMode.MOBILE
            }
            it.copy(userAgentMode = next, playerReloadNonce = it.playerReloadNonce + 1)
        }
        persistCurrentState()
    }

    fun reloadPlayer() {
        _uiState.update { it.copy(playerReloadNonce = it.playerReloadNonce + 1) }
    }

    fun updateAccountName(name: String) {
        LiveCommentBus.ownUsernameLower = name.trim().lowercase()
        _uiState.update { it.copy(accountName = name) }
        persistCurrentState()
    }

    fun toggleGameplayPlay() {
        _uiState.update { it.copy(isGameplayPlaying = !it.isGameplayPlaying) }
    }

    fun toggleGameplaySound() {
        _uiState.update { it.copy(isGameplaySoundOn = !it.isGameplaySoundOn) }
        persistCurrentState()
    }

    fun toggleAutoHideAds() {
        _uiState.update {
            val next = !it.isAutoHideAdsOn
            it.copy(
                isAutoHideAdsOn = next,
                isAdDetectedNow = if (!next) false else it.isAdDetectedNow
            )
        }
        persistCurrentState()
    }

    fun toggleManualCover() {
        _uiState.update { it.copy(isManualCoverOn = !it.isManualCoverOn) }
    }

    fun toggleVoiceReduce() {
        _uiState.update { it.copy(isVoiceReduceOn = !it.isVoiceReduceOn) }
        persistCurrentState()
    }

    fun setDisplayScaleMode(mode: DisplayScaleMode) {
        _uiState.update { it.copy(displayScaleMode = mode) }
        persistCurrentState()
    }

    fun setAspectPreset(preset: GameplayAspectPreset) {
        _uiState.update { prev ->
            var w = prev.gameplayRect.w
            if (preset == GameplayAspectPreset.PORTRAIT_9_16 && w > 0.62f) {
                w = 0.60f
            }
            val h = when (preset) {
                GameplayAspectPreset.LANDSCAPE_16_9 -> w * 81f / 256f
                GameplayAspectPreset.PORTRAIT_9_16 -> w
                GameplayAspectPreset.FREE -> prev.gameplayRect.h
            }
            prev.copy(
                aspectPreset = preset,
                gameplayRect = prev.gameplayRect.copy(w = w, h = h).clamped()
            )
        }
        persistCurrentState()
    }

    fun updateGameplayRect(rect: WindowRectState, saveNow: Boolean) {
        _uiState.update { it.copy(gameplayRect = rect) }
        if (saveNow) persistCurrentState()
    }

    fun updateCharacterRect(rect: WindowRectState, isLandscape: Boolean, saveNow: Boolean) {
        _uiState.update {
            if (isLandscape) {
                it.copy(characterLandscapeRect = rect)
            } else {
                it.copy(characterPortraitRect = rect)
            }
        }
        if (saveNow) persistCurrentState()
    }

    fun resetGameplayWindow() {
        _uiState.update {
            it.copy(
                gameplayRect = WindowRectState.DefaultGameplay,
                aspectPreset = GameplayAspectPreset.LANDSCAPE_16_9,
                displayScaleMode = DisplayScaleMode.FIT
            )
        }
        persistCurrentState()
    }

    fun resetCharacterWindow(isLandscape: Boolean) {
        _uiState.update {
            if (isLandscape) {
                it.copy(characterLandscapeRect = WindowRectState.DefaultCharacterLandscape)
            } else {
                it.copy(characterPortraitRect = WindowRectState.DefaultCharacterPortrait)
            }
        }
        persistCurrentState()
    }

    fun setCameraActive(active: Boolean) {
        runCatching {
            _uiState.update { prev ->
                val validSavedVideo = prev.characterLocalPath
                    ?.takeIf { repo.isValidCharacterVideoFile(it) }
                prev.copy(
                    isCameraActive = active,
                    isCharacterVideoSwitching = false,
                    characterLocalPath = validSavedVideo,
                    characterVideoSwitchNonce = if (!active && validSavedVideo != null) {
                        prev.characterVideoSwitchNonce + 1L
                    } else {
                        prev.characterVideoSwitchNonce
                    },
                    isCharacterPlaying = if (!active && validSavedVideo != null) {
                        true
                    } else {
                        prev.isCharacterPlaying
                    }
                )
            }
            persistCurrentState()
        }
    }

    fun flipCamera() {
        _uiState.update { it.copy(isFrontCamera = !it.isFrontCamera) }
        persistCurrentState()
    }

    fun toggleCharacterPlay() {
        _uiState.update { it.copy(isCharacterPlaying = !it.isCharacterPlaying) }
    }

    fun toggleCharacterSound() {
        _uiState.update { it.copy(isCharacterSoundOn = !it.isCharacterSoundOn) }
        persistCurrentState()
    }

    fun togglePulseOnAiSpeak() {
        _uiState.update { it.copy(isPulseOnAiSpeakEnabled = !it.isPulseOnAiSpeakEnabled) }
        persistCurrentState()
    }

    // --- TikTok LIVE Comment Reader & Sarcastic AI Voice Controls ---

    fun toggleCommentReader() {
        val next = !_uiState.value.isCommentReaderRunning
        LiveCommentBus.setReaderActive(next)
        if (!next) {
            ttsVoiceManager.stopSpeaking()
        } else {
            isSilentlyPausedForRateLimit = false
            val accReady = LiveCommentBus.isAccessibilityConnected.value ||
                isAccessibilityServiceEnabledInSettings(getApplication())
            if (!accReady && !LiveCommentBus.isOcrFallbackRunning.value) {
                _uiState.update { it.copy(isSetupGuideOpen = true) }
            }
        }
        _uiState.update { it.copy(isCommentReaderRunning = next) }
    }

    fun setSetupGuideOpen(open: Boolean) {
        _uiState.update {
            it.copy(
                isSetupGuideOpen = open,
                isAccessibilityServiceConnected = LiveCommentBus.isAccessibilityConnected.value ||
                    isAccessibilityServiceEnabledInSettings(getApplication())
            )
        }
    }

    fun startOcrFallbackService(resultCode: Int, data: Intent) {
        val app = getApplication<Application>()
        val serviceIntent = TikTokOcrFallbackService.createStartIntent(app, resultCode, data)
        ContextCompat.startForegroundService(app, serviceIntent)
    }

    fun stopOcrFallbackService() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, TikTokOcrFallbackService::class.java))
        LiveCommentBus.setOcrFallbackRunning(false)
    }

    fun toggleAiRepliesEnabled() {
        isSilentlyPausedForRateLimit = false
        _uiState.update { it.copy(isAiRepliesEnabled = !it.isAiRepliesEnabled) }
        persistCurrentState()
    }

    fun toggleVoiceTtsEnabled() {
        val next = !_uiState.value.isVoiceTtsEnabled
        if (!next) {
            ttsVoiceManager.stopSpeaking()
        }
        _uiState.update { it.copy(isVoiceTtsEnabled = next) }
        persistCurrentState()
    }

    fun setLlmProvider(provider: LlmProviderChoice) {
        isSilentlyPausedForRateLimit = false
        _uiState.update { it.copy(llmProvider = provider) }
        persistCurrentState()
    }

    fun updateLlmApiKey(key: String) {
        isSilentlyPausedForRateLimit = false
        _uiState.update { it.copy(llmApiKey = key) }
        persistCurrentState()
    }

    fun setSelectedTtsVoice(voiceId: String) {
        _uiState.update { it.copy(selectedTtsVoiceId = voiceId) }
        persistCurrentState()
    }

    fun updateTtsSpeed(speed: Float) {
        _uiState.update { it.copy(ttsSpeed = speed.coerceIn(0.5f, 2.0f)) }
        persistCurrentState()
    }

    fun updateTtsPitch(pitch: Float) {
        _uiState.update { it.copy(ttsPitch = pitch.coerceIn(0.5f, 2.0f)) }
        persistCurrentState()
    }

    fun updateAiCooldownSeconds(seconds: Int) {
        _uiState.update { it.copy(aiCooldownSeconds = seconds.coerceIn(5, 90)) }
        persistCurrentState()
    }

    fun previewSelectedTtsVoice() {
        previewVoiceJob?.cancel()
        ttsVoiceManager.refreshVoices()
        val snap = _uiState.value
        val sample = getApplication<Application>().getString(R.string.tts_preview_sample)
        previewVoiceJob = viewModelScope.launch {
            ttsVoiceManager.speakAndAwait(
                text = sample,
                selectedVoiceId = snap.selectedTtsVoiceId,
                speed = snap.ttsSpeed,
                pitch = snap.ttsPitch
            )
        }
    }

    fun toggleMinimizePanel() {
        _uiState.update { it.copy(isPanelMinimized = !it.isPanelMinimized) }
    }

    fun setBroadcastMode(enabled: Boolean) {
        _uiState.update {
            it.copy(
                isBroadcastMode = enabled,
                engineMode = if (enabled && it.engineMode == PlaybackEngineMode.INTERACTIVE_WEB) {
                    PlaybackEngineMode.WEB_ISOLATOR
                } else {
                    it.engineMode
                }
            )
        }
    }

    fun setBrowserSheetOpen(open: Boolean) {
        _uiState.update { it.copy(isBrowserSheetOpen = open) }
    }

    override fun onCleared() {
        ttsVoiceManager.shutdown()
        super.onCleared()
    }
}
