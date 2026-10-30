package com.example.engine

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.example.model.DisplayScaleMode
import com.example.model.VideoQualityPreference
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
class CenterChannelVoiceReducerProcessor : BaseAudioProcessor() {

    @Volatile
    var enabled: Boolean = false

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.channelCount == 2
        ) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val out = replaceOutputBuffer(remaining).order(ByteOrder.LITTLE_ENDIAN)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        if (!enabled) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        // Stereo 16-bit PCM: each frame = 4 bytes (Left short + Right short)
        val frameCount = remaining / 4
        for (i in 0 until frameCount) {
            val left = inputBuffer.short.toInt()
            val right = inputBuffer.short.toInt()
            // Cancel center-panned vocal/commentator signal: (L - R) * 0.7
            val cancelled = ((left - right) * 7 / 10)
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            out.putShort(cancelled)
            out.putShort(cancelled)
        }
        while (inputBuffer.hasRemaining()) {
            out.put(inputBuffer.get())
        }
        out.flip()
    }
}

@OptIn(UnstableApi::class)
@Composable
fun ExoVideoPlayerView(
    playerManager: StudioExoPlayerManager,
    sourceUriOrUrl: String,
    headers: Map<String, String>,
    isPlaying: Boolean,
    isMuted: Boolean,
    voiceReduceEnabled: Boolean,
    scaleMode: DisplayScaleMode,
    reloadNonce: Int,
    qualityPreference: VideoQualityPreference = VideoQualityPreference.AUTO_720P_MIN,
    isAudioDucked: Boolean = false,
    onStreamError: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentOnError by rememberUpdatedState(onStreamError)
    val currentScaleMode by rememberUpdatedState(scaleMode)

    // Reuses the ViewModel-managed ExoPlayer instance across video switches & recompositions
    val exoPlayer = remember(playerManager, reloadNonce, sourceUriOrUrl) {
        playerManager.getOrCreateGameplayPlayer(headers, qualityPreference)
    }

    var videoWidth by remember(exoPlayer) { mutableIntStateOf(exoPlayer.videoSize.width) }
    var videoHeight by remember(exoPlayer) { mutableIntStateOf(exoPlayer.videoSize.height) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    val reusableMatrix = remember { Matrix() }

    DisposableEffect(exoPlayer, playerManager) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoWidth = videoSize.width
                videoHeight = videoSize.height
                textureViewRef?.let { tv ->
                    applyTextureScaleMatrix(
                        textureView = tv,
                        videoWidth = videoSize.width,
                        videoHeight = videoSize.height,
                        scaleMode = currentScaleMode,
                        matrix = reusableMatrix
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val callback = currentOnError
                if (callback != null) {
                    callback.invoke(error.errorCodeName)
                } else {
                    runCatching {
                        Toast.makeText(
                            context,
                            "Video error: ${error.errorCodeName}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            runCatching { exoPlayer.removeListener(listener) }
            val tv = textureViewRef
            textureViewRef = null
            playerManager.detachGameplayTextureView(tv)
        }
    }

    LaunchedEffect(playerManager, headers) {
        playerManager.updateGameplayHeaders(headers)
    }

    LaunchedEffect(playerManager, qualityPreference) {
        playerManager.updateGameplayQuality(qualityPreference)
    }

    LaunchedEffect(exoPlayer, sourceUriOrUrl, reloadNonce) {
        runCatching {
            if (sourceUriOrUrl.isBlank()) {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                return@LaunchedEffect
            }
            val uri = if (sourceUriOrUrl.startsWith("/")) {
                val f = File(sourceUriOrUrl)
                if (!f.exists() || f.length() <= 0L) {
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    return@LaunchedEffect
                }
                Uri.fromFile(f)
            } else {
                Uri.parse(sourceUriOrUrl)
            }
            playerManager.loadGameplayMedia(
                player = exoPlayer,
                loadKey = "$reloadNonce|$sourceUriOrUrl",
                mediaItem = MediaItem.fromUri(uri),
                playWhenReady = isPlaying
            )
        }
    }

    LaunchedEffect(exoPlayer, isPlaying) {
        runCatching {
            exoPlayer.playWhenReady = isPlaying
            if (isPlaying && exoPlayer.playbackState == Player.STATE_IDLE && exoPlayer.mediaItemCount > 0) {
                exoPlayer.prepare()
            }
        }
    }

    LaunchedEffect(exoPlayer, isMuted, isAudioDucked) {
        runCatching {
            exoPlayer.volume = computeTargetVolume(isMuted = isMuted, isAudioDucked = isAudioDucked)
        }
    }

    LaunchedEffect(playerManager, voiceReduceEnabled) {
        playerManager.gameplayVoiceProcessor.enabled = voiceReduceEnabled
    }

    LaunchedEffect(scaleMode, videoWidth, videoHeight, textureViewRef) {
        textureViewRef?.let { tv ->
            applyTextureScaleMatrix(tv, videoWidth, videoHeight, scaleMode, reusableMatrix)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isOpaque = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        applyTextureScaleMatrix(
                            this@apply,
                            videoWidth,
                            videoHeight,
                            currentScaleMode,
                            reusableMatrix
                        )
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        applyTextureScaleMatrix(
                            this@apply,
                            videoWidth,
                            videoHeight,
                            currentScaleMode,
                            reusableMatrix
                        )
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    applyTextureScaleMatrix(
                        this,
                        videoWidth,
                        videoHeight,
                        currentScaleMode,
                        reusableMatrix
                    )
                }
                playerManager.attachGameplayTextureView(exoPlayer, this)
                textureViewRef = this
            }
        },
        update = { tv ->
            playerManager.attachGameplayTextureView(exoPlayer, tv)
            textureViewRef = tv
            applyTextureScaleMatrix(tv, videoWidth, videoHeight, scaleMode, reusableMatrix)
        }
    )
}

private fun computeTargetVolume(isMuted: Boolean, isAudioDucked: Boolean): Float = when {
    isMuted -> 0f
    isAudioDucked -> 0.22f
    else -> 1f
}

private fun applyTextureScaleMatrix(
    textureView: TextureView,
    videoWidth: Int,
    videoHeight: Int,
    scaleMode: DisplayScaleMode,
    matrix: Matrix
) {
    val viewWidth = textureView.width.toFloat()
    val viewHeight = textureView.height.toFloat()
    if (viewWidth <= 0f || viewHeight <= 0f || videoWidth <= 0 || videoHeight <= 0) {
        textureView.setTransform(null)
        return
    }

    matrix.reset()
    if (scaleMode == DisplayScaleMode.STRETCH) {
        textureView.setTransform(matrix)
        return
    }

    val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
    val viewAspect = viewWidth / viewHeight

    var scaleX = 1f
    var scaleY = 1f

    if (scaleMode == DisplayScaleMode.FIT) {
        if (videoAspect > viewAspect) {
            scaleY = viewAspect / videoAspect
        } else {
            scaleX = videoAspect / viewAspect
        }
    } else if (scaleMode == DisplayScaleMode.FILL) {
        if (videoAspect > viewAspect) {
            scaleX = videoAspect / viewAspect
        } else {
            scaleY = viewAspect / videoAspect
        }
    }

    val pivotX = viewWidth / 2f
    val pivotY = viewHeight / 2f
    matrix.setScale(
        max(0.01f, min(scaleX, 20f)),
        max(0.01f, min(scaleY, 20f)),
        pivotX,
        pivotY
    )
    textureView.setTransform(matrix)
}
