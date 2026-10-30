package com.example.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.example.R
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.NeonPink
import com.example.ui.theme.StudioDeepNavy
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.StudioVoid
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

private sealed interface CharacterMediaTarget {
    data object None : CharacterMediaTarget
    data class Camera(val isFrontCamera: Boolean) : CharacterMediaTarget
    data class Video(val localFilePath: String, val switchNonce: Long) : CharacterMediaTarget
}

private object OvalClipOutlineProvider : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        val w = view.width
        val h = view.height
        if (w > 0 && h > 0) {
            outline.setOval(0, 0, w, h)
        } else {
            outline.setOval(0, 0, 1, 1)
        }
    }
}

private fun enforceCircularOutline(view: View) {
    view.outlineProvider = OvalClipOutlineProvider
    view.clipToOutline = true
    if (view.width > 0 && view.height > 0) {
        view.invalidateOutline()
    }
}

private class CircularHostContainer(context: Context) : FrameLayout(context) {
    init {
        isClickable = false
        isFocusable = false
        clipChildren = true
        clipToPadding = true
        enforceCircularOutline(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        enforceCircularOutline(this)
        for (i in 0 until childCount) {
            getChildAt(i)?.let { enforceCircularOutline(it) }
        }
    }
}

@OptIn(UnstableApi::class)
private class ActiveCharacterVideoSession(
    val player: ExoPlayer,
    val textureView: TextureView,
    val playerListener: Player.Listener,
    val layoutListener: View.OnLayoutChangeListener
) {
    val isReleased = AtomicBoolean(false)

    @Volatile
    var videoWidth: Int = 0

    @Volatile
    var videoHeight: Int = 0
}

/**
 * Main-thread controller that guarantees:
 * 1. Camera and Video NEVER run at the same time (previous mode is 100% released before next starts).
 * 2. Safe video switching: `player.stop()`, `player.clearMediaItems()`, `player.release()`,
 *    detach & remove old `TextureView`, then create a fresh player & attach on Main thread.
 * 3. Null and double-release safety via `AtomicBoolean` + defensive `try/catch`.
 * 4. Persistent circular outline clipping on host + child surfaces after every switch.
 */
@OptIn(UnstableApi::class)
private class CharacterCircleMediaController(
    private val appContext: Context
) {
    private var hostContainer: CircularHostContainer? = null
    private var currentTarget: CharacterMediaTarget = CharacterMediaTarget.None
    private var switchGeneration: Long = 0L
    private var isDisposed: Boolean = false

    private var activeVideoSession: ActiveCharacterVideoSession? = null
    private var activePreviewView: PreviewView? = null
    private var boundCameraProvider: ProcessCameraProvider? = null

    private var desiredIsPlaying: Boolean = true
    private var desiredIsMuted: Boolean = true

    fun attachHost(container: CircularHostContainer) {
        if (hostContainer === container) return
        hostContainer = container
        enforceCircularOutline(container)
    }

    fun updatePlaybackControls(isPlaying: Boolean, isMuted: Boolean) {
        desiredIsPlaying = isPlaying
        desiredIsMuted = isMuted
        val session = activeVideoSession ?: return
        if (session.isReleased.get()) return
        try {
            session.player.volume = if (isMuted) 0f else 1f
            session.player.playWhenReady = isPlaying
            if (isPlaying &&
                session.player.playbackState == Player.STATE_IDLE &&
                session.player.mediaItemCount > 0
            ) {
                session.player.prepare()
            }
        } catch (_: Throwable) {
            // Ignore transient state race if player is mid-release
        }
    }

    fun switchToTarget(
        target: CharacterMediaTarget,
        lifecycleOwner: LifecycleOwner,
        isPlaying: Boolean,
        isMuted: Boolean,
        onVideoError: (String) -> Unit,
        onMissingVideoFile: () -> Unit,
        onCameraError: (String) -> Unit
    ) {
        if (isDisposed) return
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "CharacterCircleMediaController must run on Main thread"
        }

        desiredIsPlaying = isPlaying
        desiredIsMuted = isMuted

        val host = hostContainer ?: return

        val alreadyActiveAndHealthy = when (target) {
            is CharacterMediaTarget.None -> currentTarget == CharacterMediaTarget.None &&
                activeVideoSession == null && activePreviewView == null

            is CharacterMediaTarget.Camera -> currentTarget == target &&
                activePreviewView != null && activeVideoSession == null

            is CharacterMediaTarget.Video -> currentTarget == target &&
                activeVideoSession != null &&
                activeVideoSession?.isReleased?.get() == false &&
                activePreviewView == null
        }

        if (alreadyActiveAndHealthy) {
            enforceCircularOutline(host)
            updatePlaybackControls(isPlaying = isPlaying, isMuted = isMuted)
            return
        }

        val myGeneration = ++switchGeneration
        currentTarget = target

        try {
            // STEP 1: Strictly release Camera first if it was running or pending
            releaseCameraSafely(host)

            // STEP 2: Strictly stop, clear, & release previous ExoPlayer and remove old TextureView
            releaseVideoSessionSafely(host)

            // Ensure no orphaned surface views remain inside the circle container
            try {
                host.removeAllViews()
            } catch (_: Throwable) {
            }
            enforceCircularOutline(host)

            // STEP 3: Start ONLY the requested target on the Main thread
            when (target) {
                is CharacterMediaTarget.None -> Unit

                is CharacterMediaTarget.Video -> {
                    startVideoOnMain(
                        host = host,
                        localFilePath = target.localFilePath,
                        generation = myGeneration,
                        onVideoError = onVideoError,
                        onMissingVideoFile = onMissingVideoFile
                    )
                }

                is CharacterMediaTarget.Camera -> {
                    startCameraOnMain(
                        host = host,
                        isFrontCamera = target.isFrontCamera,
                        lifecycleOwner = lifecycleOwner,
                        generation = myGeneration,
                        onCameraError = onCameraError
                    )
                }
            }
        } catch (t: Throwable) {
            releaseCameraSafely(host)
            releaseVideoSessionSafely(host)
            currentTarget = CharacterMediaTarget.None
            val message = t.localizedMessage ?: t.javaClass.simpleName
            showSafeToast(appContext.getString(R.string.character_video_error_toast, message))
            onVideoError(message)
        }
    }

