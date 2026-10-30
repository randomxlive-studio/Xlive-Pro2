package com.example.ai

import android.util.Log
import com.example.model.LlmProviderChoice
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

sealed interface AiReplyResult {
    data class Success(val spokenReply: String) : AiReplyResult
    data object RateLimited : AiReplyResult
    data object SilentFailure : AiReplyResult
}

class SarcasticAiResponder {

    companion object {
        private const val TAG = "SarcasticAiResponder"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun generateSarcasticReply(
        provider: LlmProviderChoice,
        apiKey: String,
        username: String,
        commentText: String
    ): AiReplyResult = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) return@withContext AiReplyResult.SilentFailure

        val prompt = AiReplyConfig.buildPromptForComment(username, commentText)

        try {
            when (provider) {
                LlmProviderChoice.GEMINI_FREE -> {
                    var lastResult: AiReplyResult = AiReplyResult.SilentFailure
                    for (candidateModel in AiReplyConfig.GEMINI_MODEL_CANDIDATES) {
                        val attempt = callGeminiApi(
                            model = candidateModel,
                            apiKey = trimmedKey,
                            prompt = prompt
                        )
                        lastResult = attempt
                        if (attempt is AiReplyResult.Success || attempt is AiReplyResult.RateLimited) {
                            break
                        }
                    }
                    lastResult
                }

                LlmProviderChoice.GROQ_FREE -> {
                    callGroqApi(
                        model = AiReplyConfig.GROQ_DEFAULT_MODEL,
                        apiKey = trimmedKey,
                        prompt = prompt
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "Silent AI reply skip: ${t.message}")
            AiReplyResult.SilentFailure
        }
    }

    private fun callGeminiApi(model: String, apiKey: String, prompt: String): AiReplyResult {
        val url = String.format(AiReplyConfig.GEMINI_ENDPOINT_TEMPLATE, model) + "?key=$apiKey"
        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.9)
                    put("maxOutputTokens", 140)
                }
            )
        }

        val req = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (resp.code == 429) return AiReplyResult.RateLimited
            if (!resp.isSuccessful) return AiReplyResult.SilentFailure

            val root = JSONObject(resp.body?.string().orEmpty())
            val text = root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.trim()
                .orEmpty()

            return if (text.isNotBlank()) {
                AiReplyResult.Success(cleanForSpeech(text))
            } else {
                AiReplyResult.SilentFailure
            }
        }
    }

    private fun callGroqApi(model: String, apiKey: String, prompt: String): AiReplyResult {
        val payload = JSONObject().apply {
            put("model", model)
            put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", prompt)
                )
            )
            put("temperature", 0.9)
            put("max_tokens", 140)
        }

        val req = Request.Builder()
            .url(AiReplyConfig.GROQ_ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (resp.code == 429) return AiReplyResult.RateLimited
            if (!resp.isSuccessful) return AiReplyResult.SilentFailure

            val root = JSONObject(resp.body?.string().orEmpty())
            val text = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                .orEmpty()

            return if (text.isNotBlank()) {
                AiReplyResult.Success(cleanForSpeech(text))
            } else {
                AiReplyResult.SilentFailure
            }
        }
    }

    private fun cleanForSpeech(raw: String): String {
        return raw
            .replace("*", "")
            .replace("_", " ")
            .replace("#", "")
            .replace("`", "")
            .trim()
    }
}
