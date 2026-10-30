package com.example.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TikTokCommentAccessibilityService : AccessibilityService() {

    companion object {
        private val TIKTOK_PACKAGES = setOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill"
        )
        private const val MIN_SCAN_INTERVAL_MS = 350L
    }

    private var lastScanTimestampMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        LiveCommentBus.setAccessibilityConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!LiveCommentBus.isReaderActive.value) return
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg !in TIKTOK_PACKAGES) return

        val now = System.currentTimeMillis()
        if (now - lastScanTimestampMs < MIN_SCAN_INTERVAL_MS) return
        lastScanTimestampMs = now

        val root = rootInActiveWindow ?: return
        try {
            val screenBounds = Rect()
            root.getBoundsInScreen(screenBounds)
            val screenHeight = screenBounds.height().coerceAtLeast(800)
            val screenWidth = screenBounds.width().coerceAtLeast(400)

            val extractedPairs = mutableListOf<Pair<String, String>>()
            traverseForComments(
                node = root,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                outPairs = extractedPairs
            )

            // Submit in top-to-bottom order so newest appears last in the priority queue
            extractedPairs.takeLast(8).forEach { (user, comment) ->
                LiveCommentBus.submitCandidate(
                    rawUsername = user,
                    rawText = comment,
                    fromAccessibility = true
                )
            }
        } catch (_: Throwable) {
        }
    }

    private fun traverseForComments(
        node: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        outPairs: MutableList<Pair<String, String>>
    ) {
        if (node == null || !node.isVisibleToUser) return

        val viewId = node.viewIdResourceName.orEmpty().lowercase()
        val className = node.className?.toString().orEmpty()

        // Check if this container represents a single chat row inside a RecyclerView/ListView
        if ((className.contains("ViewGroup") ||
                className.contains("LinearLayout") ||
                className.contains("RelativeLayout") ||
                className.contains("ConstraintLayout") ||
                className.contains("FrameLayout")) &&
            node.childCount in 1..4
        ) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val inChatVerticalZone = bounds.top >= (screenHeight * 0.42f) &&
                bounds.bottom <= (screenHeight * 0.93f)
            val inChatHorizontalZone = bounds.left <= (screenWidth * 0.85f) &&
                bounds.width() >= (screenWidth * 0.18f)
            val looksLikeChatId = viewId.contains("chat") ||
                viewId.contains("comment") ||
                viewId.contains("msg") ||
                viewId.contains("message")

            if ((inChatVerticalZone && inChatHorizontalZone) || looksLikeChatId) {
                val directTexts = collectDirectLeafTexts(node)
                if (directTexts.size == 2) {
                    val candidateUser = directTexts[0]
                    val candidateMsg = directTexts[1]
                    if (candidateUser.length <= 36 && candidateMsg.length >= 2) {
                        outPairs.add(candidateUser to candidateMsg)
                        return
                    }
                } else if (directTexts.size == 1) {
                    parseSingleCombinedLine(directTexts[0])?.let { pair ->
                        outPairs.add(pair)
                        return
                    }
                }
            }
        }

        // Also check standalone leaf TextViews in the TikTok LIVE comment region
        if (node.childCount == 0 && !node.text.isNullOrBlank()) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val inChatZone = bounds.top >= (screenHeight * 0.45f) &&
                bounds.bottom <= (screenHeight * 0.92f) &&
                bounds.left <= (screenWidth * 0.80f)
            if (inChatZone || viewId.contains("comment") || viewId.contains("chat")) {
                parseSingleCombinedLine(node.text.toString())?.let { outPairs.add(it) }
            }
        }

        val count = node.childCount
        for (i in 0 until count) {
            traverseForComments(node.getChild(i), screenWidth, screenHeight, outPairs)
        }
    }

    private fun collectDirectLeafTexts(container: AccessibilityNodeInfo): List<String> {
        val result = mutableListOf<String>()
        for (i in 0 until container.childCount) {
            val child = container.getChild(i) ?: continue
            if (!child.isVisibleToUser) continue
            val t = child.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) {
                result.add(t)
            }
        }
        return result
    }

    private fun parseSingleCombinedLine(raw: String): Pair<String, String>? {
        val cleaned = raw.trim()
        if (cleaned.length < 3) return null
        val colonIdx = cleaned.indexOf(':')
        if (colonIdx in 1..32 && colonIdx < cleaned.length - 2) {
            val user = cleaned.substring(0, colonIdx).trim()
            val msg = cleaned.substring(colonIdx + 1).trim()
            return user to msg
        }
        val newlineIdx = cleaned.indexOf('\n')
        if (newlineIdx in 1..32 && newlineIdx < cleaned.length - 2) {
            val user = cleaned.substring(0, newlineIdx).trim()
            val msg = cleaned.substring(newlineIdx + 1).trim()
            return user to msg
        }
        return "" to cleaned
    }

    override fun onInterrupt() {
        LiveCommentBus.setAccessibilityConnected(false)
    }

    override fun onDestroy() {
        LiveCommentBus.setAccessibilityConnected(false)
        super.onDestroy()
    }
}
