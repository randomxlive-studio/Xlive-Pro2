package com.example.service

import com.example.ai.AiReplyConfig
import com.example.model.LiveCommentItem
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object LiveCommentBus {

    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    private val _isOcrFallbackRunning = MutableStateFlow(false)
    val isOcrFallbackRunning: StateFlow<Boolean> = _isOcrFallbackRunning.asStateFlow()

    private val _isReaderActive = MutableStateFlow(false)
    val isReaderActive: StateFlow<Boolean> = _isReaderActive.asStateFlow()

    private val _waitingQueue = MutableStateFlow<List<LiveCommentItem>>(emptyList())
    val waitingQueue: StateFlow<List<LiveCommentItem>> = _waitingQueue.asStateFlow()

    @Volatile
    var ownUsernameLower: String = "randomxlive"

    @Volatile
    var lastAccessibilityCommentMs: Long = 0L
        private set

    private val handledHashes = LinkedHashSet<String>()
    private const val MAX_HANDLED_HISTORY = 1200

    private val SYSTEM_EVENT_PATTERNS = listOf(
        "joined",
        "followed",
        "followed the host",
        "shared the live",
        "shared live",
        "liked the live",
        "sent a ",
        "sent ",
        "rose x",
        "gift",
        "treasure box",
        "top viewer",
        "weekly ranking",
        "tap to ",
        "add comment",
        "say something",
        "welcome to tiktok live",
        "community guidelines",
        "انضم",
        "تابع المضيف",
        "شارك البث",
        "أرسل هدية",
        "أعجب بالبث"
    )

    private val QUESTION_STARTERS = listOf(
        "?", "؟", "how ", "why ", "what ", "when ", "where ", "who ", "which ", "can you",
        "do you", "are you", "is this", "كيف", "ليش", "لماذا", "متى", "وين", "أين",
        "شو ", "شنو", "هل ", "واش ", "علاش", "منو", "مين ", "كم ", "pourquoi", "comment",
        "quand", "como ", "por que", "qué "
    )

    fun setAccessibilityConnected(connected: Boolean) {
        _isAccessibilityConnected.value = connected
    }

    fun setOcrFallbackRunning(running: Boolean) {
        _isOcrFallbackRunning.value = running
    }

    fun setReaderActive(active: Boolean) {
        _isReaderActive.value = active
        if (!active) {
            synchronized(this) {
                _waitingQueue.value = emptyList()
            }
        }
    }

    fun shouldRunOcrCaptureNow(): Boolean {
        if (!_isReaderActive.value) return false
        val sinceLastAcc = System.currentTimeMillis() - lastAccessibilityCommentMs
        // Only run OCR fallback if Accessibility hasn't yielded any comment in the last 6 seconds
        return !_isAccessibilityConnected.value || sinceLastAcc > 6_000L
    }

    fun submitCandidate(rawUsername: String, rawText: String, fromAccessibility: Boolean) {
        if (!_isReaderActive.value) return

        val username = rawUsername.trim().removePrefix("@").trimEnd(':').trim()
        val text = rawText.trim()

        if (!isValidViewerComment(username, text)) return

        val normalizedKey = "${username.lowercase(Locale.ROOT)}|${text.lowercase(Locale.ROOT)}"
        val hash = sha256(normalizedKey)

        synchronized(this) {
            if (handledHashes.contains(hash)) return
            handledHashes.add(hash)
            if (handledHashes.size > MAX_HANDLED_HISTORY) {
                val it = handledHashes.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }

            if (fromAccessibility) {
                lastAccessibilityCommentMs = System.currentTimeMillis()
            }

            val item = LiveCommentItem(
                username = username,
                text = text,
                hash = hash,
                isQuestion = detectQuestion(text)
            )

            val mutable = _waitingQueue.value.toMutableList()
            mutable.add(item)

            // Keep maximum of 3 waiting comments: drop oldest non-question first, else oldest overall
            while (mutable.size > AiReplyConfig.MAX_WAITING_COMMENTS) {
                val oldestNonQuestionIdx = mutable.indexOfFirst { !it.isQuestion }
                if (oldestNonQuestionIdx >= 0 && oldestNonQuestionIdx < mutable.lastIndex) {
                    mutable.removeAt(oldestNonQuestionIdx)
                } else {
                    mutable.removeAt(0)
                }
            }

            _waitingQueue.value = mutable
        }
    }

    /**
     * Pops the highest-priority waiting comment: prefers the newest question, else the newest comment.
     */
    fun pollNextCommentForReply(): LiveCommentItem? {
        synchronized(this) {
            val current = _waitingQueue.value
            if (current.isEmpty()) return null
            val chosen = current.lastOrNull { it.isQuestion } ?: current.last()
            _waitingQueue.value = current.filterNot { it.hash == chosen.hash }
            return chosen
        }
    }

    fun isValidViewerComment(username: String, text: String): Boolean {
        if (text.length < 3 || text.length > 220) return false

        val own = ownUsernameLower.trim()
        if (own.isNotEmpty() && username.isNotEmpty() && username.lowercase(Locale.ROOT) == own) {
            return false
        }

        val lowerText = text.lowercase(Locale.ROOT)
        val combinedLower = "${username.lowercase(Locale.ROOT)} $lowerText".trim()

        if (SYSTEM_EVENT_PATTERNS.any { pattern -> combinedLower.contains(pattern) }) {
            return false
        }

        // Skip pure numbers, pure punctuation/emojis, or single-character repetition spam ("aaaaaa")
        val lettersOrArabicCount = text.count { Character.isLetter(it) }
        if (lettersOrArabicCount < 2) return false

        val distinctChars = text.filter { !it.isWhitespace() }.toSet().size
        if (text.length >= 6 && distinctChars <= 2) return false

        return true
    }

    private fun detectQuestion(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return QUESTION_STARTERS.any { q -> lower.contains(q) }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
