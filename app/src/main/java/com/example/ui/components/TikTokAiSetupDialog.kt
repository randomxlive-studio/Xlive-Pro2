package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.service.TikTokCommentAccessibilityService
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.StudioBorderBlue
import com.example.ui.theme.StudioCardBg
import com.example.ui.theme.StudioDeepNavy
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.StudioVoid

@Composable
fun TikTokAiSetupDialog(
    isAccessibilityConnected: Boolean,
    isOcrFallbackRunning: Boolean,
    onToggleOcrFallback: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isAccEnabledInSettings by remember {
        mutableStateOf(isAccessibilityServiceEnabledInSettings(context))
    }
    var hasOverlayPerm by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccEnabledInSettings = isAccessibilityServiceEnabledInSettings(context)
                hasOverlayPerm = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val accessibilityReady = isAccessibilityConnected || isAccEnabledInSettings

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = StudioDeepNavy,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, NeonCyan, RoundedCornerShape(16.dp))
                .testTag("tiktok_setup_dialog")
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.setup_guide_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = NeonCyan,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_setup_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.btn_close),
                            tint = StudioTextSecondary
                        )
                    }
                }

                SetupStepCard(
                    title = stringResource(R.string.setup_step_accessibility_title),
                    description = stringResource(R.string.setup_step_accessibility_desc),
                    buttonText = stringResource(R.string.btn_open_accessibility),
                    icon = Icons.Default.AccessibilityNew,
                    isGranted = accessibilityReady,
                    buttonTestTag = "open_accessibility_settings_button",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                    }
                )

                SetupStepCard(
                    title = stringResource(R.string.setup_step_overlay_title),
                    description = stringResource(R.string.setup_step_overlay_desc),
                    buttonText = stringResource(R.string.btn_open_overlay),
                    icon = Icons.Default.Layers,
                    isGranted = hasOverlayPerm,
                    buttonTestTag = "open_overlay_settings_button",
                    onClick = {
                        runCatching {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    }
                )

                SetupStepCard(
                    title = stringResource(R.string.setup_step_ocr_title),
                    description = stringResource(R.string.setup_step_ocr_desc),
                    buttonText = stringResource(
                        if (isOcrFallbackRunning) R.string.btn_ocr_fallback_on
                        else R.string.btn_ocr_fallback_off
                    ),
                    icon = Icons.Default.DocumentScanner,
                    isGranted = isOcrFallbackRunning,
                    buttonTestTag = "setup_toggle_ocr_button",
                    onClick = onToggleOcrFallback
                )

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = StudioVoid
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("done_setup_dialog_button")
                ) {
                    Text(
                        text = stringResource(R.string.btn_close),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupStepCard(
    title: String,
    description: String,
    buttonText: String,
    icon: ImageVector,
    isGranted: Boolean,
    buttonTestTag: String,
    onClick: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(StudioCardBg, RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = if (isGranted) NeonEmerald else StudioBorderBlue,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else icon,
                contentDescription = null,
                tint = if (isGranted) NeonEmerald else NeonAmber
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = StudioTextPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(if (isGranted) R.string.status_ready else R.string.status_off),
                style = MaterialTheme.typography.labelSmall,
                color = if (isGranted) NeonEmerald else NeonAmber
            )
        }

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = StudioTextSecondary
        )

        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isGranted) NeonPurple.copy(alpha = 0.25f) else NeonPurple,
                contentColor = StudioTextPrimary
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag(buttonTestTag)
        ) {
            Icon(imageVector = icon, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = buttonText, style = MaterialTheme.typography.labelLarge)
        }
    }
}

fun isAccessibilityServiceEnabledInSettings(context: Context): Boolean {
    return runCatching {
        val expected = "${context.packageName}/${TikTokCommentAccessibilityService::class.java.canonicalName}"
        val expectedShort = "${context.packageName}/.${TikTokCommentAccessibilityService::class.java.simpleName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        enabledServices.split(':').any { entry ->
            entry.equals(expected, ignoreCase = true) ||
                entry.equals(expectedShort, ignoreCase = true) ||
                entry.contains(TikTokCommentAccessibilityService::class.java.simpleName)
        }
    }.getOrDefault(false)
}
