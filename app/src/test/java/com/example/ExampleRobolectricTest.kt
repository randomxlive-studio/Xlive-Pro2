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

        // Simulate switching character video 5 times in a row from external source files
        var previousSavedPath: String? = null
        repeat(5) { idx ->
            val externalSource = File(context.cacheDir, "picker_temp_$idx.mp4").apply {
                writeBytes("fake-mp4-video-payload-$idx".toByteArray())
            }
            val result = repo.copyCharacterVideoToInternalStorage(Uri.fromFile(externalSource))
            assertTrue(result.isSuccess)
            val copiedPath = result.getOrThrow()

            // Must be stored inside filesDir/character_videos and never reuse temporary picker URI
            assertTrue(copiedPath.startsWith(context.filesDir.absolutePath))
            assertTrue(repo.isValidCharacterVideoFile(copiedPath))
            if (previousSavedPath != null) {
                assertNotEquals(previousSavedPath, copiedPath)
                // Previous file still exists until deferred cleanup runs after player stops
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

        // Verify app restart restores the last selected character video from filesDir
        val restored = repo.loadInitialState()
        assertEquals(previousSavedPath, restored.characterLocalPath)

        // Delete the saved file and verify restart falls back cleanly to default null state without crashing
        File(previousSavedPath!!).delete()
        val restoredAfterMissing = repo.loadInitialState()
        assertNull(restoredAfterMissing.characterLocalPath)
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
