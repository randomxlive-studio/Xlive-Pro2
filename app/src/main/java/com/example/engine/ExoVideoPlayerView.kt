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
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.example.model.DisplayScaleMode
import com.example.model.VideoQualityPreference
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
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

    val voiceProcessor = remember { CenterChannelVoiceReducerProcessor() }
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }

    val trackSelector = remember(headers) {
        DefaultTrackSelector(context).apply {
            setParameters(buildMin720pTrackParameters(this, qualityPreference))
        }
    }

    val exoPlayer = remember(headers, trackSelector) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setUserAgent(headers["User-Agent"] ?: UniversalVideoResolver.MOBILE_CHROME_UA)
            .setDefaultRequestProperties(headers)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // Large buffer so playback never drops below 720p even on weak/fluctuating signal:
        // min 30s, max 120s, playback start 2.5s, rebuffer 5s
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,
                120_000,
                2_500,
                5_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(voiceProcessor))
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }.setEnableDecoderFallback(true)

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = isPlaying
                volume = computeTargetVolume(isMuted = isMuted, isAudioDucked = isAudioDucked)
            }
    }

    val isPlayerReleased = remember(exoPlayer) { AtomicBoolean(false) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoWidth = videoSize.width
                videoHeight = videoSize.height
                textureViewRef?.let { tv ->
                    applyTextureScaleMatrix(tv, videoSize.width, videoSize.height, scaleMode)
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
            if (isPlayerReleased.compareAndSet(false, true)) {
                runCatching { exoPlayer.removeListener(listener) }
                runCatching { exoPlayer.stop() }
                runCatching { exoPlayer.clearMediaItems() }
                runCatching { exoPlayer.clearVideoTextureView(textureViewRef) }
                runCatching { exoPlayer.release() }
            }
        }
    }

    LaunchedEffect(trackSelector, qualityPreference) {
        runCatching {
            trackSelector.setParameters(buildMin720pTrackParameters(trackSelector, qualityPreference))
        }
    }

    LaunchedEffect(exoPlayer, sourceUriOrUrl, reloadNonce) {
        if (isPlayerReleased.get()) return@LaunchedEffect
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
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = isPlaying
        }
    }

    LaunchedEffect(exoPlayer, isPlaying) {
        if (isPlayerReleased.get()) return@LaunchedEffect
        runCatching {
            exoPlayer.playWhenReady = isPlaying
            if (isPlaying && exoPlayer.playbackState == Player.STATE_IDLE && exoPlayer.mediaItemCount > 0) {
                exoPlayer.prepare()
            }
        }
    }

    LaunchedEffect(exoPlayer, isMuted, isAudioDucked) {
        if (isPlayerReleased.get()) return@LaunchedEffect
        runCatching {
            exoPlayer.volume = computeTargetVolume(isMuted = isMuted, isAudioDucked = isAudioDucked)
        }
    }

    LaunchedEffect(voiceReduceEnabled) {
        voiceProcessor.enabled = voiceReduceEnabled
    }

    LaunchedEffect(scaleMode, videoWidth, videoHeight, textureViewRef) {
        textureViewRef?.let { tv ->
            applyTextureScaleMatrix(tv, videoWidth, videoHeight, scaleMode)
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
                        applyTextureScaleMatrix(this@apply, videoWidth, videoHeight, scaleMode)
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        applyTextureScaleMatrix(this@apply, videoWidth, videoHeight, scaleMode)
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    applyTextureScaleMatrix(this, videoWidth, videoHeight, scaleMode)
                }
                exoPlayer.setVideoTextureView(this)
                textureViewRef = this
            }
        },
        update = { tv ->
            if (textureViewRef !== tv) {
                exoPlayer.setVideoTextureView(tv)
                textureViewRef = tv
            }
            applyTextureScaleMatrix(tv, videoWidth, videoHeight, scaleMode)
        }
    )
}

private fun computeTargetVolume(isMuted: Boolean, isAudioDucked: Boolean): Float = when {
    isMuted -> 0f
    isAudioDucked -> 0.22f
    else -> 1f
}

@OptIn(UnstableApi::class)
private fun buildMin720pTrackParameters(
    trackSelector: DefaultTrackSelector,
    qualityPreference: VideoQualityPreference
): DefaultTrackSelector.Parameters {
    val builder = trackSelector.buildUponParameters()
        // Fallback to best available quality if stream has no 720p+ variant
        .setExceedVideoConstraintsIfNecessary(true)
        .setExceedRendererCapabilitiesIfNecessary(true)

    when (qualityPreference) {
        VideoQualityPreference.AUTO_720P_MIN -> {
            builder
                .setMinVideoSize(1280, 720)
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                .setForceHighestSupportedBitrate(true)
        }

        VideoQualityPreference.FORCE_1080P -> {
            builder
                .setMinVideoSize(1920, 1080)
                .setMaxVideoSize(1920, 1080)
                .setForceHighestSupportedBitrate(true)
        }

        VideoQualityPreference.FORCE_720P -> {
            builder
                .setMinVideoSize(1280, 720)
                .setMaxVideoSize(1280, 720)
                .setForceHighestSupportedBitrate(true)
        }
    }
    return builder.build()
}

private fun applyTextureScaleMatrix(
    textureView: TextureView,
    videoWidth: Int,
    videoHeight: Int,
    scaleMode: DisplayScaleMode
) {
    val viewWidth = textureView.width.toFloat()
    val viewHeight = textureView.height.toFloat()
    if (viewWidth <= 0f || viewHeight <= 0f || videoWidth <= 0 || videoHeight <= 0) {
        textureView.setTransform(null)
        return
    }

    val matrix = Matrix()
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
