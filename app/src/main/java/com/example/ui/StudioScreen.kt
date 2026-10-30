package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.engine.ExoVideoPlayerView
import com.example.engine.StudioExoPlayerManager
import com.example.engine.UniversalWebPlayerView
import com.example.model.BadgeTransformState
import com.example.model.PlaybackEngineMode
import com.example.model.SniffedStream
import com.example.model.StudioUiState
import com.example.ui.components.CharacterCircleMediaView
import com.example.ui.components.CrashReportDialog
import com.example.ui.components.DraggableStudioWindow
import com.example.ui.components.PlatformBrowserSheet
import com.example.ui.components.StudioControlPanel
import com.example.ui.components.TikTokAiSetupDialog
import com.example.ui.theme.LivePulseRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.SpaceGroteskFamily
import com.example.ui.theme.StudioDeepNavy
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.StudioVoid
import java.io.File
import kotlin.math.min as kotlinMin
import kotlin.math.roundToInt

@Composable
fun StudioScreen(
    viewModel: StudioViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Return to Edit Mode on Back press when inside Broadcast Mode
    BackHandler(enabled = state.isBroadcastMode) {
        viewModel.setBroadcastMode(false)
    }

    // Zero-permission Android Photo Pickers for Header Avatar, Gameplay video, and Character video (VideoOnly)
    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching { viewModel.handlePickedAvatarImage(uri) }
        }
    }

    val gameplayVideoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching { viewModel.handlePickedGameplayVideo(uri) }
        }
    }

    val characterVideoDocumentFallback = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Throwable) {
                }
                viewModel.handlePickedCharacterVideo(uri)
            } catch (t: Throwable) {
                val msg = context.getString(
                    R.string.character_video_copy_failed,
                    t.localizedMessage ?: t.javaClass.simpleName
                )
                runCatching { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
            }
        }
    }

    val characterVideoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Throwable) {
                }
                viewModel.handlePickedCharacterVideo(uri)
            } catch (t: Throwable) {
                val msg = context.getString(
                    R.string.character_video_copy_failed,
                    t.localizedMessage ?: t.javaClass.simpleName
                )
                runCatching { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
            }
        }
    }

    fun launchCharacterVideoPickerSafely() {
        try {
            characterVideoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        } catch (_: Throwable) {
            try {
                characterVideoDocumentFallback.launch(arrayOf("video/*"))
            } catch (t: Throwable) {
                val msg = context.getString(
                    R.string.character_video_copy_failed,
                    t.localizedMessage ?: t.javaClass.simpleName
                )
                runCatching { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.setCameraActive(true)
        } else {
            viewModel.showStatus(context.getString(R.string.camera_permission_denied), false)
        }
    }

    // MediaProjection launcher for optional ML Kit OCR comment-area fallback
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            viewModel.startOcrFallbackService(result.resultCode, data)
        }
    }

    fun toggleOcrFallbackWithConsent() {
        if (state.isOcrFallbackRunning) {
            viewModel.stopOcrFallbackService()
        } else {
            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
        }
    }

    // Runs ONLY while AI is actively speaking — zero infinite animation overhead when idle
    val effectiveCirclePulse = rememberAiSpeechPulseScale(
        active = state.isAiSpeaking && state.isPulseOnAiSpeakEnabled
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val totalWidthDp = maxWidth
        val totalHeightDp = maxHeight
        val isLandscape = totalWidthDp >= totalHeightDp

        val stageHeightDp = if (isLandscape) {
            totalHeightDp
        } else {
            min(totalHeightDp, totalWidthDp * 16f / 9f)
        }
        val stageWidthDp = if (isLandscape) {
            totalWidthDp
        } else {
            stageHeightDp * 9f / 16f
        }

        val density = LocalDensity.current
        val stageWidthPx = with(density) { stageWidthDp.toPx() }
        val stageHeightPx = with(density) { stageHeightDp.toPx() }

        // Main Broadcast Stage (#scene) — zero AI text bubbles or overlays anywhere on screen
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(width = stageWidthDp, height = stageHeightDp)
                .background(StudioVoid)
                .then(
                    if (!isLandscape) {
                        Modifier
                            .padding(2.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(
                                width = 1.5.dp,
                                color = NeonCyan.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(16.dp)
                            )
                    } else {
                        Modifier
                    }
                )
                .testTag("studio_scene_stage")
        ) {
            // Cached Neon Cyber Grid Background (zero per-frame shader allocations)
            StaticCyberGridBackground(isLandscape = isLandscape)

            // 1. GAMEPLAY / UNIVERSAL VIDEO WINDOW (#gwin)
            DraggableStudioWindow(
                rect = state.gameplayRect,
                stageWidthPx = stageWidthPx,
                stageHeightPx = stageHeightPx,
                stageWidthDp = stageWidthDp,
                stageHeightDp = stageHeightDp,
                isEditMode = !state.isBroadcastMode,
                isFullBleedLocked = isLandscape,
                allowTouchInsideContent = state.effectiveEngine == PlaybackEngineMode.INTERACTIVE_WEB &&
                    !state.isBroadcastMode,
                borderColor = NeonCyan,
                aspectConstraint = state.aspectPreset,
                windowTestTag = "gameplay_window",
                isCircular = false,
                onRectChange = { rect, save -> viewModel.updateGameplayRect(rect, save) }
            ) {
                GameplayMediaContent(
                    playerManager = viewModel.playerManager,
                    state = state,
                    onEmbedBlocked = viewModel::onEmbedBlockedOrFailed,
                    onDirectStreamError = viewModel::onDirectStreamFailed,
                    onStreamSniffed = viewModel::onStreamSniffed,
                    onAdStateChanged = viewModel::onAdStateChanged
                )
            }

            // 2. PERFECT CIRCULAR CHARACTER / LIVE CAMERA WINDOW (#cwin) — rendered above gameplay
            val charRect = if (isLandscape) state.characterLandscapeRect else state.characterPortraitRect
            DraggableStudioWindow(
                rect = charRect,
                stageWidthPx = stageWidthPx,
                stageHeightPx = stageHeightPx,
                stageWidthDp = stageWidthDp,
                stageHeightDp = stageHeightDp,
                isEditMode = !state.isBroadcastMode,
                isFullBleedLocked = false,
                allowTouchInsideContent = false,
                borderColor = Color.Transparent,
                aspectConstraint = null,
                windowTestTag = "character_window",
                isCircular = true,
                pulseScale = effectiveCirclePulse,
                onRectChange = { rect, save ->
                    viewModel.updateCharacterRect(rect, isLandscape, save)
                }
            ) {
                CharacterCircleMediaView(
                    playerManager = viewModel.playerManager,
                    isCameraActive = state.isCameraActive,
                    isFrontCamera = state.isFrontCamera,
                    videoFilePath = state.characterLocalPath,
                    videoSwitchNonce = state.characterVideoSwitchNonce,
                    isPlaying = state.isCharacterPlaying,
                    isMuted = !state.isCharacterSoundOn,
                    isLoadingVideo = state.isCharacterVideoSwitching,
                    isBroadcastMode = state.isBroadcastMode,
                    onVideoPlaybackError = viewModel::onCharacterVideoPlaybackError,
                    onMissingVideoFile = viewModel::onCharacterVideoFileMissing,
                    onCameraFailure = viewModel::onCharacterCameraError,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 3. MOVABLE & RESIZABLE STREAMER ACCOUNT BADGE (#hdr) — rendered above video,
            // 1-finger drag + 2-finger pinch scale (0.5x..2.5x), clamped strictly inside stage bounds
            val badgeTransform = if (isLandscape) {
                state.badgeLandscapeState
            } else {
                state.badgePortraitState
            }
            MovableStreamerHeaderBadge(
                accountName = state.accountName,
                avatarPath = state.avatarLocalPath,
                transform = badgeTransform,
                isLandscape = isLandscape,
                isEditMode = !state.isBroadcastMode,
                stageWidthPx = stageWidthPx,
                stageHeightPx = stageHeightPx,
                onTransformChange = { updated, saveNow ->
                    viewModel.updateBadgeTransform(updated, isLandscape, saveNow)
                }
            )
        }

        // Floating subtle Broadcast Controls when in Broadcast Mode (#gear & #gear2)
        if (state.isBroadcastMode) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(10.dp)
            ) {
                IconButton(
                    onClick = { viewModel.setBroadcastMode(false) },
                    modifier = Modifier
                        .size(48.dp)
                        .alpha(0.38f)
                        .clip(CircleShape)
                        .background(StudioDeepNavy)
                        .border(1.dp, NeonCyan, CircleShape)
                        .testTag("exit_broadcast_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.cd_exit_broadcast),
                        tint = StudioTextPrimary
                    )
                }

                IconButton(
                    onClick = viewModel::toggleManualCover,
                    modifier = Modifier
                        .size(48.dp)
                        .alpha(0.38f)
                        .clip(CircleShape)
                        .background(StudioDeepNavy)
                        .border(1.dp, NeonPurple, CircleShape)
                        .testTag("broadcast_cover_toggle_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = stringResource(R.string.cd_toggle_cover),
                        tint = if (state.isManualCoverOn) NeonCyan else StudioTextPrimary
                    )
                }
            }
        }

        // Studio Control Panel (Docked Bottom in Portrait, Docked End in Landscape)
        if (!state.isBroadcastMode) {
            StudioControlPanel(
                state = state,
                isLandscape = isLandscape,
                maxPortraitHeight = totalHeightDp * 0.48f,
                onToggleMinimize = viewModel::toggleMinimizePanel,
                onStartBroadcast = { viewModel.setBroadcastMode(true) },
                onAccountNameChange = viewModel::updateAccountName,
                onPickAvatar = {
                    avatarPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onUrlInputChange = viewModel::onUrlInputChange,
                onPasteAndLoad = {
                    val clip = clipboardManager.getText()?.text.orEmpty().trim()
                    if (clip.isNotEmpty()) {
                        viewModel.onUrlInputChange(clip)
                        viewModel.loadVideoLink(clip)
                    } else {
                        viewModel.showStatus(
                            context.getString(R.string.status_clipboard_empty),
                            false
                        )
                    }
                },
                onLoadUrl = { viewModel.loadVideoLink() },
                onClearUrl = viewModel::clearUrlAndReturnToLocal,
                onOpenBrowserSheet = { viewModel.setBrowserSheetOpen(true) },
                onSelectQuality = viewModel::setQualityPreference,
                onToggleAdBlocker = viewModel::toggleAdBlocker,
                onSelectEngine = viewModel::setEngineMode,
                onToggleUserAgent = viewModel::toggleUserAgentMode,
                onSelectSniffedStream = viewModel::selectSniffedStream,
                onPickGameplayVideo = {
                    gameplayVideoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                },
                onToggleGameplayPlay = viewModel::toggleGameplayPlay,
                onToggleGameplaySound = viewModel::toggleGameplaySound,
                onReloadPlayer = viewModel::reloadPlayer,
                onToggleAutoHideAds = viewModel::toggleAutoHideAds,
                onToggleManualCover = viewModel::toggleManualCover,
                onToggleVoiceReduce = viewModel::toggleVoiceReduce,
                onSelectScaleMode = viewModel::setDisplayScaleMode,
                onSelectAspectPreset = viewModel::setAspectPreset,
                onPickCharacterVideo = { launchCharacterVideoPickerSafely() },
                onToggleCamera = {
                    runCatching {
                        if (state.isCameraActive) {
                            viewModel.setCameraActive(false)
                        } else {
                            val hasPerm = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPerm) {
                                viewModel.setCameraActive(true)
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }
                    }
                },
                onFlipCamera = viewModel::flipCamera,
                onToggleCharacterPlay = viewModel::toggleCharacterPlay,
                onToggleCharacterSound = viewModel::toggleCharacterSound,
                onTogglePulseOnSpeak = viewModel::togglePulseOnAiSpeak,
                onToggleCommentReader = viewModel::toggleCommentReader,
                onOpenSetupGuide = { viewModel.setSetupGuideOpen(true) },
                onToggleOcrFallback = { toggleOcrFallbackWithConsent() },
                onToggleAiReplies = viewModel::toggleAiRepliesEnabled,
                onToggleVoiceTts = viewModel::toggleVoiceTtsEnabled,
                onSelectLlmProvider = viewModel::setLlmProvider,
                onApiKeyChange = viewModel::updateLlmApiKey,
                onSelectTtsVoice = viewModel::setSelectedTtsVoice,
                onPreviewTtsVoice = viewModel::previewSelectedTtsVoice,
                onTtsSpeedChange = viewModel::updateTtsSpeed,
                onTtsPitchChange = viewModel::updateTtsPitch,
                onAiCooldownChange = viewModel::updateAiCooldownSeconds,
                onResetGameplay = viewModel::resetGameplay,
                onResetCharacter = viewModel::resetCircle,
                onResetBadge = viewModel::resetBadge,
                modifier = Modifier.align(
                    if (isLandscape) Alignment.BottomEnd else Alignment.BottomCenter
                )
            )
        }

        // Top Toast / Status Banner (for manual link/file actions in Edit Mode only)
        AnimatedVisibility(
            visible = state.statusBannerMessage != null && !state.isBroadcastMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 10.dp, start = 16.dp, end = 16.dp)
        ) {
            val msg = state.statusBannerMessage.orEmpty()
            val borderCol = if (state.isStatusSuccess) NeonEmerald else LivePulseRed
            Box(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(StudioDeepNavy.copy(alpha = 0.95f))
                    .border(1.dp, borderCol, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("status_toast_banner")
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioTextPrimary,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Built-in Universal Platform Browser & Video Grabber Sheet
        if (state.isBrowserSheetOpen) {
            PlatformBrowserSheet(
                initialUrl = state.activeUrl.ifBlank { state.urlInput },
                isAdBlockerEnabled = state.isAdBlockerEnabled,
                onDismiss = { viewModel.setBrowserSheetOpen(false) },
                onSelectPageForIsolator = viewModel::applyFromBrowserIsolator,
                onSelectDirectStream = viewModel::applyFromBrowserDirectStream
            )
        }

        // Accessibility Service & Overlay Permission Setup Guide Dialog
        if (state.isSetupGuideOpen) {
            TikTokAiSetupDialog(
                isAccessibilityConnected = state.isAccessibilityServiceConnected,
                isOcrFallbackRunning = state.isOcrFallbackRunning,
                onToggleOcrFallback = { toggleOcrFallbackWithConsent() },
                onDismiss = { viewModel.setSetupGuideOpen(false) }
            )
        }

        // Next-Launch Global Crash Report Dialog (with Copy button)
        val crashLog = state.pendingCrashReport
        if (!crashLog.isNullOrBlank()) {
            CrashReportDialog(
                crashReport = crashLog,
                onDismissAndClear = viewModel::dismissCrashReport
            )
        }
    }
}

@Composable
private fun rememberAiSpeechPulseScale(active: Boolean): Float {
    if (!active) return 1.0f
    val pulseTransition = rememberInfiniteTransition(label = "ai_speech_pulse")
    val animatedSpeechScale by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.045f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 360, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "circle_speech_scale"
    )
    return animatedSpeechScale
}

@Composable
private fun GameplayMediaContent(
    playerManager: StudioExoPlayerManager,
    state: StudioUiState,
    onEmbedBlocked: (String) -> Unit,
    onDirectStreamError: (String) -> Unit,
    onStreamSniffed: (SniffedStream) -> Unit,
    onAdStateChanged: (Boolean) -> Unit
) {
    val shouldMuteActual = !state.isGameplaySoundOn || state.isCoverVisible
    val isAudioDucked = state.isAiSpeaking

    Box(modifier = Modifier.fillMaxSize()) {
        val target = state.resolvedTarget
        val effectiveEngine = state.effectiveEngine

        when {
            // Case 1: Active Online URL
            state.activeUrl.isNotBlank() && target != null -> {
                val directUrl = state.selectedSniffedStream?.url ?: target.primaryStreamUrl
                val directHeaders = remember(state.selectedSniffedStream, target) {
                    if (state.selectedSniffedStream != null) {
                        mapOf(
                            "Referer" to state.selectedSniffedStream.referer.ifBlank { target.resolvedUrl },
                            "User-Agent" to state.selectedSniffedStream.userAgent
                        )
                    } else {
                        target.requestHeaders
                    }
                }

                if (effectiveEngine == PlaybackEngineMode.DIRECT_STREAM && !directUrl.isNullOrBlank()) {
                    ExoVideoPlayerView(
                        playerManager = playerManager,
                        sourceUriOrUrl = directUrl,
                        headers = directHeaders,
                        isPlaying = state.isGameplayPlaying,
                        isMuted = shouldMuteActual,
                        voiceReduceEnabled = state.isVoiceReduceOn,
                        scaleMode = state.displayScaleMode,
                        reloadNonce = state.playerReloadNonce,
                        qualityPreference = state.qualityPreference,
                        isAudioDucked = isAudioDucked,
                        onStreamError = onDirectStreamError,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    UniversalWebPlayerView(
                        target = target,
                        engineMode = effectiveEngine,
                        userAgentMode = state.userAgentMode,
                        qualityPreference = state.qualityPreference,
                        isAdBlockerEnabled = state.isAdBlockerEnabled,
                        isPlaying = state.isGameplayPlaying,
                        isMuted = shouldMuteActual,
                        isAudioDucked = isAudioDucked,
                        scaleMode = state.displayScaleMode,
                        reloadNonce = state.playerReloadNonce,
                        onEmbedBlocked = onEmbedBlocked,
                        onStreamSniffed = onStreamSniffed,
                        onAdStateChanged = onAdStateChanged,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Case 2: Local Uploaded Gameplay File
            !state.gameplayLocalPath.isNullOrBlank() -> {
                ExoVideoPlayerView(
                    playerManager = playerManager,
                    sourceUriOrUrl = state.gameplayLocalPath,
                    headers = emptyMap(),
                    isPlaying = state.isGameplayPlaying,
                    isMuted = shouldMuteActual,
                    voiceReduceEnabled = state.isVoiceReduceOn,
                    scaleMode = state.displayScaleMode,
                    reloadNonce = state.playerReloadNonce,
                    qualityPreference = state.qualityPreference,
                    isAudioDucked = isAudioDucked,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Case 3: Empty Placeholder (Hidden in Broadcast Mode)
            !state.isBroadcastMode -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .testTag("gameplay_empty_placeholder")
                ) {
                    Icon(
                        imageVector = Icons.Default.LiveTv,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.gameplay_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = NeonEmerald,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.gameplay_empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioTextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Loading indicator while probing redirects/extractors
        if (state.isResolvingUrl) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(StudioVoid.copy(alpha = 0.72f))
            ) {
                CircularProgressIndicator(color = NeonCyan)
            }
        }

        // BE RIGHT BACK Cover Shield (#cover)
        if (state.isCoverVisible) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val coverBrush = Brush.radialGradient(
                            colors = listOf(Color(0xFF1B0F3D), StudioVoid)
                        )
                        onDrawBehind {
                            drawRect(coverBrush)
                        }
                    }
                    .testTag("be_right_back_cover")
            ) {
                Text(
                    text = stringResource(R.string.be_right_back),
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    letterSpacing = 5.sp,
                    color = NeonCyan,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun MovableStreamerHeaderBadge(
    accountName: String,
    avatarPath: String?,
    transform: BadgeTransformState,
    isLandscape: Boolean,
    isEditMode: Boolean,
    stageWidthPx: Float,
    stageHeightPx: Float,
    onTransformChange: (BadgeTransformState, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val currentTransform by rememberUpdatedState(transform)
    val currentOnTransformChange by rememberUpdatedState(onTransformChange)

    val defaultBaseWidthPx = with(density) { 172.dp.toPx() }
    val defaultBaseHeightPx = with(density) { 50.dp.toPx() }
    var measuredBaseSize by remember { mutableStateOf(IntSize.Zero) }

    val baseWidthPx = if (measuredBaseSize.width > 0) {
        measuredBaseSize.width.toFloat()
    } else {
        defaultBaseWidthPx
    }
    val baseHeightPx = if (measuredBaseSize.height > 0) {
        measuredBaseSize.height.toFloat()
    } else {
        defaultBaseHeightPx
    }

    // Limit scale between 0.5x and 2.5x while guaranteeing the scaled badge fits inside the stage
    val maxScaleThatFitsStage = if (stageWidthPx > 1f && stageHeightPx > 1f) {
        kotlinMin(
            BadgeTransformState.MAX_SCALE,
            kotlinMin(stageWidthPx / baseWidthPx, stageHeightPx / baseHeightPx)
                .coerceAtLeast(BadgeTransformState.MIN_SCALE)
        )
    } else {
        BadgeTransformState.MAX_SCALE
    }

    val effectiveScale = transform.scale.coerceIn(
        BadgeTransformState.MIN_SCALE,
        maxScaleThatFitsStage
    )

    val scaledWidthPx = baseWidthPx * effectiveScale
    val scaledHeightPx = baseHeightPx * effectiveScale

    val maxOffsetX = (stageWidthPx - scaledWidthPx).coerceAtLeast(0f)
    val maxOffsetY = (stageHeightPx - scaledHeightPx).coerceAtLeast(0f)

    val clampedXPx = (transform.x * stageWidthPx).coerceIn(0f, maxOffsetX)
    val clampedYPx = (transform.y * stageHeightPx).coerceIn(0f, maxOffsetY)

    val touchBoxWidthDp = with(density) { scaledWidthPx.coerceAtLeast(48.dp.toPx()).toDp() }
    val touchBoxHeightDp = with(density) { scaledHeightPx.coerceAtLeast(48.dp.toPx()).toDp() }

    val pillShape = remember { RoundedCornerShape(50) }
    val pillBrush = remember {
        Brush.horizontalGradient(listOf(Color(0xDD0B1430), Color(0xDD1A0B3A)))
    }
    val avatarModel = remember(context, avatarPath) {
        val validFile = avatarPath?.takeIf { it.isNotBlank() }?.let(::File)
        if (validFile != null && validFile.exists()) {
            ImageRequest.Builder(context)
                .data(validFile)
                .size(256)
                .crossfade(false)
                .build()
        } else {
            null
        }
    }

    val gestureModifier = if (isEditMode) {
        Modifier.pointerInput(isLandscape, stageWidthPx, stageHeightPx, baseWidthPx, baseHeightPx) {
            if (stageWidthPx <= 1f || stageHeightPx <= 1f) return@pointerInput
            detectTransformGestures { _, pan, zoom, _ ->
                val cur = currentTransform
                val fitCap = kotlinMin(
                    BadgeTransformState.MAX_SCALE,
                    kotlinMin(stageWidthPx / baseWidthPx, stageHeightPx / baseHeightPx)
                        .coerceAtLeast(BadgeTransformState.MIN_SCALE)
                )
                val prevScale = cur.scale.coerceIn(BadgeTransformState.MIN_SCALE, fitCap)
                val nextScale = (prevScale * zoom).coerceIn(BadgeTransformState.MIN_SCALE, fitCap)

                val prevXPx = (cur.x * stageWidthPx)
                    .coerceIn(0f, (stageWidthPx - baseWidthPx * prevScale).coerceAtLeast(0f))
                val prevYPx = (cur.y * stageHeightPx)
                    .coerceIn(0f, (stageHeightPx - baseHeightPx * prevScale).coerceAtLeast(0f))

                // Anchor pinch zoom subtly around badge center + apply 1-finger or 2-finger pan
                val scaleDeltaW = baseWidthPx * (nextScale - prevScale) * 0.5f
                val scaleDeltaH = baseHeightPx * (nextScale - prevScale) * 0.5f

                val nextMaxX = (stageWidthPx - baseWidthPx * nextScale).coerceAtLeast(0f)
                val nextMaxY = (stageHeightPx - baseHeightPx * nextScale).coerceAtLeast(0f)

                val nextXPx = (prevXPx + pan.x - scaleDeltaW).coerceIn(0f, nextMaxX)
                val nextYPx = (prevYPx + pan.y - scaleDeltaH).coerceIn(0f, nextMaxY)

                val updated = BadgeTransformState(
                    x = (nextXPx / stageWidthPx).coerceIn(0f, 1f),
                    y = (nextYPx / stageHeightPx).coerceIn(0f, 1f),
                    scale = nextScale
                )
                currentOnTransformChange(updated, false)
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    x = clampedXPx.roundToInt(),
                    y = clampedYPx.roundToInt()
                )
            }
            .size(width = touchBoxWidthDp, height = touchBoxHeightDp)
            .then(gestureModifier)
            .testTag("streamer_header_badge")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .onSizeChanged { size ->
                    if (size.width > 0 && size.height > 0 && size != measuredBaseSize) {
                        measuredBaseSize = size
                    }
                }
                .graphicsLayer {
                    scaleX = effectiveScale
                    scaleY = effectiveScale
                    transformOrigin = TransformOrigin(0f, 0f)
                    compositingStrategy = CompositingStrategy.Auto
                }
                .widthIn(max = 260.dp)
                .shadow(10.dp, pillShape, spotColor = NeonPurple)
                .clip(pillShape)
                .background(pillBrush)
                .border(1.5.dp, NeonPurple, pillShape)
                .padding(start = 6.dp, end = 14.dp, top = 5.dp, bottom = 5.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF11182E))
                    .border(2.dp, NeonCyan, CircleShape)
            ) {
                if (avatarModel != null) {
                    AsyncImage(
                        model = avatarModel,
                        contentDescription = stringResource(R.string.cd_profile_avatar),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = stringResource(R.string.cd_profile_avatar),
                        tint = NeonCyan,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Text(
                    text = accountName.ifBlank { " " },
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = StudioTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LivePulseDot()
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = stringResource(R.string.live_badge),
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 1.6.sp,
                        color = LivePulseRed
                    )
                }
            }
        }
    }
}

@Composable
private fun LivePulseDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "live_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            .graphicsLayer { alpha = pulseAlpha }
            .clip(CircleShape)
            .background(LivePulseRed)
    )
}

@Composable
private fun StaticCyberGridBackground(isLandscape: Boolean) {
    Spacer(
        modifier = Modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val topGlow = Brush.radialGradient(
                    colors = listOf(Color(0xFF1B0F3D), Color.Transparent),
                    center = Offset(w * 0.5f, 0f),
                    radius = maxOf(w, h) * 0.65f
                )
                val bottomGlow = Brush.radialGradient(
                    colors = listOf(Color(0xFF032A3A), Color.Transparent),
                    center = Offset(w * 0.5f, h),
                    radius = maxOf(w, h) * 0.55f
                )
                val cell = (minOf(w, h) * 0.09f).coerceAtLeast(24f)
                val gridAlpha = if (isLandscape) 0.08f else 0.16f
                val lineColor = NeonCyan.copy(alpha = gridAlpha)

                onDrawBehind {
                    drawRect(brush = topGlow)
                    drawRect(brush = bottomGlow)

                    var x = 0f
                    while (x <= w) {
                        drawLine(
                            color = lineColor,
                            start = Offset(x, 0f),
                            end = Offset(x, h),
                            strokeWidth = 1f
                        )
                        x += cell
                    }

                    var y = 0f
                    while (y <= h) {
                        drawLine(
                            color = lineColor,
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1f
                        )
                        y += cell
                    }
                }
            }
    )
}
