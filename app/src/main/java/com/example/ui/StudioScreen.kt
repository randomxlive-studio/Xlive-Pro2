package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.R
import com.example.engine.ExoVideoPlayerView
import com.example.engine.UniversalWebPlayerView
import com.example.model.DisplayScaleMode
import com.example.model.PlaybackEngineMode
import com.example.model.SniffedStream
import com.example.model.StudioUiState
import com.example.ui.components.CameraCharacterView
import com.example.ui.components.DraggableStudioWindow
import com.example.ui.components.PlatformBrowserSheet
import com.example.ui.components.StudioControlPanel
import com.example.ui.components.TikTokAiSetupDialog
import com.example.ui.theme.LivePulseRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.NeonPink
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.SpaceGroteskFamily
import com.example.ui.theme.StudioDeepNavy
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.StudioVoid
import java.io.File

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

    // Zero-permission Android Photo Pickers for Avatar, Gameplay video, and Character image/video
    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.handlePickedAvatarImage(uri)
    }

    val gameplayVideoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.handlePickedGameplayVideo(uri)
    }

    val characterMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.handlePickedCharacterMedia(uri)
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

    // Very subtle scale pulse on the circular character/camera view ONLY while AI is speaking
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
    val effectiveCirclePulse = if (state.isAiSpeaking && state.isPulseOnAiSpeakEnabled) {
        animatedSpeechScale
    } else {
        1.0f
    }

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
            // Animated Neon Cyber Grid Background
            AnimatedCyberGridBackground(isLandscape = isLandscape)

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
                    state = state,
                    onEmbedBlocked = viewModel::onEmbedBlockedOrFailed,
                    onDirectStreamError = viewModel::onDirectStreamFailed,
                    onStreamSniffed = viewModel::onStreamSniffed,
                    onAdStateChanged = viewModel::onAdStateChanged
                )
            }

            // 2. STREAMER HEADER PILL (#hdr)
            StreamerHeaderBadge(
                accountName = state.accountName,
                avatarPath = state.avatarLocalPath,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 14.dp, top = 12.dp)
            )

            // 3. PERFECT CIRCULAR CHARACTER / LIVE CAMERA WINDOW (#cwin) — rendered above video,
            // no corners, no border, no stroke, no shadow, draggable & resizable, subtle pulse on AI speech
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
                CharacterMediaContent(state = state)
            }
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
                onPickCharacterMedia = {
                    characterMediaPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                },
                onToggleCamera = {
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
                onResetGameplay = viewModel::resetGameplayWindow,
                onResetCharacter = { viewModel.resetCharacterWindow(isLandscape) },
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
    }
}

@Composable
private fun GameplayMediaContent(
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
                val directHeaders = if (state.selectedSniffedStream != null) {
                    mapOf(
                        "Referer" to state.selectedSniffedStream.referer.ifBlank { target.resolvedUrl },
                        "User-Agent" to state.selectedSniffedStream.userAgent
                    )
                } else {
                    target.requestHeaders
                }

                if (effectiveEngine == PlaybackEngineMode.DIRECT_STREAM && !directUrl.isNullOrBlank()) {
                    ExoVideoPlayerView(
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
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF1B0F3D), StudioVoid)
                        )
                    )
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
private fun CharacterMediaContent(state: StudioUiState) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isCameraActive -> {
                CameraCharacterView(
                    isFrontCamera = state.isFrontCamera,
                    modifier = Modifier.fillMaxSize()
                )
            }

            !state.characterLocalPath.isNullOrBlank() && state.isCharacterImage -> {
                AsyncImage(
                    model = File(state.characterLocalPath),
                    contentDescription = stringResource(R.string.cd_profile_avatar),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            !state.characterLocalPath.isNullOrBlank() -> {
                ExoVideoPlayerView(
                    sourceUriOrUrl = state.characterLocalPath,
                    headers = emptyMap(),
                    isPlaying = state.isCharacterPlaying,
                    isMuted = !state.isCharacterSoundOn,
                    voiceReduceEnabled = false,
                    scaleMode = DisplayScaleMode.FILL,
                    reloadNonce = 0,
                    qualityPreference = state.qualityPreference,
                    isAudioDucked = false,
                    modifier = Modifier.fillMaxSize()
                )
            }

            !state.isBroadcastMode -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(StudioDeepNavy.copy(alpha = 0.55f))
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = NeonPink,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = stringResource(R.string.character_empty_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonEmerald,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.character_empty_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamerHeaderBadge(
    accountName: String,
    avatarPath: String?,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "live_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val pillShape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .widthIn(max = 260.dp)
            .shadow(10.dp, pillShape, spotColor = NeonPurple)
            .clip(pillShape)
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xDD0B1430), Color(0xDD1A0B3A))
                )
            )
            .border(1.5.dp, NeonPurple, pillShape)
            .padding(start = 6.dp, end = 14.dp, top = 5.dp, bottom = 5.dp)
            .testTag("streamer_header_badge")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color(0xFF11182E))
                .border(2.dp, NeonCyan, CircleShape)
        ) {
            if (!avatarPath.isNullOrBlank()) {
                AsyncImage(
                    model = File(avatarPath),
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
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .alpha(pulseAlpha)
                        .clip(CircleShape)
                        .background(LivePulseRed)
                )
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

@Composable
private fun AnimatedCyberGridBackground(isLandscape: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "cyber_grid")
    val gridPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "grid_phase"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF1B0F3D), Color.Transparent),
                center = Offset(w * 0.5f, 0f),
                radius = maxOf(w, h) * 0.65f
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF032A3A), Color.Transparent),
                center = Offset(w * 0.5f, h),
                radius = maxOf(w, h) * 0.55f
            )
        )

        val cell = (minOf(w, h) * 0.09f).coerceAtLeast(24f)
        val gridAlpha = if (isLandscape) 0.08f else 0.16f
        val lineColor = NeonCyan.copy(alpha = gridAlpha)

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

        val yOffset = gridPhase * cell
        var y = -cell + yOffset
        while (y <= h) {
            if (y >= 0f) {
                drawLine(
                    color = lineColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f
                )
            }
            y += cell
        }
    }
}