    private fun startVideoOnMain(
        host: CircularHostContainer,
        localFilePath: String,
        generation: Long,
        onVideoError: (String) -> Unit,
        onMissingVideoFile: () -> Unit
    ) {
        val videoFile = File(localFilePath)
        if (!videoFile.exists() || !videoFile.isFile || videoFile.length() <= 0L) {
            currentTarget = CharacterMediaTarget.None
            onMissingVideoFile()
            return
        }

        val textureView = TextureView(host.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isOpaque = false
            enforceCircularOutline(this)
        }

        val renderersFactory = DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(true)

        val trackSelector = DefaultTrackSelector(appContext).apply {
            setParameters(
                buildUponParameters()
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .build()
            )
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2_000,
                12_000,
                400,
                800
            )
            .build()

        val exoPlayer = ExoPlayer.Builder(appContext, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()

        lateinit var sessionRef: ActiveCharacterVideoSession

        val layoutListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (!sessionRef.isReleased.get() && view is TextureView) {
                enforceCircularOutline(view)
                applyCenterCropMatrix(view, sessionRef.videoWidth, sessionRef.videoHeight)
            }
        }

        val playerListener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (isDisposed || generation != switchGeneration || sessionRef.isReleased.get()) return
                sessionRef.videoWidth = videoSize.width
                sessionRef.videoHeight = videoSize.height
                enforceCircularOutline(textureView)
                enforceCircularOutline(host)
                applyCenterCropMatrix(textureView, videoSize.width, videoSize.height)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isDisposed || generation != switchGeneration) return
                val detail = buildString {
                    append(error.errorCodeName)
                    val causeMsg = error.cause?.localizedMessage ?: error.localizedMessage
                    if (!causeMsg.isNullOrBlank() && !causeMsg.contains(error.errorCodeName)) {
                        append(" - ")
                        append(causeMsg.take(90))
                    }
                }
                showSafeToast(appContext.getString(R.string.character_video_error_toast, detail))
                releaseVideoSessionSafely(hostContainer)
                currentTarget = CharacterMediaTarget.None
                onVideoError(detail)
            }
        }

        sessionRef = ActiveCharacterVideoSession(
            player = exoPlayer,
            textureView = textureView,
            playerListener = playerListener,
            layoutListener = layoutListener
        )

        textureView.addOnLayoutChangeListener(layoutListener)
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                if (!sessionRef.isReleased.get()) {
                    enforceCircularOutline(textureView)
                    applyCenterCropMatrix(textureView, sessionRef.videoWidth, sessionRef.videoHeight)
                }
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                if (!sessionRef.isReleased.get()) {
                    enforceCircularOutline(textureView)
                    applyCenterCropMatrix(textureView, sessionRef.videoWidth, sessionRef.videoHeight)
                }
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        activeVideoSession = sessionRef

        try {
            host.addView(textureView)
            enforceCircularOutline(textureView)
            enforceCircularOutline(host)

            exoPlayer.addListener(playerListener)
            exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
            exoPlayer.volume = if (desiredIsMuted) 0f else 1f
            exoPlayer.setVideoTextureView(textureView)
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = desiredIsPlaying
        } catch (t: Throwable) {
            releaseVideoSessionSafely(host)
            currentTarget = CharacterMediaTarget.None
            val message = t.localizedMessage ?: t.javaClass.simpleName
            showSafeToast(appContext.getString(R.string.character_video_error_toast, message))
            onVideoError(message)
        }
    }

    private fun startCameraOnMain(
        host: CircularHostContainer,
        isFrontCamera: Boolean,
        lifecycleOwner: LifecycleOwner,
        generation: Long,
        onCameraError: (String) -> Unit
    ) {
        val hasCameraPerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPerm) {
            currentTarget = CharacterMediaTarget.None
            val msg = appContext.getString(R.string.camera_permission_denied)
            showSafeToast(msg)
            onCameraError(msg)
            return
        }

        val previewView = PreviewView(host.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // COMPATIBLE uses TextureView instead of SurfaceView so circular outline clipping
            // always stays intact and never punches a rectangle through Compose layers.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            enforceCircularOutline(this)
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                enforceCircularOutline(v)
            }
        }

        host.addView(previewView)
        enforceCircularOutline(previewView)
        enforceCircularOutline(host)
        activePreviewView = previewView

        val cameraProviderFuture = ProcessCameraProvider.getInstance(appContext)
        val mainExecutor = ContextCompat.getMainExecutor(appContext)

        cameraProviderFuture.addListener(
            {
                try {
                    val provider = cameraProviderFuture.get()
                    if (isDisposed || generation != switchGeneration || activePreviewView !== previewView) {
                        // Switched away before async CameraX provider resolved — make sure camera stays unbound
                        runCatching { provider.unbindAll() }
                        return@addListener
                    }

                    provider.unbindAll()

                    val preferredSelector = if (isFrontCamera) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    val fallbackSelector = if (isFrontCamera) {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    } else {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    }
                    val resolvedSelector = when {
                        runCatching { provider.hasCamera(preferredSelector) }.getOrDefault(false) ->
                            preferredSelector
                        runCatching { provider.hasCamera(fallbackSelector) }.getOrDefault(false) ->
                            fallbackSelector
                        else -> preferredSelector
                    }

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    provider.bindToLifecycle(lifecycleOwner, resolvedSelector, preview)
                    boundCameraProvider = provider
                    enforceCircularOutline(previewView)
                    enforceCircularOutline(host)
                } catch (t: Throwable) {
                    releaseCameraSafely(hostContainer)
                    currentTarget = CharacterMediaTarget.None
                    val msg = t.localizedMessage ?: t.javaClass.simpleName
                    showSafeToast(appContext.getString(R.string.character_camera_error_toast, msg))
                    onCameraError(msg)
                }
            },
            mainExecutor
        )
    }

    private fun releaseVideoSessionSafely(host: CircularHostContainer?) {
        val session = activeVideoSession
        activeVideoSession = null
        if (session != null && session.isReleased.compareAndSet(false, true)) {
            try {
                session.textureView.removeOnLayoutChangeListener(session.layoutListener)
            } catch (_: Throwable) {
            }
            try {
                session.textureView.surfaceTextureListener = null
            } catch (_: Throwable) {
            }
            try {
                session.player.removeListener(session.playerListener)
            } catch (_: Throwable) {
            }
            try {
                session.player.playWhenReady = false
                session.player.stop()
            } catch (_: Throwable) {
            }
            try {
                session.player.clearMediaItems()
            } catch (_: Throwable) {
            }
            try {
                session.player.clearVideoTextureView(session.textureView)
                session.player.setVideoSurface(null)
            } catch (_: Throwable) {
            }
            try {
                session.player.release()
            } catch (_: Throwable) {
            }
            try {
                (session.textureView.parent as? ViewGroup)?.removeView(session.textureView)
                host?.removeView(session.textureView)
            } catch (_: Throwable) {
            }
        }
    }

    private fun releaseCameraSafely(host: CircularHostContainer?) {
        val preview = activePreviewView
        activePreviewView = null

        val provider = boundCameraProvider
        boundCameraProvider = null
        if (provider != null) {
            try {
                provider.unbindAll()
            } catch (_: Throwable) {
            }
        } else {
            try {
                val future = ProcessCameraProvider.getInstance(appContext)
                if (future.isDone) {
                    future.get().unbindAll()
                }
            } catch (_: Throwable) {
            }
        }

        if (preview != null) {
            try {
                (preview.parent as? ViewGroup)?.removeView(preview)
                host?.removeView(preview)
            } catch (_: Throwable) {
            }
        }
    }

    fun disposeOnMain() {
        if (isDisposed) return
        isDisposed = true
        switchGeneration++
        currentTarget = CharacterMediaTarget.None
        releaseCameraSafely(hostContainer)
        releaseVideoSessionSafely(hostContainer)
        try {
            hostContainer?.removeAllViews()
        } catch (_: Throwable) {
        }
        hostContainer = null
    }

    private fun showSafeToast(message: String) {
        try {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
        }
    }
}

