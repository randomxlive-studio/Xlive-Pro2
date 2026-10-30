package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.Coil
import com.example.R
import com.example.ai.AiReplyConfig
import com.example.ai.AiReplyResult
import com.example.ai.SarcasticAiResponder
import com.example.ai.TtsVoiceManager
import com.example.crash.CrashReportManager
import com.example.data.StudioPreferencesRepository
import com.example.engine.StudioExoPlayerManager
import com.example.engine.UniversalVideoResolver
import com.example.model.BadgeTransformState
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

    val playerManager = StudioExoPlayerManager(application.applicationContext)

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private var resolveJob: Job? = null
    private var bannerJob: Job? = null
    private var previewVoiceJob: Job? = null
    private var debouncedSaveJob: Job? = null

    private val characterSwitchMutex = Mutex()
    private val gameplayResetMutex = Mutex()

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
                    gameplayLocalPath = current.gameplayLocalPath ?: saved.gameplayLocalPath,
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
                        persistCurrentStateNow()

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
                        isSilentlyPausedForRateLimit = true
                        delay(5_000L)
                    }

                    AiReplyResult.SilentFailure -> {
                        val cooldownMs = (_uiState.value.aiCooldownSeconds.coerceIn(3, 120)) * 1000L
                        delay(cooldownMs)
                    }
                }
            }
        }
    }

    /**
     * Immediate persistence for discrete button clicks, media selections, and settings toggles.
     */
    private fun persistCurrentStateNow() {
        if (!isInitialStateLoaded) return
        debouncedSaveJob?.cancel()
        val snapshot = _uiState.value
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repo.saveState(snapshot) }
        }
    }

    /**
     * Debounced persistence for high-frequency touch drag & pinch-zoom gestures so touch handling
     * never allocates DataStore transactions on every frame.
     */
    private fun scheduleDebouncedSave() {
        if (!isInitialStateLoaded) return
        debouncedSaveJob?.cancel()
        debouncedSaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(450L)
            runCatching { repo.saveState(_uiState.value) }
        }
    }

    fun onTrimMemory(level: Int) {
        runCatching { playerManager.trimMemory() }
        runCatching { LiveCommentBus.trimMemory() }
        runCatching {
            Coil.imageLoader(getApplication()).memoryCache?.clear()
        }
    }

    fun onLowMemory() {
        runCatching { playerManager.trimMemory() }
        runCatching { LiveCommentBus.trimMemory() }
        runCatching {
            Coil.imageLoader(getApplication()).memoryCache?.clear()
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
        val hasLocalGameplay = !_uiState.value.gameplayLocalPath.isNullOrBlank()
        if (!hasLocalGameplay) {
            playerManager.releaseGameplayPlayer()
        }
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
        persistCurrentStateNow()
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
            val nextEngine = if (keepCurrentEngine) {
                _uiState.value.engineMode
            } else {
                PlaybackEngineMode.AUTO
            }
            val effectiveNext = if (nextEngine == PlaybackEngineMode.AUTO) {
                resolved.recommendedEngine
            } else {
                nextEngine
            }
            if (effectiveNext != PlaybackEngineMode.DIRECT_STREAM) {
                // Free ExoPlayer memory when Gameplay switches to WebView engine
                playerManager.releaseGameplayPlayer()
            }

            _uiState.update { prev ->
                prev.copy(
                    activeUrl = resolved.resolvedUrl,
                    urlInput = resolved.resolvedUrl,
                    resolvedTarget = resolved,
                    engineMode = nextEngine,
                    isResolvingUrl = false,
                    isGameplayPlaying = true,
                    playerReloadNonce = prev.playerReloadNonce + 1
                )
            }
            persistCurrentStateNow()
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
        val app = getApplication<Application>()
        try {
            repo.tryTakePersistableReadPermission(uri)
        } catch (_: Throwable) {
        }

        viewModelScope.launch {
            gameplayResetMutex.withLock {
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
                                isAdDetectedNow = false,
                                isGameplayPlaying = true,
                                playerReloadNonce = it.playerReloadNonce + 1
                            )
                        }
                        if (isInitialStateLoaded) {
                            runCatching { repo.saveState(_uiState.value) }
                        }
                        showStatus(app.getString(R.string.status_saved_local), true)
                        viewModelScope.launch(Dispatchers.IO) {
                            delay(1000L)
                            repo.cleanupOldSlotMedia("gameplay", _uiState.value.gameplayLocalPath)
                        }
                    }
                }
            }
        }
    }

    /**
     * Safe character video change flow:
     * 1. Takes persistable URI permission where applicable.
     * 2. Stops Camera cleanly and reuses the single character ExoPlayer via setMediaItem/prepare.
     * 3. Streams the picked video in 32KB chunks into internal `filesDir/character_videos` on `Dispatchers.IO`.
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
                            runCatching { repo.saveState(_uiState.value) }
                        }
                        showStatus(app.getString(R.string.status_saved_local), true)

                        viewModelScope.launch(Dispatchers.IO) {
                            delay(1200L)
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
            playerManager.releaseCharacterPlayer()
            _uiState.update {
                it.copy(
                    characterLocalPath = null,
                    isCharacterVideoSwitching = false,
                    isCameraActive = false
                )
            }
            persistCurrentStateNow()
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
            playerManager.releaseCharacterPlayer()
            _uiState.update {
                it.copy(
                    characterLocalPath = null,
                    isCharacterVideoSwitching = false,
                    isCameraActive = false
                )
            }
            persistCurrentStateNow()
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
                    persistCurrentStateNow()
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
            playerManager.releaseGameplayPlayer()
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
            playerManager.releaseGameplayPlayer()
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
        if (firstPlayable == null) {
            playerManager.releaseGameplayPlayer()
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
                sniffedStreams = capturedStreams.take(8),
                selectedSniffedStream = firstPlayable,
                engineMode = PlaybackEngineMode.AUTO,
                isGameplayPlaying = true,
                playerReloadNonce = it.playerReloadNonce + 1
            )
        }
        persistCurrentStateNow()
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
        persistCurrentStateNow()
    }

    fun onAdStateChanged(isAdShowing: Boolean) {
        _uiState.update { it.copy(isAdDetectedNow = isAdShowing) }
    }

    fun setQualityPreference(pref: VideoQualityPreference) {
        _uiState.update { it.copy(qualityPreference = pref) }
        persistCurrentStateNow()
    }

    fun toggleAdBlocker() {
        _uiState.update { it.copy(isAdBlockerEnabled = !it.isAdBlockerEnabled) }
        persistCurrentStateNow()
    }

    fun setEngineMode(mode: PlaybackEngineMode) {
        if (mode != PlaybackEngineMode.DIRECT_STREAM && mode != PlaybackEngineMode.AUTO) {
            playerManager.releaseGameplayPlayer()
        }
        _uiState.update {
            it.copy(
                engineMode = mode,
                playerReloadNonce = it.playerReloadNonce + 1
            )
        }
        persistCurrentStateNow()
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
        persistCurrentStateNow()
    }

    fun reloadPlayer() {
        _uiState.update { it.copy(playerReloadNonce = it.playerReloadNonce + 1) }
    }

    fun updateAccountName(name: String) {
        LiveCommentBus.ownUsernameLower = name.trim().lowercase()
        _uiState.update { it.copy(accountName = name) }
        scheduleDebouncedSave()
    }

    fun toggleGameplayPlay() {
        _uiState.update { it.copy(isGameplayPlaying = !it.isGameplayPlaying) }
    }

    fun toggleGameplaySound() {
        _uiState.update { it.copy(isGameplaySoundOn = !it.isGameplaySoundOn) }
        persistCurrentStateNow()
    }

    fun toggleAutoHideAds() {
        _uiState.update {
            val next = !it.isAutoHideAdsOn
            it.copy(
                isAutoHideAdsOn = next,
                isAdDetectedNow = if (!next) false else it.isAdDetectedNow
            )
        }
        persistCurrentStateNow()
    }

    fun toggleManualCover() {
        _uiState.update { it.copy(isManualCoverOn = !it.isManualCoverOn) }
    }

    fun toggleVoiceReduce() {
        _uiState.update { it.copy(isVoiceReduceOn = !it.isVoiceReduceOn) }
        persistCurrentStateNow()
    }

    fun setDisplayScaleMode(mode: DisplayScaleMode) {
        _uiState.update { it.copy(displayScaleMode = mode) }
        persistCurrentStateNow()
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
        persistCurrentStateNow()
    }

    fun updateGameplayRect(rect: WindowRectState, saveNow: Boolean) {
        _uiState.update { it.copy(gameplayRect = rect) }
        if (saveNow) persistCurrentStateNow() else scheduleDebouncedSave()
    }

    fun updateCharacterRect(rect: WindowRectState, isLandscape: Boolean, saveNow: Boolean) {
        _uiState.update {
            if (isLandscape) {
                it.copy(characterLandscapeRect = rect)
            } else {
                it.copy(characterPortraitRect = rect)
            }
        }
        if (saveNow) persistCurrentStateNow() else scheduleDebouncedSave()
    }

    fun updateBadgeTransform(
        transform: BadgeTransformState,
        isLandscape: Boolean,
        saveNow: Boolean
    ) {
        val clamped = transform.clamped()
        _uiState.update {
            if (isLandscape) {
                it.copy(badgeLandscapeState = clamped)
            } else {
                it.copy(badgePortraitState = clamped)
            }
        }
        if (saveNow) persistCurrentStateNow() else scheduleDebouncedSave()
    }

    /**
     * Permanently deletes the Gameplay video/link/browser page ONLY (leaves Circle & Badge untouched):
     * 1. Cancels URL resolution, stops, clears, detaches, and releases Gameplay ExoPlayer memory.
     * 2. Clears local video path, pasted link, browser page, and sniffed streams, showing empty state.
     * 3. Resets Gameplay window position & size to defaults.
     * 4. Deletes copied internal gameplay file(s) from filesDir/cacheDir on Dispatchers.IO inside try/catch
     *    and removes saved path/URL from DataStore so it never returns after restart.
     * 5. Shows Toast("Gameplay video deleted").
     */
    fun resetGameplay() {
        val app = getApplication<Application>()
        resolveJob?.cancel()
        debouncedSaveJob?.cancel()

        val oldPath = _uiState.value.gameplayLocalPath
        playerManager.releaseGameplayPlayer()

        _uiState.update { prev ->
            prev.copy(
                gameplayLocalPath = null,
                urlInput = "",
                activeUrl = "",
                resolvedTarget = null,
                sniffedStreams = emptyList(),
                selectedSniffedStream = null,
                isResolvingUrl = false,
                isAdDetectedNow = false,
                isManualCoverOn = false,
                isGameplayPlaying = false,
                engineMode = PlaybackEngineMode.AUTO,
                gameplayRect = WindowRectState.DefaultGameplay,
                aspectPreset = GameplayAspectPreset.LANDSCAPE_16_9,
                displayScaleMode = DisplayScaleMode.FIT,
                playerReloadNonce = prev.playerReloadNonce + 1
            )
        }

        val toastText = app.getString(R.string.toast_gameplay_deleted)
        runCatching {
            Toast.makeText(app, toastText, Toast.LENGTH_SHORT).show()
        }
        showStatus(toastText, true)

        val snapshotAfterReset = _uiState.value
        viewModelScope.launch(Dispatchers.IO) {
            gameplayResetMutex.withLock {
                try {
                    repo.deleteGameplayMediaAndClearPrefs(oldPath)
                    if (isInitialStateLoaded) {
                        repo.saveState(snapshotAfterReset)
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * Permanently deletes the Circle video or stops Camera ONLY (leaves Gameplay & Badge untouched):
     * 1. Stops, clears, detaches, and releases Circle ExoPlayer memory, and sets isCameraActive = false
     *    so CameraX immediately calls unbindAll().
     * 2. Clears saved character video path and shows the circular empty placeholder.
     * 3. Resets Circle window position & size to defaults (both portrait & landscape).
     * 4. Deletes copied internal circle video file(s) from filesDir/cacheDir on Dispatchers.IO inside try/catch
     *    and clears DataStore so it never returns after restart.
     * 5. Shows Toast("Circle video deleted").
     */
    fun resetCircle() {
        val app = getApplication<Application>()
        debouncedSaveJob?.cancel()

        val oldPath = _uiState.value.characterLocalPath
        playerManager.releaseCharacterPlayer()

        _uiState.update { prev ->
            prev.copy(
                characterLocalPath = null,
                isCharacterVideoSwitching = false,
                isCameraActive = false,
                isCharacterPlaying = false,
                characterVideoSwitchNonce = prev.characterVideoSwitchNonce + 1L,
                characterPortraitRect = WindowRectState.DefaultCharacterPortrait,
                characterLandscapeRect = WindowRectState.DefaultCharacterLandscape
            )
        }

        val toastText = app.getString(R.string.toast_circle_deleted)
        runCatching {
            Toast.makeText(app, toastText, Toast.LENGTH_SHORT).show()
        }
        showStatus(toastText, true)

        val snapshotAfterReset = _uiState.value
        viewModelScope.launch(Dispatchers.IO) {
            characterSwitchMutex.withLock {
                try {
                    repo.deleteCharacterMediaAndClearPrefs(oldPath)
                    if (isInitialStateLoaded) {
                        repo.saveState(snapshotAfterReset)
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * Resets ONLY the Streamer Account Badge position and scale back to portrait & landscape defaults.
     * Deletes nothing and leaves Gameplay & Circle untouched.
     */
    fun resetBadge() {
        val app = getApplication<Application>()
        _uiState.update {
            it.copy(
                badgePortraitState = BadgeTransformState.DefaultPortrait,
                badgeLandscapeState = BadgeTransformState.DefaultLandscape
            )
        }
        persistCurrentStateNow()
        val msg = app.getString(R.string.toast_badge_reset)
        runCatching {
            Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
        }
        showStatus(msg, true)
    }

    fun setCameraActive(active: Boolean) {
        runCatching {
            if (active) {
                // Camera and Video NEVER run at the same time: release video player memory first
                playerManager.releaseCharacterPlayer()
            }
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
            persistCurrentStateNow()
        }
    }

    fun flipCamera() {
        _uiState.update { it.copy(isFrontCamera = !it.isFrontCamera) }
        persistCurrentStateNow()
    }

    fun toggleCharacterPlay() {
        _uiState.update { it.copy(isCharacterPlaying = !it.isCharacterPlaying) }
    }

    fun toggleCharacterSound() {
        _uiState.update { it.copy(isCharacterSoundOn = !it.isCharacterSoundOn) }
        persistCurrentStateNow()
    }

    fun togglePulseOnAiSpeak() {
        _uiState.update { it.copy(isPulseOnAiSpeakEnabled = !it.isPulseOnAiSpeakEnabled) }
        persistCurrentStateNow()
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
        persistCurrentStateNow()
    }

    fun toggleVoiceTtsEnabled() {
        val next = !_uiState.value.isVoiceTtsEnabled
        if (!next) {
            ttsVoiceManager.stopSpeaking()
        }
        _uiState.update { it.copy(isVoiceTtsEnabled = next) }
        persistCurrentStateNow()
    }

    fun setLlmProvider(provider: LlmProviderChoice) {
        isSilentlyPausedForRateLimit = false
        _uiState.update { it.copy(llmProvider = provider) }
        persistCurrentStateNow()
    }

    fun updateLlmApiKey(key: String) {
        isSilentlyPausedForRateLimit = false
        _uiState.update { it.copy(llmApiKey = key) }
        scheduleDebouncedSave()
    }

    fun setSelectedTtsVoice(voiceId: String) {
        _uiState.update { it.copy(selectedTtsVoiceId = voiceId) }
        persistCurrentStateNow()
    }

    fun updateTtsSpeed(speed: Float) {
        _uiState.update { it.copy(ttsSpeed = speed.coerceIn(0.5f, 2.0f)) }
        scheduleDebouncedSave()
    }

    fun updateTtsPitch(pitch: Float) {
        _uiState.update { it.copy(ttsPitch = pitch.coerceIn(0.5f, 2.0f)) }
        scheduleDebouncedSave()
    }

    fun updateAiCooldownSeconds(seconds: Int) {
        _uiState.update { it.copy(aiCooldownSeconds = seconds.coerceIn(5, 90)) }
        scheduleDebouncedSave()
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
        debouncedSaveJob?.cancel()
        playerManager.releaseAll()
        ttsVoiceManager.shutdown()
        super.onCleared()
    }
}
