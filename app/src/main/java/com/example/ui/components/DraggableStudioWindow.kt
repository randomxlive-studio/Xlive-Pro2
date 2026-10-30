package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.example.model.GameplayAspectPreset
import com.example.model.WindowRectState
import com.example.ui.theme.StudioVoid
import kotlin.math.min as kotlinMin
import kotlin.math.roundToInt

@Composable
fun DraggableStudioWindow(
    rect: WindowRectState,
    stageWidthPx: Float,
    stageHeightPx: Float,
    stageWidthDp: Dp,
    stageHeightDp: Dp,
    isEditMode: Boolean,
    isFullBleedLocked: Boolean,
    allowTouchInsideContent: Boolean,
    borderColor: Color,
    aspectConstraint: GameplayAspectPreset?,
    windowTestTag: String,
    isCircular: Boolean = false,
    pulseScale: Float = 1f,
    onRectChange: (WindowRectState, Boolean) -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val currentRect by rememberUpdatedState(rect)
    val currentOnRectChange by rememberUpdatedState(onRectChange)

    val effectiveX = if (isFullBleedLocked) 0f else rect.x
    val effectiveY = if (isFullBleedLocked) 0f else rect.y
    val effectiveW = if (isFullBleedLocked) 1f else rect.w
    val effectiveH = if (isFullBleedLocked) 1f else rect.h

    val minStageDp = min(stageWidthDp, stageHeightDp)
    val minStagePx = kotlinMin(stageWidthPx, stageHeightPx).coerceAtLeast(1f)
    val circleDiameterDp = minStageDp * effectiveW

    val canManipulateWindow = isEditMode && !isFullBleedLocked && !allowTouchInsideContent

    val sizeAndAppearanceModifier = when {
        isCircular -> {
            // Perfect circle: no corners, no border, no stroke, no shadow
            Modifier
                .size(circleDiameterDp)
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                }
                .clip(CircleShape)
                .background(Color.Transparent, CircleShape)
        }

        isFullBleedLocked -> {
            Modifier
                .size(width = stageWidthDp, height = stageHeightDp)
                .background(Color.Black)
        }

        else -> {
            val shape = RoundedCornerShape(12.dp)
            Modifier
                .size(
                    width = stageWidthDp * effectiveW,
                    height = stageHeightDp * effectiveH
                )
                .shadow(elevation = 12.dp, shape = shape, spotColor = borderColor)
                .clip(shape)
                .background(StudioVoid)
                .border(
                    width = if (isEditMode) 2.5.dp else 2.dp,
                    color = borderColor,
                    shape = shape
                )
        }
    }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (effectiveX * stageWidthPx).roundToInt(),
                    y = (effectiveY * stageHeightPx).roundToInt()
                )
            }
            .then(sizeAndAppearanceModifier)
            .testTag(windowTestTag)
    ) {
        // Window media content
        Box(modifier = Modifier.fillMaxSize(), content = content)

        // Transparent gesture catcher in Edit Mode (drag + pinch resize)
        if (canManipulateWindow) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(stageWidthPx, stageHeightPx, aspectConstraint, isCircular) {
                        if (stageWidthPx <= 1f || stageHeightPx <= 1f) return@pointerInput
                        detectTransformGestures { _, pan, zoom, _ ->
                            val r = currentRect
                            val newW = (r.w * zoom).coerceIn(0.12f, 0.95f)
                            val newH = if (isCircular) {
                                newW
                            } else {
                                when (aspectConstraint) {
                                    GameplayAspectPreset.LANDSCAPE_16_9 -> newW * 81f / 256f
                                    GameplayAspectPreset.PORTRAIT_9_16 -> newW
                                    GameplayAspectPreset.FREE -> (r.h * zoom).coerceIn(0.08f, 1.15f)
                                    null -> (r.h * zoom).coerceIn(0.08f, 1.15f)
                                }
                            }
                            val next = r.copy(
                                x = r.x + pan.x / stageWidthPx,
                                y = r.y + pan.y / stageHeightPx,
                                w = newW,
                                h = newH
                            ).clamped()
                            currentOnRectChange(next, true)
                        }
                    }
            )

            // Bottom-right 48dp touch resize handle (invisible on circular window so zero stroke/corner appears)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(48.dp)
                    .pointerInput(stageWidthPx, stageHeightPx, aspectConstraint, isCircular) {
                        if (stageWidthPx <= 1f || stageHeightPx <= 1f) return@pointerInput
                        detectDragGestures(
                            onDragEnd = { currentOnRectChange(currentRect, true) }
                        ) { change, dragAmount ->
                            change.consume()
                            val r = currentRect
                            if (isCircular) {
                                val delta = (dragAmount.x + dragAmount.y) / (2f * minStagePx)
                                val nextD = (r.w + delta).coerceIn(0.12f, 0.90f)
                                currentOnRectChange(r.copy(w = nextD, h = nextD).clamped(), false)
                            } else {
                                val newW = (r.w + dragAmount.x / stageWidthPx).coerceIn(0.14f, 1.15f)
                                val newH = when (aspectConstraint) {
                                    GameplayAspectPreset.LANDSCAPE_16_9 -> newW * 81f / 256f
                                    GameplayAspectPreset.PORTRAIT_9_16 -> newW
                                    GameplayAspectPreset.FREE ->
                                        (r.h + dragAmount.y / stageHeightPx).coerceIn(0.08f, 1.15f)
                                    null -> {
                                        val ratio = if (r.w > 0.01f) r.h / r.w else 1f
                                        (newW * ratio).coerceIn(0.08f, 1.15f)
                                    }
                                }
                                currentOnRectChange(r.copy(w = newW, h = newH).clamped(), false)
                            }
                        }
                    }
                    .testTag("${windowTestTag}_resize_handle")
            ) {
                if (!isCircular) {
                    Canvas(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(28.dp)
                    ) {
                        val path = Path().apply {
                            moveTo(size.width, 0f)
                            lineTo(size.width, size.height)
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(path = path, color = borderColor.copy(alpha = 0.88f))
                    }
                }
            }
        }
    }
}