private fun applyCenterCropMatrix(
    textureView: TextureView,
    videoWidth: Int,
    videoHeight: Int
) {
    val viewWidth = textureView.width.toFloat()
    val viewHeight = textureView.height.toFloat()
    if (viewWidth <= 0f || viewHeight <= 0f || videoWidth <= 0 || videoHeight <= 0) {
        textureView.setTransform(null)
        return
    }

    val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
    val viewAspect = viewWidth / viewHeight

    var scaleX = 1f
    var scaleY = 1f
    if (videoAspect > viewAspect) {
        scaleX = videoAspect / viewAspect
    } else {
        scaleY = viewAspect / videoAspect
    }

    val matrix = Matrix()
    matrix.setScale(
        max(0.01f, min(scaleX, 20f)),
        max(0.01f, min(scaleY, 20f)),
        viewWidth / 2f,
        viewHeight / 2f
    )
    textureView.setTransform(matrix)
}

@Composable
fun CharacterCircleMediaView(
    isCameraActive: Boolean,
    isFrontCamera: Boolean,
    videoFilePath: String?,
    videoSwitchNonce: Long,
    isPlaying: Boolean,
    isMuted: Boolean,
    isLoadingVideo: Boolean,
    isBroadcastMode: Boolean,
    onVideoPlaybackError: (String) -> Unit,
    onMissingVideoFile: () -> Unit,
    onCameraFailure: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val currentOnVideoError by rememberUpdatedState(onVideoPlaybackError)
    val currentOnMissingFile by rememberUpdatedState(onMissingVideoFile)
    val currentOnCameraFailure by rememberUpdatedState(onCameraFailure)

    val controller = remember(context.applicationContext) {
        CharacterCircleMediaController(context.applicationContext)
    }

    val target: CharacterMediaTarget = remember(
        isCameraActive,
        isFrontCamera,
        videoFilePath,
        videoSwitchNonce,
        isLoadingVideo
    ) {
        when {
            isLoadingVideo -> CharacterMediaTarget.None
            isCameraActive -> CharacterMediaTarget.Camera(isFrontCamera = isFrontCamera)
            !videoFilePath.isNullOrBlank() -> CharacterMediaTarget.Video(
                localFilePath = videoFilePath,
                switchNonce = videoSwitchNonce
            )
            else -> CharacterMediaTarget.None
        }
    }

    DisposableEffect(controller) {
        onDispose {
            controller.disposeOnMain()
        }
    }

    LaunchedEffect(controller, target, lifecycleOwner) {
        controller.switchToTarget(
            target = target,
            lifecycleOwner = lifecycleOwner,
            isPlaying = isPlaying,
            isMuted = isMuted,
            onVideoError = { currentOnVideoError(it) },
            onMissingVideoFile = { currentOnMissingFile() },
            onCameraError = { currentOnCameraFailure(it) }
        )
    }

    LaunchedEffect(controller, isPlaying, isMuted) {
        controller.updatePlaybackControls(isPlaying = isPlaying, isMuted = isMuted)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                shape = CircleShape
                clip = true
            }
            .clip(CircleShape)
            .testTag("character_circle_media_box")
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape),
            factory = { ctx ->
                CircularHostContainer(ctx).also { host ->
                    controller.attachHost(host)
                    controller.switchToTarget(
                        target = target,
                        lifecycleOwner = lifecycleOwner,
                        isPlaying = isPlaying,
                        isMuted = isMuted,
                        onVideoError = { currentOnVideoError(it) },
                        onMissingVideoFile = { currentOnMissingFile() },
                        onCameraError = { currentOnCameraFailure(it) }
                    )
                }
            },
            update = { host ->
                enforceCircularOutline(host)
                controller.attachHost(host)
            }
        )

        if (isLoadingVideo) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(StudioVoid.copy(alpha = 0.65f), CircleShape)
            ) {
                CircularProgressIndicator(
                    color = NeonCyan,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(28.dp)
                )
            }
        } else if (target == CharacterMediaTarget.None && !isBroadcastMode) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(StudioDeepNavy.copy(alpha = 0.55f), CircleShape)
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
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
