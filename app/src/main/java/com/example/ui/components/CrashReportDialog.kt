package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.ui.theme.LivePulseRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.StudioBorderBlue
import com.example.ui.theme.StudioCardBg
import com.example.ui.theme.StudioDeepNavy
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.StudioVoid

@Composable
fun CrashReportDialog(
    crashReport: String,
    onDismissAndClear: () -> Unit
) {
    val context = LocalContext.current
    val copiedToastText = stringResource(R.string.toast_crash_copied)

    Dialog(
        onDismissRequest = onDismissAndClear,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            color = StudioDeepNavy,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .border(1.5.dp, LivePulseRed.copy(alpha = 0.85f), RoundedCornerShape(18.dp))
                .testTag("crash_report_dialog")
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = LivePulseRed,
                        modifier = Modifier.size(26.dp)
                    )
                    Text(
                        text = stringResource(R.string.crash_dialog_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioTextPrimary
                    )
                }

                Text(
                    text = stringResource(R.string.crash_dialog_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioTextSecondary
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 320.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(StudioCardBg)
                        .border(1.dp, StudioBorderBlue, RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = crashReport,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = StudioTextPrimary,
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .testTag("crash_report_stacktrace_text")
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            runCatching {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as? ClipboardManager
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText("RandomXLive Crash Log", crashReport)
                                )
                                Toast.makeText(context, copiedToastText, Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan,
                            contentColor = StudioVoid
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("copy_crash_report_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.btn_copy_crash),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    OutlinedButton(
                        onClick = onDismissAndClear,
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                            brush = SolidColor(StudioBorderBlue)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = StudioTextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("dismiss_crash_report_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.btn_dismiss_crash),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}
