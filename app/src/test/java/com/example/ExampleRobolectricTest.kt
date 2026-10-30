package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiReplyConfig
import com.example.crash.CrashReportManager
import com.example.data.StudioPreferencesRepository
import com.example.engine.AdBlockerEngine
import com.example.engine.UniversalVideoResolver
import com.example.engine.isLikelyVideoStreamUrl
import com.example.model.BadgeTransformState
import com.example.model.StudioUiState
import com.example.service.LiveCommentBus
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("RandomXLive", appName)
    }

    @Test
    fun `universal resolver extracts url and detects direct stream urls`() {
        val resolver = UniversalVideoResolver()
        val extracted = resolver.normalizeInputToUrl(
            "Watch this clip https://vm.tiktok.com/ZM12345/ right now"
        )
        assertEquals("https://vm.tiktok.com/ZM12345/", extracted)
        assertTrue(isLikelyVideoStreamUrl("https://cdn.example.com/live/master.m3u8?token=abc"))
        assertTrue(isLikelyVideoStreamUrl("https://cdn.example.com/media/clip.mp4"))
    }

    @Test
    fun `ad blocker blocks ad domains and youtube ad endpoints while allowing video streams`() {
        assertTrue(AdBlockerEngine.shouldBlockRequest("https://googleads.g.doubleclick.net/pagead/ads"))
        assertTrue(AdBlockerEngine.shouldBlockRequest("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(AdBlockerEngine.shouldBlockRequest("https://m.youtube.com/api/stats/ads?ver=2"))
        assertFalse(AdBlockerEngine.shouldBlockRequest("https://cdn.example.com/video/1080p.m3u8"))
        assertFalse(AdBlockerEngine.shouldBlockRequest("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"))
    }

    @Test
    fun `live comment bus filters system messages deduplicates hashes and prioritizes questions in max 3 queue`() {
        LiveCommentBus.setReaderActive(false)
        LiveCommentBus.ownUsernameLower = "mystreamer"
        LiveCommentBus.setReaderActive(true)

        // System messages, own comments, and very short spam should be ignored
        LiveCommentBus.submitCandidate("user1", "joined", fromAccessibility = true)
        LiveCommentBus.submitCandidate("user2", "followed the host", fromAccessibility = true)
        LiveCommentBus.submitCandidate("user3", "sent a Rose x5", fromAccessibility = true)
        LiveCommentBus.submitCandidate("mystreamer", "Welcome everyone to the stream!", fromAccessibility = true)
        LiveCommentBus.submitCandidate("spammer", "aaaaaaa", fromAccessibility = true)
        assertEquals(0, LiveCommentBus.waitingQueue.value.size)

        // Valid comment + duplicate check
        LiveCommentBus.submitCandidate("ahmed", "Nice gameplay bro!", fromAccessibility = true)
        LiveCommentBus.submitCandidate("ahmed", "Nice gameplay bro!", fromAccessibility = true)
        assertEquals(1, LiveCommentBus.waitingQueue.value.size)

        // Fill queue past 3 items and ensure max 3 cap + question priority
        LiveCommentBus.submitCandidate("sara", "What phone are you playing on?", fromAccessibility = true)
        LiveCommentBus.submitCandidate("omar", "Crazy clutch right there", fromAccessibility = true)
        LiveCommentBus.submitCandidate("lina", "Let's gooo team", fromAccessibility = true)

        assertEquals(3, LiveCommentBus.waitingQueue.value.size)

        val firstPolled = LiveCommentBus.pollNextCommentForReply()
        assertNotNull(firstPolled)
        assertTrue(firstPolled!!.isQuestion)
        assertEquals("sara", firstPolled.username)

        LiveCommentBus.setReaderActive(false)
        assertNull(LiveCommentBus.pollNextCommentForReply())
    }

    @Test
    fun `ai reply config builds exact sarcastic prompt with viewer username`() {
        val prompt = AiReplyConfig.buildPromptForComment("karim_99", "Why do you always miss that jump?")
        assertTrue(prompt.contains("You are a sarcastic, witty, funny live-stream character."))
        assertTrue(prompt.contains("@karim_99: Why do you always miss that jump?"))
        assertTrue(prompt.contains("Reply in the same language and dialect as the comment."))
    }

    @Test
    fun `character video copies into filesDir switches repeatedly restores last video and falls back if missing`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = StudioPreferencesRepository(context)

        var previousSavedPath: String? = null
        repeat(5) { idx ->
            val externalSource = File(context.cacheDir, "picker_temp_$idx.mp4").apply {
                writeBytes("fake-mp4-video-payload-$idx".toByteArray())
            }
            val result = repo.copyCharacterVideoToInternalStorage(Uri.fromFile(externalSource))
            assertTrue(result.isSuccess)
            val copiedPath = result.getOrThrow()

            assertTrue(copiedPath.startsWith(context.filesDir.absolutePath))
            assertTrue(repo.isValidCharacterVideoFile(copiedPath))
            if (previousSavedPath != null) {
                assertNotEquals(previousSavedPath, copiedPath)
                assertTrue(File(previousSavedPath!!).exists())
            }

            repo.saveState(StudioUiState(characterLocalPath = copiedPath))
            repo.cleanupOldCharacterVideos(activePathToKeep = copiedPath)

            if (previousSavedPath != null) {
                assertFalse(File(previousSavedPath!!).exists())
            }
            previousSavedPath = copiedPath
            externalSource.delete()
        }

        val restored = repo.loadInitialState()
        assertEquals(previousSavedPath, restored.characterLocalPath)

        File(previousSavedPath!!).delete()
        val restoredAfterMissing = repo.loadInitialState()
        assertNull(restoredAfterMissing.characterLocalPath)
    }

    @Test
    fun `reset gameplay and reset circle delete internal files independently and clear datastore keys`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = StudioPreferencesRepository(context)

        val tempGameplaySrc = File(context.cacheDir, "src_gameplay.mp4").apply {
            writeBytes("gameplay-video-bytes".toByteArray())
        }
        val tempCircleSrc = File(context.cacheDir, "src_circle.mp4").apply {
            writeBytes("circle-video-bytes".toByteArray())
        }

        val gameplaySaved = repo.copyPickedMediaToInternalStorage(Uri.fromFile(tempGameplaySrc), "gameplay")
        assertNotNull(gameplaySaved)
        val gameplayPath = gameplaySaved!!.first

        val circlePath = repo.copyCharacterVideoToInternalStorage(Uri.fromFile(tempCircleSrc)).getOrThrow()

        repo.saveState(
            StudioUiState(
                gameplayLocalPath = gameplayPath,
                activeUrl = "https://example.com/stream.mp4",
                characterLocalPath = circlePath
            )
        )

        // 1. Delete gameplay ONLY -> circle video must remain intact on disk & in DataStore
        repo.deleteGameplayMediaAndClearPrefs(gameplayPath)
        assertFalse(File(gameplayPath).exists())
        assertTrue(File(circlePath).exists())

        val afterGameplayReset = repo.loadInitialState()
        assertNull(afterGameplayReset.gameplayLocalPath)
        assertEquals("", afterGameplayReset.activeUrl)
        assertEquals(circlePath, afterGameplayReset.characterLocalPath)

        // 2. Delete circle ONLY -> circle video deleted from disk & DataStore; picking again works
        repo.deleteCharacterMediaAndClearPrefs(circlePath)
        assertFalse(File(circlePath).exists())
        val afterCircleReset = repo.loadInitialState()
        assertNull(afterCircleReset.characterLocalPath)

        // 3. Picking a new circle video after deleting works normally
        val circlePathAfterReset = repo.copyCharacterVideoToInternalStorage(Uri.fromFile(tempCircleSrc)).getOrThrow()
        assertTrue(File(circlePathAfterReset).exists())
        repo.deleteCharacterMediaAndClearPrefs(circlePathAfterReset)

        tempGameplaySrc.delete()
        tempCircleSrc.delete()
    }

    @Test
    fun `badge transform clamps scale to 0_5x_2_5x and persists portrait and landscape states`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = StudioPreferencesRepository(context)

        val tooSmall = BadgeTransformState(x = -0.5f, y = 2.0f, scale = 0.1f).clamped()
        assertEquals(0f, tooSmall.x, 0.0001f)
        assertEquals(0.98f, tooSmall.y, 0.0001f)
        assertEquals(0.5f, tooSmall.scale, 0.0001f)

        val tooBig = BadgeTransformState(x = 0.25f, y = 0.35f, scale = 9.0f).clamped()
        assertEquals(2.5f, tooBig.scale, 0.0001f)

        val customPortrait = BadgeTransformState(x = 0.22f, y = 0.44f, scale = 1.65f)
        val customLandscape = BadgeTransformState(x = 0.55f, y = 0.18f, scale = 0.80f)
        repo.saveState(
            StudioUiState(
                badgePortraitState = customPortrait,
                badgeLandscapeState = customLandscape
            )
        )

        val loaded = repo.loadInitialState()
        assertEquals(customPortrait.x, loaded.badgePortraitState.x, 0.0001f)
        assertEquals(customPortrait.y, loaded.badgePortraitState.y, 0.0001f)
        assertEquals(customPortrait.scale, loaded.badgePortraitState.scale, 0.0001f)
        assertEquals(customLandscape.x, loaded.badgeLandscapeState.x, 0.0001f)
        assertEquals(customLandscape.y, loaded.badgeLandscapeState.y, 0.0001f)
        assertEquals(customLandscape.scale, loaded.badgeLandscapeState.scale, 0.0001f)
    }

    @Test
    fun `crash report manager reads and clears saved stack trace file`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crashFile = File(context.filesDir, "last_crash_report.txt")
        crashFile.writeText("java.lang.RuntimeException: Test crash stack trace")

        val loaded = CrashReportManager.readPendingCrashReport(context)
        assertNotNull(loaded)
        assertTrue(loaded!!.contains("Test crash stack trace"))

        CrashReportManager.clearCrashReport(context)
        assertNull(CrashReportManager.readPendingCrashReport(context))
    }
}
