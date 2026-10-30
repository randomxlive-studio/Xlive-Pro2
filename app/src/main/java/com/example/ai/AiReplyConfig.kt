package com.example.ai

/**
 * Single centralized configuration for free-tier LLM endpoints, model names,
 * daily safety limits, and the sarcastic streamer system prompt.
 */
object AiReplyConfig {

    // Google Gemini Free Tier (via AI Studio API key)
    const val GEMINI_ENDPOINT_TEMPLATE =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"
    const val GEMINI_DEFAULT_MODEL = "gemini-2.5-flash"
    val GEMINI_MODEL_CANDIDATES = listOf(
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-2.0-flash"
    )

    // Groq Free Tier (OpenAI-compatible chat completions endpoint)
    const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    const val GROQ_DEFAULT_MODEL = "llama-3.3-70b-versatile"

    // Conservative daily free-tier safety cap to pause silently before exhausting limits
    const val DAILY_FREE_REQUEST_LIMIT = 450

    // Maximum waiting comments in queue (oldest dropped when exceeded)
    const val MAX_WAITING_COMMENTS = 3

    // Default cooldown in seconds between spoken AI replies
    const val DEFAULT_COOLDOWN_SECONDS = 15

    fun buildPromptForComment(username: String, commentText: String): String {
        val formattedComment = if (username.isNotBlank()) {
            "@$username: $commentText"
        } else {
            commentText
        }
        return "You are a sarcastic, witty, funny live-stream character. " +
            "A viewer wrote this comment in my live: $formattedComment. " +
            "Answer in one or two short sentences with dry sarcastic humor, " +
            "mention the viewer's username if given, never insult anyone for real. " +
            "Reply in the same language and dialect as the comment."
    }
}
