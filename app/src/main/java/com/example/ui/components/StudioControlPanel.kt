package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.DisplayScaleMode
import com.example.model.GameplayAspectPreset
import com.example.model.LlmProviderChoice
import com.example.model.PlaybackEngineMode
import com.example.model.SniffedStream
import com.example.model.StudioUiState
import com.example.model.UserAgentMode
import com.example.model.VideoQualityPreference
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonBroadcastRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonCyanDim
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.StudioBorderBlue
import com.example.ui.theme.StudioCardBg
import com.example.ui.theme.StudioLabelAccent
import com.example.ui.theme.StudioPanelSurface
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.StudioVoid
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudioControlPanel(
    state: StudioUiState,
    isLandscape: Boolean,
    maxPortraitHeight: Dp,
    onToggleMinimize: () -> Unit,
    onStartBroadcast: () -> Unit,
    onAccountNameChange: (String) -> Unit,
    onPickAvatar: () -> Unit,
    onUrlInputChange: (String) -> Unit,
    onPasteAndLoad: () -> Unit,
    onLoadUrl: () -> Unit,
    onClearUrl: () -> Unit,
    onOpenBrowserSheet: () -> Unit,
    onSelectQuality: (VideoQualityPreference) -> Unit,
    onToggleAdBlocker: () -> Unit,
    onSelectEngine: (PlaybackEngineMode) -> Unit,
    onToggleUserAgent: () -> Unit,
    onSelectSniffedStream: (SniffedStream) -> Unit,
    onPickGameplayVideo: () -> Unit,
    onToggleGameplayPlay: () -> Unit,
    onToggleGameplaySound: () -> Unit,
    onReloadPlayer: () -> Unit,
    onToggleAutoHideAds: () -> Unit,
    onToggleManualCover: () -> Unit,
    onToggleVoiceReduce: () -> Unit,
    onSelectScaleMode: (DisplayScaleMode) -> Unit,
    onSelectAspectPreset: (GameplayAspectPreset) -> Unit,
    onPickCharacterMedia: () -> Unit,
    onToggleCamera: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleCharacterPlay: () -> Unit,
    onToggleCharacterSound: () -> Unit,
    onTogglePulseOnSpeak: () -> Unit,
    onToggleCommentReader: () -> Unit,
    onOpenSetupGuide: () -> Unit,
    onToggleOcrFallback: () -> Unit,
    onToggleAiReplies: () -> Unit,
    onToggleVoiceTts: () -> Unit,
    onSelectLlmProvider: (LlmProviderChoice) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSelectTtsVoice: (String) -> Unit,
    onPreviewTtsVoice: () -> Unit,
    onTtsSpeedChange: (Float) -> Unit,
    onTtsPitchChange: (Float) -> Unit,
    onAiCooldownChange: (Int) -> Unit,
    onResetGameplay: () -> Unit,
    onResetCharacter: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showApiKeyPlain by remember { mutableStateOf(false) }
    var voiceMenuExpanded by remember { mutableStateOf(false) }

    val panelModifier = if (isLandscape) {
        modifier
            .width(380.dp)
            .then(if (state.isPanelMinimized) Modifier else Modifier.fillMaxHeight())
            .background(StudioPanelSurface)
            .border(width = 1.5.dp, color = NeonCyan.copy(alpha = 0.7f))
            .windowInsetsPadding(WindowInsets.navigationBars)
    } else {
        modifier
            .fillMaxWidth()
            .then(
                if (state.isPanelMinimized) Modifier
                else Modifier.heightIn(max = maxPortraitHeight)
            )
            .background(StudioPanelSurface)
            .border(width = 1.5.dp, color = NeonCyan.copy(alpha = 0.75f))
            .windowInsetsPadding(WindowInsets.navigationBars)
    }

    Column(modifier = panelModifier) {
        // Panel Header Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.studio_title),
                style = MaterialTheme.typography.titleSmall,
                color = NeonCyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (state.isPanelMinimized) {
                GradientBroadcastButton(
                    text = stringResource(R.string.btn_broadcast_short),
                    onClick = onStartBroadcast,
                    testTag = "panel_min_broadcast_button",
                    modifier = Modifier.height(48.dp)
                )
            }

            StudioToggleBtn(
                text = stringResource(
                    if (state.isPanelMinimized) R.string.btn_show_panel else R.string.btn_hide_panel
                ),
                icon = if (state.isPanelMinimized) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                isActive = false,
                onClick = onToggleMinimize,
                testTag = "toggle_panel_button"
            )
        }

        AnimatedVisibility(visible = !state.isPanelMinimized) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.wake_lock_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonEmerald
                )

                // 1. UNIVERSAL VIDEO FROM ANY PLATFORM SECTION
                SectionHeader(stringResource(R.string.section_universal_video))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = state.urlInput,
                        onValueChange = onUrlInputChange,
                        placeholder = {
                            Text(
                                text = stringResource(R.string.hint_video_url),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        singleLine = true,
                        trailingIcon = {
                            if (state.urlInput.isNotEmpty()) {
                                IconButton(
                                    onClick = onClearUrl,
                                    modifier = Modifier.testTag("clear_url_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = stringResource(R.string.btn_clear_url),
                                        tint = StudioTextSecondary
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onLoadUrl() }),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("url_input_field")
                    )

                    StudioToggleBtn(
                        text = stringResource(R.string.btn_paste_url),
                        icon = Icons.Default.ContentPaste,
                        isActive = false,
                        onClick = onPasteAndLoad,
                        testTag = "paste_url_button"
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onLoadUrl,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan,
                            contentColor = StudioVoid
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("load_url_button")
                    ) {
                        if (state.isResolvingUrl) {
                            CircularProgressIndicator(
                                color = StudioVoid,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        } else {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = stringResource(R.string.btn_load_url),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    StudioToggleBtn(
                        text = stringResource(R.string.btn_reload_player),
                        icon = Icons.Default.Refresh,
                        isActive = false,
                        onClick = onReloadPlayer,
                        testTag = "reload_player_button"
                    )
                }

                // Built-in Universal Video Browser & Sniffer Launcher
                Button(
                    onClick = onOpenBrowserSheet,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioCardBg,
                        contentColor = StudioTextPrimary
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .border(1.dp, NeonPurple, RoundedCornerShape(10.dp))
                        .testTag("open_browser_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = NeonCyan
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.btn_open_browser),
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                // FEATURE 1 & 2: MANUAL QUALITY MENU (AUTO 720P+ / 1080P / 720P) & AD BLOCKER SWITCH
                SectionHeader(stringResource(R.string.section_quality_adblock))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StudioToggleBtn(
                        text = stringResource(R.string.quality_auto),
                        icon = Icons.Default.HighQuality,
                        isActive = state.qualityPreference == VideoQualityPreference.AUTO_720P_MIN,
                        onClick = { onSelectQuality(VideoQualityPreference.AUTO_720P_MIN) },
                        testTag = "quality_auto_button",
                        modifier = Modifier.weight(1f)
                    )
                    StudioToggleBtn(
                        text = stringResource(R.string.quality_1080p),
                        isActive = state.qualityPreference == VideoQualityPreference.FORCE_1080P,
                        onClick = { onSelectQuality(VideoQualityPreference.FORCE_1080P) },
                        testTag = "quality_1080p_button",
                        modifier = Modifier.weight(0.8f)
                    )
                    StudioToggleBtn(
                        text = stringResource(R.string.quality_720p),
                        isActive = state.qualityPreference == VideoQualityPreference.FORCE_720P,
                        onClick = { onSelectQuality(VideoQualityPreference.FORCE_720P) },
                        testTag = "quality_720p_button",
                        modifier = Modifier.weight(0.8f)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(StudioCardBg)
                        .border(
                            width = 1.dp,
                            color = if (state.isAdBlockerEnabled) NeonEmerald else StudioBorderBlue,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = if (state.isAdBlockerEnabled) NeonEmerald else StudioTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                if (state.isAdBlockerEnabled) R.string.adblock_on
                                else R.string.adblock_off
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = StudioTextPrimary
                        )
                    }
                    Switch(
                        checked = state.isAdBlockerEnabled,
                        onCheckedChange = { onToggleAdBlocker() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = StudioVoid,
                            checkedTrackColor = NeonEmerald
                        ),
                        modifier = Modifier.testTag("adblocker_switch")
                    )
                }

                Text(
                    text = stringResource(R.string.hint_universal_engine),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioTextSecondary
                )

                // 2. PLAYBACK ENGINE SWITCHER
                if (state.activeUrl.isNotBlank()) {
                    SectionHeader(stringResource(R.string.section_engine_mode))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        StudioToggleBtn(
                            text = stringResource(R.string.engine_auto),
                            isActive = state.engineMode == PlaybackEngineMode.AUTO,
                            onClick = { onSelectEngine(PlaybackEngineMode.AUTO) },
                            testTag = "engine_auto_button"
                        )
                        StudioToggleBtn(
                            text = stringResource(R.string.engine_isolator),
                            isActive = state.engineMode == PlaybackEngineMode.WEB_ISOLATOR,
                            onClick = { onSelectEngine(PlaybackEngineMode.WEB_ISOLATOR) },
                            testTag = "engine_isolator_button"
                        )
                        StudioToggleBtn(
                            text = stringResource(R.string.engine_embed),
                            isActive = state.engineMode == PlaybackEngineMode.SMART_EMBED,
                            onClick = { onSelectEngine(PlaybackEngineMode.SMART_EMBED) },
                            testTag = "engine_embed_button"
                        )
                        StudioToggleBtn(
                            text = stringResource(R.string.engine_direct),
                            isActive = state.engineMode == PlaybackEngineMode.DIRECT_STREAM,
                            onClick = { onSelectEngine(PlaybackEngineMode.DIRECT_STREAM) },
                            testTag = "engine_direct_button"
                        )
                        StudioToggleBtn(
                            text = stringResource(R.string.engine_interactive),
                            isActive = state.engineMode == PlaybackEngineMode.INTERACTIVE_WEB,
                            onClick = { onSelectEngine(PlaybackEngineMode.INTERACTIVE_WEB) },
                            testTag = "engine_interactive_button"
                        )
                        StudioToggleBtn(
                            text = stringResource(
                                if (state.userAgentMode == UserAgentMode.MOBILE) R.string.ua_mobile
                                else R.string.ua_desktop
                            ),
                            isActive = state.userAgentMode == UserAgentMode.DESKTOP,
                            onClick = onToggleUserAgent,
                            testTag = "toggle_ua_button"
                        )
                    }

                    if (state.engineMode == PlaybackEngineMode.INTERACTIVE_WEB) {
                        Button(
                            onClick = { onSelectEngine(PlaybackEngineMode.WEB_ISOLATOR) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonAmber,
                                contentColor = StudioVoid
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("lock_isolate_button")
                        ) {
                            Icon(imageVector = Icons.Default.Lock, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.btn_lock_isolate_now),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    // Sniffed raw streams bar
                    if (state.sniffedStreams.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.sniffed_streams_title,
                                state.sniffedStreams.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonEmerald
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            state.sniffedStreams.take(5).forEachIndexed { idx, stream ->
                                StudioToggleBtn(
                                    text = "#${idx + 1} ${stream.label}",
                                    icon = Icons.Default.Bolt,
                                    isActive = state.selectedSniffedStream?.url == stream.url &&
                                        state.effectiveEngine == PlaybackEngineMode.DIRECT_STREAM,
                                    onClick = { onSelectSniffedStream(stream) },
                                    testTag = "sniffed_stream_$idx"
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = StudioBorderBlue.copy(alpha = 0.5f))

                // 3. TIKTOK LIVE COMMENT READER & SARCASTIC AI VOICE (VOICE ONLY)
                SectionHeader(stringResource(R.string.section_tiktok_ai))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onToggleCommentReader,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isCommentReaderRunning) NeonBroadcastRed else NeonEmerald,
                            contentColor = if (state.isCommentReaderRunning) StudioTextPrimary else StudioVoid
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("toggle_comment_reader_button")
                    ) {
                        Icon(
                            imageVector = if (state.isCommentReaderRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                if (state.isCommentReaderRunning) R.string.btn_reader_running
                                else R.string.btn_reader_stopped
                            ),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    StudioToggleBtn(
                        text = stringResource(R.string.btn_setup_permissions),
                        icon = Icons.Default.AccessibilityNew,
                        isActive = state.isAccessibilityServiceConnected,
                        onClick = onOpenSetupGuide,
                        testTag = "open_setup_guide_button"
                    )
                }

                Text(
                    text = stringResource(
                        R.string.ai_status_summary,
                        stringResource(
                            if (state.isAccessibilityServiceConnected) R.string.status_ready
                            else R.string.status_off
                        ),
                        state.queuedCommentsCount,
                        state.dailyAiRequestCount
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.isCommentReaderRunning) NeonEmerald else StudioTextSecondary
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StudioToggleBtn(
                        text = stringResource(
                            if (state.isAiRepliesEnabled) R.string.btn_ai_on else R.string.btn_ai_off
                        ),
                        icon = Icons.Default.SmartToy,
                        isActive = state.isAiRepliesEnabled,
                        onClick = onToggleAiReplies,
                        testTag = "toggle_ai_replies_button"
                    )
                    StudioToggleBtn(
                        text = stringResource(
                            if (state.isVoiceTtsEnabled) R.string.btn_tts_on else R.string.btn_tts_off
                        ),
                        icon = Icons.Default.RecordVoiceOver,
                        isActive = state.isVoiceTtsEnabled,
                        onClick = onToggleVoiceTts,
                        testTag = "toggle_tts_voice_button"
                    )
                    StudioToggleBtn(
                        text = stringResource(
                            if (state.isOcrFallbackRunning) R.string.btn_ocr_fallback_on
                            else R.string.btn_ocr_fallback_off
                        ),
                        icon = Icons.Default.DocumentScanner,
                        isActive = state.isOcrFallbackRunning,
                        onClick = onToggleOcrFallback,
                        testTag = "toggle_ocr_fallback_button"
                    )
                }

                // Free LLM Provider Selector (Gemini Free / Groq Free) + Local API Key Input
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StudioToggleBtn(
                        text = stringResource(R.string.provider_gemini),
                        isActive = state.llmProvider == LlmProviderChoice.GEMINI_FREE,
                        onClick = { onSelectLlmProvider(LlmProviderChoice.GEMINI_FREE) },
                        testTag = "provider_gemini_button",
                        modifier = Modifier.weight(1f)
                    )
                    StudioToggleBtn(
                        text = stringResource(R.string.provider_groq),
                        isActive = state.llmProvider == LlmProviderChoice.GROQ_FREE,
                        onClick = { onSelectLlmProvider(LlmProviderChoice.GROQ_FREE) },
                        testTag = "provider_groq_button",
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = state.llmApiKey,
                    onValueChange = onApiKeyChange,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.hint_api_key, state.llmProvider.displayName),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = NeonCyan
                        )
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { showApiKeyPlain = !showApiKeyPlain },
                            modifier = Modifier.testTag("toggle_api_key_visibility_button")
                        ) {
                            Icon(
                                imageVector = if (showApiKeyPlain) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(R.string.cd_toggle_cover),
                                tint = StudioTextSecondary
                            )
                        }
                    },
                    visualTransformation = if (showApiKeyPlain) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("llm_api_key_input")
                )

                // Installed TTS Voice Picker + Preview Voice Button
                val currentVoiceLabel = state.availableTtsVoices
                    .firstOrNull { it.id == state.selectedTtsVoiceId }
                    ?.displayName
                    ?: stringResource(R.string.voice_auto_match)

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        StudioToggleBtn(
                            text = stringResource(R.string.label_tts_voice, currentVoiceLabel),
                            isActive = state.selectedTtsVoiceId.isNotBlank(),
                            onClick = { voiceMenuExpanded = true },
                            testTag = "select_tts_voice_button",
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(
                            expanded = voiceMenuExpanded,
                            onDismissRequest = { voiceMenuExpanded = false },
                            modifier = Modifier
                                .heightIn(max = 280.dp)
                                .background(StudioCardBg)
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.voice_auto_match),
                                        color = StudioTextPrimary
                                    )
                                },
                                onClick = {
                                    onSelectTtsVoice("")
                                    voiceMenuExpanded = false
                                }
                            )
                            state.availableTtsVoices.forEach { voice ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = voice.displayName,
                                            color = StudioTextPrimary
                                        )
                                    },
                                    onClick = {
                                        onSelectTtsVoice(voice.id)
                                        voiceMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    StudioToggleBtn(
                        text = stringResource(R.string.btn_preview_voice),
                        icon = Icons.Default.VolumeUp,
                        isActive = state.isAiSpeaking,
                        onClick = onPreviewTtsVoice,
                        testTag = "preview_tts_voice_button"
                    )
                }

                // TTS Speed, Pitch, and Cooldown Sliders
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.label_tts_speed, state.ttsSpeed),
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary
                    )
                    Slider(
                        value = state.ttsSpeed,
                        onValueChange = onTtsSpeedChange,
                        valueRange = 0.5f..2.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = NeonCyan,
                            activeTrackColor = NeonCyan
                        ),
                        modifier = Modifier.testTag("tts_speed_slider")
                    )

                    Text(
                        text = stringResource(R.string.label_tts_pitch, state.ttsPitch),
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary
                    )
                    Slider(
                        value = state.ttsPitch,
                        onValueChange = onTtsPitchChange,
                        valueRange = 0.5f..2.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = NeonPurple,
                            activeTrackColor = NeonPurple
                        ),
                        modifier = Modifier.testTag("tts_pitch_slider")
                    )

                    Text(
                        text = stringResource(R.string.label_ai_cooldown, state.aiCooldownSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary
                    )
                    Slider(
                        value = state.aiCooldownSeconds.toFloat(),
                        onValueChange = { onAiCooldownChange(it.roundToInt()) },
                        valueRange = 5f..60f,
                        colors = SliderDefaults.colors(
                            thumbColor = NeonEmerald,
                            activeTrackColor = NeonEmerald
                        ),
                        modifier = Modifier.testTag("ai_cooldown_slider")
                    )
                }

                HorizontalDivider(color = StudioBorderBlue.copy(alpha = 0.5f))

                // 4. ACCOUNT & TIKTOK OWN HANDLE
                SectionHeader(stringResource(R.string.section_account))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = state.accountName,
                        onValueChange = { if (it.length <= 28) onAccountNameChange(it) },
                        placeholder = { Text(stringResource(R.string.hint_account_name)) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("account_name_input")
                    )
                    StudioToggleBtn(
                        text = stringResource(R.string.btn_pick_avatar),
                        icon = Icons.Default.AccountCircle,
                        isActive = state.avatarLocalPath != null,
                        onClick = onPickAvatar,
                        testTag = "pick_avatar_button"
                    )
                }

                // 5. GAMEPLAY WINDOW CONTROLS
                SectionHeader(stringResource(R.string.section_gameplay_controls))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StudioToggleBtn(
                        text = stringResource(R.string.btn_upload_gameplay),
                        icon = Icons.Default.UploadFile,
                        isActive = state.gameplayLocalPath != null && state.activeUrl.isBlank(),
                        onClick = onPickGameplayVideo,
                        testTag = "upload_gameplay_button"
                    )
                    StudioToggleBtn(
                        text = stringResource(
                            if (state.isGameplayPlaying) R.string.btn_pause else R.string.btn_play
                        ),
                        icon = if (state.isGameplayPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        isActive = state.isGameplayPlaying,
                        onClick = onToggleGameplayPlay,
                        testTag = "gameplay_play_pause_button"
                    )
                    StudioToggleBtn(
                        text = stringResource(
                            if (state.isGameplaySoundOn) R.string.btn_sound_on else R.string.btn_sound_off
                        ),
                        icon = if (state.isGameplaySoundOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        isActive = state.isGameplaySoundOn,
                        onClick = onToggleGameplaySound,
                        testTag = "gameplay_sound_button"
                    )
                    StudioToggleBtn(
                        text = stringResource(
                            if (state.isAutoHideAdsOn) R.string.btn_auto_hide_ads_on
                            else R.string.btn_auto_hide_ads_off
                        ),
                        icon = Icons.Default.Shield,
                        isActive = state.isAutoHideAdsOn,
                        onClick = onToggleAutoHideAds,
                        testTag = "auto_hide_ads_button"
                    )
                    StudioToggleBtn(
                        text = stringResource(
                            if (state.isManualCoverOn) R.string.btn_cover_show else R.string.btn_cover_hide
                        ),
                        icon = if (state.isManualCoverOn) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        isActive = state.isManualCoverOn,
                        onClick = onToggleManualCover,
                        testTag = "manual_cover_button"
                    )
                    StudioToggleBtn(
                        text = stringResource(
                            if (state.isVoiceReduceOn) R.string.btn_voice_reduce_on
                            else R.string.btn_voice_reduce_off
                        ),
                        icon = Icons.Default.GraphicEq,
                        isActive = state.isVoiceReduceOn,
                        onClick = onToggleVoiceReduce,
                        testTag = "voice_reduce_button"
                    )
                }

                Text(
                    text = stringResource(R.string.hint_voice_reduce),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioTextSecondary
                )

                // Display Scale & Aspect Ratio
                SectionHeader(stringResource(R.string.section_display_mode))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StudioToggleBtn(
                        text = stringResource(R.string.mode_fit),
                        isActive = state.displayScaleMode == DisplayScaleMode.FIT,
                        onClick = { onSelectScaleMode(DisplayScaleMode.FIT) },
                        testTag = "scale_fit_button",
                        modifier = Modifier.weight(1f)
                    )
                    StudioToggleBtn(
                        text = stringResource(R.string.mode_fill),
                        isActive = state.displayScaleMode == DisplayScaleMode.FILL,
                        onClick = { onSelectScaleMode(DisplayScaleMode.FILL) },
                        testTag = "scale_fill_button",
                        modifier = Modifier.weight(1f)
                    )
                    StudioToggleBtn(
                        text = stringResource(R.string.mode_stretch),
                        isActive = state.displayScaleMode == DisplayScaleMode.STRETCH,
                        onClick = { onSelectScaleMode(DisplayScaleMode.STRETCH) },
                        testTag = "scale_stretch_button",
                        modifier = Modifier.weight(1f)
                    )
                }

                SectionHeader(stringResource(R.string.section_aspect_ratio))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StudioToggleBtn(
                        text = stringResource(R.string.ratio_landscape),
                        isActive = state.aspectPreset == GameplayAspectPreset.LANDSCAPE_16_9,
                        onClick = { onSelectAspectPreset(GameplayAspectPreset.LANDSCAPE_16_9) },
                        testTag = "ratio_landscape_button",
                        modifier = Modifier.weight(1f)
                    )
                    StudioToggleBtn(
                        text = stringResource(R.string.ratio_portrait),
                        isActive = state.aspectPreset == GameplayAspectPreset.PORTRAIT_9_16,
                        onClick = { onSelectAspectPreset(GameplayAspectPreset.PORTRAIT_9_16) },
                        testTag = "ratio_portrait_button",
                        modifier = Modifier.weight(1f)
                    )
                    StudioToggleBtn(
                        text = stringResource(R.string.ratio_free),
                        isActive = state.aspectPreset == GameplayAspectPreset.FREE,
                        onClick = { onSelectAspectPreset(GameplayAspectPreset.FREE) },
                        testTag = "ratio_free_button",
                        modifier = Modifier.weight(1f)
                    )
                }

                HorizontalDivider(color = StudioBorderBlue.copy(alpha = 0.5f))

                // 6. CIRCULAR CHARACTER & LIVE CAMERA
                SectionHeader(stringResource(R.string.section_character))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StudioToggleBtn(
                        text = stringResource(R.string.btn_upload_character),
                        icon = Icons.Default.UploadFile,
                        isActive = state.characterLocalPath != null && !state.isCameraActive,
                        onClick = onPickCharacterMedia,
                        testTag = "upload_character_button"
                    )
                    StudioToggleBtn(
                        text = stringResource(
                            if (state.isCameraActive) R.string.btn_camera_on else R.string.btn_camera_off
                        ),
                        icon = if (state.isCameraActive) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        isActive = state.isCameraActive,
                        onClick = onToggleCamera,
                        testTag = "toggle_camera_button"
                    )
                    StudioToggleBtn(
                        text = stringResource(R.string.btn_camera_flip),
                        icon = Icons.Default.Cameraswitch,
                        isActive = false,
                        onClick = onFlipCamera,
                        testTag = "flip_camera_button"
                    )
                    StudioToggleBtn(
                        text = stringResource(
                            if (state.isPulseOnAiSpeakEnabled) R.string.btn_pulse_on
                            else R.string.btn_pulse_off
                        ),
                        icon = Icons.Default.GraphicEq,
                        isActive = state.isPulseOnAiSpeakEnabled,
                        onClick = onTogglePulseOnSpeak,
                        testTag = "toggle_character_pulse_button"
                    )
                    if (!state.isCharacterImage && state.characterLocalPath != null) {
                        StudioToggleBtn(
                            text = stringResource(
                                if (state.isCharacterPlaying) R.string.btn_pause else R.string.btn_play
                            ),
                            icon = if (state.isCharacterPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            isActive = state.isCharacterPlaying,
                            onClick = onToggleCharacterPlay,
                            testTag = "character_play_pause_button"
                        )
                        StudioToggleBtn(
                            text = stringResource(
                                if (state.isCharacterSoundOn) R.string.btn_sound_on else R.string.btn_sound_off
                            ),
                            icon = if (state.isCharacterSoundOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            isActive = state.isCharacterSoundOn,
                            onClick = onToggleCharacterSound,
                            testTag = "character_sound_button"
                        )
                    }
                }

                // 7. RESET & BROADCAST
                SectionHeader(stringResource(R.string.section_reset))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StudioToggleBtn(
                        text = stringResource(R.string.btn_reset_gameplay),
                        icon = Icons.Default.RestartAlt,
                        isActive = false,
                        onClick = onResetGameplay,
                        testTag = "reset_gameplay_button",
                        modifier = Modifier.weight(1f)
                    )
                    StudioToggleBtn(
                        text = stringResource(R.string.btn_reset_character),
                        icon = Icons.Default.RestartAlt,
                        isActive = false,
                        onClick = onResetCharacter,
                        testTag = "reset_character_button",
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    text = stringResource(R.string.hint_drag_resize),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioTextSecondary
                )

                GradientBroadcastButton(
                    text = stringResource(R.string.btn_start_broadcast),
                    onClick = onStartBroadcast,
                    testTag = "start_broadcast_button",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = StudioLabelAccent
    )
}

@Composable
private fun StudioToggleBtn(
    text: String,
    isActive: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    val bg = if (isActive) NeonCyanDim else StudioCardBg
    val borderCol = if (isActive) NeonCyan else StudioBorderBlue
    val shape = RoundedCornerShape(10.dp)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, borderCol, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(testTag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isActive) NeonCyan else StudioTextPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = StudioTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GradientBroadcastButton(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = StudioTextPrimary
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        shape = shape,
        modifier = modifier
            .background(
                brush = Brush.horizontalGradient(listOf(NeonBroadcastRed, NeonPurple)),
                shape = shape
            )
            .testTag(testTag)
    ) {
        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}
