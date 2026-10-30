package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiReplyConfig
import com.example.engine.AdBlockerEngine
import com.example.engine.UniversalVideoResolver
import com.example.engine.isLikelyVideoStreamUrl
import com.example.service.LiveCommentBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
