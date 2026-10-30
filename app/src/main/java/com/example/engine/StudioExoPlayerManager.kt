package com.example.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.example.model.VideoQualityPreference

/**
 * ViewModel-scoped ExoPlayer manager (single source of truth for media players).
 *
 * Memory safeguards:
 * - Reuses the existing ExoPlayer via `stop()` -> `clearMediaItems()` -> `setMediaItem()` -> `prepare()`
 *   instead of allocating new ExoPlayer instances on every video change or recomposition.
 * - Uses `DefaultAllocator(trimOnReset = true)` and a strict `DefaultLoadControl` capped at
 *   18s max buffer and `32 MB` target buffer bytes (`setPrioritizeTimeOverSizeThresholds(false)`).
 * - Releases the player completely (`stop`, `clearMediaItems`, `clearVideoTextureView`, `release`)
 *   when Reset is pressed, when switching to Camera/WebView, or when the ViewModel is cleared,
 *   and lazily recreates it if a new video is picked later.
 */
@OptIn(UnstableApi::class)
class StudioExoPlayerManager(
    context: Context
) {
    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val GAMEPLAY_MIN_BUFFER_MS = 6_000
        const val GAMEPLAY_MAX_BUFFER_MS = 18_000
        const val GAMEPLAY_PLAYBACK_BUFFER_MS = 1_200
        const val GAMEPLAY_REBUFFER_MS = 2_500
        const val GAMEPLAY_TARGET_BUFFER_BYTES = 32 * 1024 * 1024 // 32 MB cap

        const val CIRCLE_MIN_BUFFER_MS = 2_000
        const val CIRCLE_MAX_BUFFER_MS = 15_000
        const val CIRCLE_PLAYBACK_BUFFER_MS = 500
        const val CIRCLE_REBUFFER_MS = 1_000
        const val CIRCLE_TARGET_BUFFER_BYTES = 16 * 1024 * 1024 // 16 MB cap for local circle clip
    }

    val gameplayVoiceProcessor = CenterChannelVoiceReducerProcessor()

    private var gameplayPlayer: ExoPlayer? = null
    private var gameplayAllocator: DefaultAllocator? = null
    private var gameplayTrackSelector: DefaultTrackSelector? = null
    private var gameplayHttpFactory: DefaultHttpDataSource.Factory? = null
    private var gameplayBoundTextureView: TextureView? = null
    private var gameplayLoadedKey: String? = null

    private var characterPlayer: ExoPlayer? = null
    private var characterAllocator: DefaultAllocator? = null
    private var characterBoundTextureView: TextureView? = null
    private var characterLoadedKey: String? = null

    fun getOrCreateGameplayPlayer(
        headers: Map<String, String> = emptyMap(),
        qualityPreference: VideoQualityPreference = VideoQualityPreference.AUTO_720P_MIN
    ): ExoPlayer {
        val existing = gameplayPlayer
        if (existing != null) {
            updateGameplayHeaders(headers)
            updateGameplayQuality(qualityPreference)
            return existing
        }

        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setUserAgent(headers["User-Agent"] ?: UniversalVideoResolver.MOBILE_CHROME_UA)
            .setDefaultRequestProperties(headers)

        val dataSourceFactory = DefaultDataSource.Factory(appContext, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val trackSelector = DefaultTrackSelector(appContext).apply {
            setParameters(buildTrackParameters(this, qualityPreference))
        }

        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                GAMEPLAY_MIN_BUFFER_MS,
                GAMEPLAY_MAX_BUFFER_MS,
                GAMEPLAY_PLAYBACK_BUFFER_MS,
                GAMEPLAY_REBUFFER_MS
            )
            .setTargetBufferBytes(GAMEPLAY_TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()

        val renderersFactory = object : DefaultRenderersFactory(appContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(gameplayVoiceProcessor))
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }.setEnableDecoderFallback(true)

        val created = ExoPlayer.Builder(appContext, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
            }

        gameplayAllocator = allocator
        gameplayHttpFactory = httpFactory
        gameplayTrackSelector = trackSelector
        gameplayPlayer = created
        gameplayLoadedKey = null
        return created
    }

    fun updateGameplayHeaders(headers: Map<String, String>) {
        val factory = gameplayHttpFactory ?: return
        runCatching {
            factory.setUserAgent(headers["User-Agent"] ?: UniversalVideoResolver.MOBILE_CHROME_UA)
            factory.setDefaultRequestProperties(headers)
        }
    }

    fun updateGameplayQuality(qualityPreference: VideoQualityPreference) {
        val selector = gameplayTrackSelector ?: return
        runCatching {
            selector.setParameters(buildTrackParameters(selector, qualityPreference))
        }
    }

    fun loadGameplayMedia(
        player: ExoPlayer,
        loadKey: String,
        mediaItem: MediaItem,
        playWhenReady: Boolean
    ) {
        if (gameplayLoadedKey == loadKey && player.mediaItemCount > 0) {
            player.playWhenReady = playWhenReady
            if (playWhenReady && player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            return
        }
        gameplayLoadedKey = loadKey
        player.stop()
        player.clearMediaItems()
        runCatching { gameplayAllocator?.trim() }
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    fun attachGameplayTextureView(player: ExoPlayer, textureView: TextureView) {
        if (gameplayBoundTextureView === textureView) return
        val prev = gameplayBoundTextureView
        if (prev != null) {
            runCatching { player.clearVideoTextureView(prev) }
        }
        gameplayBoundTextureView = textureView
        runCatching { player.setVideoTextureView(textureView) }
    }

    fun detachGameplayTextureView(textureView: TextureView?) {
        val player = gameplayPlayer
        if (textureView != null && player != null) {
            runCatching { player.clearVideoTextureView(textureView) }
        }
        if (gameplayBoundTextureView === textureView) {
            gameplayBoundTextureView = null
        }
    }

    fun releaseGameplayPlayer() {
        runOnMainSyncOrPost {
            val player = gameplayPlayer
            val tv = gameplayBoundTextureView
            val allocator = gameplayAllocator
            gameplayPlayer = null
            gameplayBoundTextureView = null
            gameplayTrackSelector = null
            gameplayHttpFactory = null
            gameplayAllocator = null
            gameplayLoadedKey = null

            if (player != null) {
                runCatching { player.playWhenReady = false }
                runCatching { player.stop() }
                runCatching { player.clearMediaItems() }
                if (tv != null) {
                    runCatching { player.clearVideoTextureView(tv) }
                }
                runCatching { player.setVideoSurface(null) }
                runCatching { player.release() }
            }
            runCatching { allocator?.trim() }
        }
    }

    fun getOrCreateCharacterPlayer(): ExoPlayer {
        val existing = characterPlayer
        if (existing != null) return existing

        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
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
            .setAllocator(allocator)
            .setBufferDurationsMs(
                CIRCLE_MIN_BUFFER_MS,
                CIRCLE_MAX_BUFFER_MS,
                CIRCLE_PLAYBACK_BUFFER_MS,
                CIRCLE_REBUFFER_MS
            )
            .setTargetBufferBytes(CIRCLE_TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()

        val created = ExoPlayer.Builder(appContext, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
            }

        characterAllocator = allocator
        characterPlayer = created
        characterLoadedKey = null
        return created
    }

    fun loadCharacterMedia(
        player: ExoPlayer,
        loadKey: String,
        mediaItem: MediaItem,
        playWhenReady: Boolean,
        isMuted: Boolean
    ) {
        player.volume = if (isMuted) 0f else 1f
        if (characterLoadedKey == loadKey && player.mediaItemCount > 0) {
            player.playWhenReady = playWhenReady
            if (playWhenReady && player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            return
        }
        characterLoadedKey = loadKey
        player.stop()
        player.clearMediaItems()
        runCatching { characterAllocator?.trim() }
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    fun attachCharacterTextureView(player: ExoPlayer, textureView: TextureView) {
        if (characterBoundTextureView === textureView) return
        val prev = characterBoundTextureView
        if (prev != null) {
            runCatching { player.clearVideoTextureView(prev) }
        }
        characterBoundTextureView = textureView
        runCatching { player.setVideoTextureView(textureView) }
    }

    fun detachCharacterTextureView(textureView: TextureView?) {
        val player = characterPlayer
        if (textureView != null && player != null) {
            runCatching { player.clearVideoTextureView(textureView) }
        }
        if (characterBoundTextureView === textureView) {
            characterBoundTextureView = null
        }
    }

    fun releaseCharacterPlayer() {
        runOnMainSyncOrPost {
            val player = characterPlayer
            val tv = characterBoundTextureView
            val allocator = characterAllocator
            characterPlayer = null
            characterBoundTextureView = null
            characterAllocator = null
            characterLoadedKey = null

            if (player != null) {
                runCatching { player.playWhenReady = false }
                runCatching { player.stop() }
                runCatching { player.clearMediaItems() }
                if (tv != null) {
                    runCatching { player.clearVideoTextureView(tv) }
                }
                runCatching { player.setVideoSurface(null) }
                runCatching { player.release() }
            }
            runCatching { allocator?.trim() }
        }
    }

    fun trimMemory() {
        runOnMainSyncOrPost {
            runCatching { gameplayAllocator?.trim() }
            runCatching { characterAllocator?.trim() }
        }
    }

    fun releaseAll() {
        releaseGameplayPlayer()
        releaseCharacterPlayer()
    }

    private fun runOnMainSyncOrPost(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun buildTrackParameters(
        trackSelector: DefaultTrackSelector,
        qualityPreference: VideoQualityPreference
    ): DefaultTrackSelector.Parameters {
        val builder = trackSelector.buildUponParameters()
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
}
