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
import androidx.media3.exoplayer.ExoPlayer
import com.example.R
import com.example.engine.StudioExoPlayerManager
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

/**
 * Main-thread circular media controller that reuses:
 * 1. The ViewModel-managed `StudioExoPlayerManager` character player (`setMediaItem` + `prepare` on
 *    video change; full `releaseCharacterPlayer()` on Reset or when switching to Camera).
 * 2. A single cached `ProcessCameraProvider` (`bindToLifecycle` once per active camera state;
 *    `unbindAll()` immediately when switching away or pressing Reset circle).
 * 3. A single reusable `TextureView` for video and `PreviewView` (`COMPATIBLE`) for camera,
 *    preserving circular clipping after every switch and reset.
 */
@OptIn(UnstableApi::class)
private class CharacterCircleMediaController(
    private val appContext: Context,
    private val playerManager: StudioExoPlayerManager
) {
    private var hostContainer: CircularHostContainer? = null
    private var currentTarget: CharacterMediaTarget = CharacterMediaTarget.None
    private var switchGeneration: Long = 0L
    private var isDisposed: Boolean = false

    private var videoTextureView: TextureView? = null
    private var attachedPlayer: ExoPlayer? = null
    private var attachedListener: Player.Listener? = null
    private val reusableCropMatrix = Matrix()

    @Volatile
    private var videoWidth: Int = 0

    @Volatile
    private var videoHeight: Int = 0

    private var activePreviewView: PreviewView? = null
    private var cachedCameraProvider: ProcessCameraProvider? = null
    private val isCameraBound = AtomicBoolean(false)

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
        val player = attachedPlayer ?: return
        try {
            player.volume = if (isMuted) 0f else 1f
            player.playWhenReady = isPlaying
            if (isPlaying && player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
                player.prepare()
            }
        } catch (_: Throwable) {
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
                attachedPlayer == null && activePreviewView == null && !isCameraBound.get()

            is CharacterMediaTarget.Camera -> currentTarget == target &&
                activePreviewView != null && attachedPlayer == null && isCameraBound.get()

            is CharacterMediaTarget.Video -> currentTarget == target &&
                attachedPlayer != null && videoTextureView?.parent === host && !isCameraBound.get()
        }

        if (alreadyActiveAndHealthy) {
            enforceCircularOutline(host)
            updatePlaybackControls(isPlaying = isPlaying, isMuted = isMuted)
            return
        }

        val myGeneration = ++switchGeneration
        currentTarget = target

        try {
            when (target) {
                is CharacterMediaTarget.None -> {
                    unbindCameraSafely(host)
                    detachVideoViewSafely(host, releasePlayerMemory = false)
                    runCatching { host.removeAllViews() }
                    enforceCircularOutline(host)
                }

                is CharacterMediaTarget.Video -> {
                    // Camera and Video NEVER run at the same time: unbind CameraX completely first
                    unbindCameraSafely(host)
                    startOrSwitchVideoOnMain(
                        host = host,
                        localFilePath = target.localFilePath,
                        switchNonce = target.switchNonce,
                        generation = myGeneration,
                        onVideoError = onVideoError,
                        onMissingVideoFile = onMissingVideoFile
                    )
                }

                is CharacterMediaTarget.Camera -> {
                    // Camera and Video NEVER run at the same time: release video player completely first
                    detachVideoViewSafely(host, releasePlayerMemory = true)
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
            unbindCameraSafely(host)
            detachVideoViewSafely(host, releasePlayerMemory = true)
            currentTarget = CharacterMediaTarget.None
            val message = t.localizedMessage ?: t.javaClass.simpleName
            showSafeToast(appContext.getString(R.string.character_video_error_toast, message))
            onVideoError(message)
        }
    }

    private fun startOrSwitchVideoOnMain(
        host: CircularHostContainer,
        localFilePath: String,
        switchNonce: Long,
        generation: Long,
        onVideoError: (String) -> Unit,
        onMissingVideoFile: () -> Unit
    ) {
        val videoFile = File(localFilePath)
        if (!videoFile.exists() || !videoFile.isFile || videoFile.length() <= 0L) {
            detachVideoViewSafely(host, releasePlayerMemory = true)
            currentTarget = CharacterMediaTarget.None
            onMissingVideoFile()
            return
        }

        // Reuse the ViewModel-managed ExoPlayer and reusable TextureView across video changes
        val exoPlayer = playerManager.getOrCreateCharacterPlayer()
        val textureView = getOrCreateCircularTextureView(host)

        if (attachedPlayer !== exoPlayer) {
            detachCurrentPlayerListener()
            val listener = object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (isDisposed || generation > switchGeneration) return
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height
                    videoTextureView?.let { tv ->
                        enforceCircularOutline(tv)
                        applyCenterCropMatrix(tv, videoSize.width, videoSize.height, reusableCropMatrix)
                    }
                    enforceCircularOutline(host)
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (isDisposed) return
                    val detail = buildString {
                        append(error.errorCodeName)
                        val causeMsg = error.cause?.localizedMessage ?: error.localizedMessage
                        if (!causeMsg.isNullOrBlank() && !causeMsg.contains(error.errorCodeName)) {
                            append(" - ")
                            append(causeMsg.take(90))
                        }
                    }
                    showSafeToast(appContext.getString(R.string.character_video_error_toast, detail))
                    detachVideoViewSafely(hostContainer, releasePlayerMemory = true)
                    currentTarget = CharacterMediaTarget.None
                    onVideoError(detail)
                }
            }
            exoPlayer.addListener(listener)
            attachedPlayer = exoPlayer
            attachedListener = listener
        }

        try {
            if (textureView.parent !== host) {
                (textureView.parent as? ViewGroup)?.removeView(textureView)
                host.removeAllViews()
                host.addView(textureView)
            }
            enforceCircularOutline(textureView)
            enforceCircularOutline(host)

            playerManager.attachCharacterTextureView(exoPlayer, textureView)
            playerManager.loadCharacterMedia(
                player = exoPlayer,
                loadKey = "$switchNonce|$localFilePath",
                mediaItem = MediaItem.fromUri(Uri.fromFile(videoFile)),
                playWhenReady = desiredIsPlaying,
                isMuted = desiredIsMuted
            )

            val currentVideoSize = exoPlayer.videoSize
            if (currentVideoSize.width > 0 && currentVideoSize.height > 0) {
                videoWidth = currentVideoSize.width
                videoHeight = currentVideoSize.height
                applyCenterCropMatrix(
                    textureView,
                    currentVideoSize.width,
                    currentVideoSize.height,
                    reusableCropMatrix
                )
            }
        } catch (t: Throwable) {
            detachVideoViewSafely(host, releasePlayerMemory = true)
            currentTarget = CharacterMediaTarget.None
            val message = t.localizedMessage ?: t.javaClass.simpleName
            showSafeToast(appContext.getString(R.string.character_video_error_toast, message))
            onVideoError(message)
        }
    }

    private fun getOrCreateCircularTextureView(host: CircularHostContainer): TextureView {
        val existing = videoTextureView
        if (existing != null && existing.context === host.context) {
            enforceCircularOutline(existing)
            return existing
        }

        val tv = TextureView(host.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isOpaque = false
            enforceCircularOutline(this)
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (v is TextureView) {
                    enforceCircularOutline(v)
                    applyCenterCropMatrix(v, videoWidth, videoHeight, reusableCropMatrix)
                }
            }
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    enforceCircularOutline(this@apply)
                    applyCenterCropMatrix(this@apply, videoWidth, videoHeight, reusableCropMatrix)
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    enforceCircularOutline(this@apply)
                    applyCenterCropMatrix(this@apply, videoWidth, videoHeight, reusableCropMatrix)
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
        videoTextureView = tv
        return tv
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

        val previewView = activePreviewView?.takeIf { it.context === host.context }
            ?: PreviewView(host.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                enforceCircularOutline(this)
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    enforceCircularOutline(v)
                }
            }

        if (previewView.parent !== host) {
            (previewView.parent as? ViewGroup)?.removeView(previewView)
            host.removeAllViews()
            host.addView(previewView)
        }
        enforceCircularOutline(previewView)
        enforceCircularOutline(host)
        activePreviewView = previewView

        val cached = cachedCameraProvider
        if (cached != null) {
            bindCameraProviderOnce(
                provider = cached,
                previewView = previewView,
                host = host,
                isFrontCamera = isFrontCamera,
                lifecycleOwner = lifecycleOwner,
                generation = generation,
                onCameraError = onCameraError
            )
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(appContext)
        val mainExecutor = ContextCompat.getMainExecutor(appContext)
        cameraProviderFuture.addListener(
            {
                try {
                    val provider = cameraProviderFuture.get()
                    cachedCameraProvider = provider
                    bindCameraProviderOnce(
                        provider = provider,
                        previewView = previewView,
                        host = host,
                        isFrontCamera = isFrontCamera,
                        lifecycleOwner = lifecycleOwner,
                        generation = generation,
                        onCameraError = onCameraError
                    )
                } catch (t: Throwable) {
                    unbindCameraSafely(hostContainer)
                    currentTarget = CharacterMediaTarget.None
                    val msg = t.localizedMessage ?: t.javaClass.simpleName
                    showSafeToast(appContext.getString(R.string.character_camera_error_toast, msg))
                    onCameraError(msg)
                }
            },
            mainExecutor
        )
    }

    private fun bindCameraProviderOnce(
        provider: ProcessCameraProvider,
        previewView: PreviewView,
        host: CircularHostContainer,
        isFrontCamera: Boolean,
        lifecycleOwner: LifecycleOwner,
        generation: Long,
        onCameraError: (String) -> Unit
    ) {
        if (isDisposed || generation != switchGeneration || activePreviewView !== previewView) {
            runCatching { provider.unbindAll() }
            isCameraBound.set(false)
            return
        }

        try {
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
            isCameraBound.set(true)
            enforceCircularOutline(previewView)
            enforceCircularOutline(host)
        } catch (t: Throwable) {
            unbindCameraSafely(hostContainer)
            currentTarget = CharacterMediaTarget.None
            val msg = t.localizedMessage ?: t.javaClass.simpleName
            showSafeToast(appContext.getString(R.string.character_camera_error_toast, msg))
            onCameraError(msg)
        }
    }

    private fun detachCurrentPlayerListener() {
        val player = attachedPlayer
        val listener = attachedListener
        attachedPlayer = null
        attachedListener = null
        if (player != null && listener != null) {
            runCatching { player.removeListener(listener) }
        }
    }

    private fun detachVideoViewSafely(
        host: CircularHostContainer?,
        releasePlayerMemory: Boolean
    ) {
        val tv = videoTextureView
        detachCurrentPlayerListener()
        playerManager.detachCharacterTextureView(tv)
        if (releasePlayerMemory) {
            playerManager.releaseCharacterPlayer()
            videoWidth = 0
            videoHeight = 0
        }
        if (tv != null) {
            runCatching { (tv.parent as? ViewGroup)?.removeView(tv) }
            runCatching { host?.removeView(tv) }
        }
    }

    private fun unbindCameraSafely(host: CircularHostContainer?) {
        isCameraBound.set(false)
        val preview = activePreviewView
        activePreviewView = null

        val provider = cachedCameraProvider
        if (provider != null) {
            runCatching { provider.unbindAll() }
        } else {
            runCatching {
                val future = ProcessCameraProvider.getInstance(appContext)
                if (future.isDone) {
                    val resolved = future.get()
                    cachedCameraProvider = resolved
                    resolved.unbindAll()
                }
            }
        }

        if (preview != null) {
            runCatching { preview.surfaceProvider }
            runCatching { (preview.parent as? ViewGroup)?.removeView(preview) }
            runCatching { host?.removeView(preview) }
        }
    }

    fun disposeOnMain() {
        if (isDisposed) return
        isDisposed = true
        switchGeneration++
        currentTarget = CharacterMediaTarget.None
        unbindCameraSafely(hostContainer)
        // Detach surface on recomposition/orientation change without destroying ViewModel player;
        // ViewModel.onCleared() and Reset Circle handle full player release.
        detachVideoViewSafely(hostContainer, releasePlayerMemory = false)
        videoTextureView?.surfaceTextureListener = null
        videoTextureView = null
        runCatching { hostContainer?.removeAllViews() }
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
    videoHeight: Int,
    matrix: Matrix
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

    matrix.reset()
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
    playerManager: StudioExoPlayerManager,
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

    val controller = remember(context.applicationContext, playerManager) {
        CharacterCircleMediaController(context.applicationContext, playerManager)
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
