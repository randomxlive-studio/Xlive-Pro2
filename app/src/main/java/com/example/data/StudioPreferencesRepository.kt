package com.example.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ai.AiReplyConfig
import com.example.model.DisplayScaleMode
import com.example.model.GameplayAspectPreset
import com.example.model.LlmProviderChoice
import com.example.model.PlaybackEngineMode
import com.example.model.StudioUiState
import com.example.model.UserAgentMode
import com.example.model.VideoQualityPreference
import com.example.model.WindowRectState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.studioDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "randomxlive_studio_prefs"
)

class StudioPreferencesRepository(private val context: Context) {

    private object Keys {
        val ACCOUNT_NAME = stringPreferencesKey("account_name")
        val AVATAR_PATH = stringPreferencesKey("avatar_path")
        val ACTIVE_URL = stringPreferencesKey("active_url")
        val GAMEPLAY_PATH = stringPreferencesKey("gameplay_path")
        val CHARACTER_PATH = stringPreferencesKey("character_path")
        val LEGACY_CHARACTER_IS_IMAGE = booleanPreferencesKey("character_is_image")
        val ENGINE_MODE = stringPreferencesKey("engine_mode")
        val QUALITY_PREF = stringPreferencesKey("quality_pref")
        val ADBLOCK_ENABLED = booleanPreferencesKey("adblock_enabled")
        val UA_MODE = stringPreferencesKey("ua_mode")

        val G_X = floatPreferencesKey("g_x")
        val G_Y = floatPreferencesKey("g_y")
        val G_W = floatPreferencesKey("g_w")
        val G_H = floatPreferencesKey("g_h")
        val ASPECT_PRESET = stringPreferencesKey("aspect_preset")
        val SCALE_MODE = stringPreferencesKey("scale_mode")
        val G_SOUND = booleanPreferencesKey("g_sound")
        val AUTO_HIDE_ADS = booleanPreferencesKey("auto_hide_ads")
        val VOICE_REDUCE = booleanPreferencesKey("voice_reduce")

        val C_X = floatPreferencesKey("c_x")
        val C_Y = floatPreferencesKey("c_y")
        val C_W = floatPreferencesKey("c_w")
        val C_H = floatPreferencesKey("c_h")

        val CL_X = floatPreferencesKey("cl_x")
        val CL_Y = floatPreferencesKey("cl_y")
        val CL_W = floatPreferencesKey("cl_w")
        val CL_H = floatPreferencesKey("cl_h")
        val C_SOUND = booleanPreferencesKey("c_sound")
        val FRONT_CAMERA = booleanPreferencesKey("front_camera")
        val PULSE_ON_SPEAK = booleanPreferencesKey("pulse_on_speak")

        // AI & TTS local settings
        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val LLM_PROVIDER = stringPreferencesKey("llm_provider")
        val LLM_API_KEY = stringPreferencesKey("llm_api_key")
        val TTS_VOICE_ID = stringPreferencesKey("tts_voice_id")
        val TTS_SPEED = floatPreferencesKey("tts_speed")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")
        val AI_COOLDOWN_SEC = intPreferencesKey("ai_cooldown_sec")
        val DAILY_REQ_COUNT = intPreferencesKey("daily_req_count")
        val DAILY_REQ_DATE = stringPreferencesKey("daily_req_date")
    }

    private fun todayDateKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    suspend fun loadInitialState(): StudioUiState = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = context.studioDataStore.data.first()
            val defG = WindowRectState.DefaultGameplay
            val defCP = WindowRectState.DefaultCharacterPortrait
            val defCL = WindowRectState.DefaultCharacterLandscape

            val avatarPath = prefs[Keys.AVATAR_PATH]?.takeIf { isValidExistingFile(it) }
            val gameplayPath = prefs[Keys.GAMEPLAY_PATH]?.takeIf { isValidExistingFile(it) }

            val rawCharacterPath = prefs[Keys.CHARACTER_PATH]
            val validCharacterVideoPath = rawCharacterPath?.takeIf { isValidCharacterVideoFile(it) }

            if (!rawCharacterPath.isNullOrBlank() && validCharacterVideoPath == null) {
                // Saved character file is missing, empty, or a legacy image — clean up safely
                runCatching {
                    context.studioDataStore.edit { editPrefs ->
                        editPrefs.remove(Keys.CHARACTER_PATH)
                        editPrefs.remove(Keys.LEGACY_CHARACTER_IS_IMAGE)
                    }
                }
            }
            cleanupOldCharacterVideos(activePathToKeep = validCharacterVideoPath)

            val savedUrl = prefs[Keys.ACTIVE_URL].orEmpty()
            val savedDate = prefs[Keys.DAILY_REQ_DATE].orEmpty()
            val today = todayDateKey()
            val dailyCount = if (savedDate == today) (prefs[Keys.DAILY_REQ_COUNT] ?: 0) else 0

            StudioUiState(
                accountName = prefs[Keys.ACCOUNT_NAME] ?: "RandomXLive",
                avatarLocalPath = avatarPath,
                urlInput = savedUrl,
                activeUrl = savedUrl,
                gameplayLocalPath = gameplayPath,
                characterLocalPath = validCharacterVideoPath,
                characterVideoSwitchNonce = if (validCharacterVideoPath != null) 1L else 0L,
                isCameraActive = false,
                engineMode = runCatching {
                    PlaybackEngineMode.valueOf(prefs[Keys.ENGINE_MODE] ?: PlaybackEngineMode.AUTO.name)
                }.getOrDefault(PlaybackEngineMode.AUTO),
                qualityPreference = runCatching {
                    VideoQualityPreference.valueOf(
                        prefs[Keys.QUALITY_PREF] ?: VideoQualityPreference.AUTO_720P_MIN.name
                    )
                }.getOrDefault(VideoQualityPreference.AUTO_720P_MIN),
                isAdBlockerEnabled = prefs[Keys.ADBLOCK_ENABLED] ?: true,
                userAgentMode = runCatching {
                    UserAgentMode.valueOf(prefs[Keys.UA_MODE] ?: UserAgentMode.MOBILE.name)
                }.getOrDefault(UserAgentMode.MOBILE),
                gameplayRect = WindowRectState(
                    x = prefs[Keys.G_X] ?: defG.x,
                    y = prefs[Keys.G_Y] ?: defG.y,
                    w = prefs[Keys.G_W] ?: defG.w,
                    h = prefs[Keys.G_H] ?: defG.h
                ).clamped(),
                aspectPreset = runCatching {
                    GameplayAspectPreset.valueOf(
                        prefs[Keys.ASPECT_PRESET] ?: GameplayAspectPreset.LANDSCAPE_16_9.name
                    )
                }.getOrDefault(GameplayAspectPreset.LANDSCAPE_16_9),
                displayScaleMode = runCatching {
                    DisplayScaleMode.valueOf(prefs[Keys.SCALE_MODE] ?: DisplayScaleMode.FIT.name)
                }.getOrDefault(DisplayScaleMode.FIT),
                isGameplaySoundOn = prefs[Keys.G_SOUND] ?: true,
                isAutoHideAdsOn = prefs[Keys.AUTO_HIDE_ADS] ?: true,
                isVoiceReduceOn = prefs[Keys.VOICE_REDUCE] ?: false,
                characterPortraitRect = WindowRectState(
                    x = prefs[Keys.C_X] ?: defCP.x,
                    y = prefs[Keys.C_Y] ?: defCP.y,
                    w = prefs[Keys.C_W] ?: defCP.w,
                    h = prefs[Keys.C_H] ?: defCP.h
                ).clamped(),
                characterLandscapeRect = WindowRectState(
                    x = prefs[Keys.CL_X] ?: defCL.x,
                    y = prefs[Keys.CL_Y] ?: defCL.y,
                    w = prefs[Keys.CL_W] ?: defCL.w,
                    h = prefs[Keys.CL_H] ?: defCL.h
                ).clamped(),
                isCharacterSoundOn = prefs[Keys.C_SOUND] ?: false,
                isFrontCamera = prefs[Keys.FRONT_CAMERA] ?: true,
                isPulseOnAiSpeakEnabled = prefs[Keys.PULSE_ON_SPEAK] ?: true,
                isAiRepliesEnabled = prefs[Keys.AI_ENABLED] ?: true,
                isVoiceTtsEnabled = prefs[Keys.TTS_ENABLED] ?: true,
                llmProvider = runCatching {
                    LlmProviderChoice.valueOf(
                        prefs[Keys.LLM_PROVIDER] ?: LlmProviderChoice.GEMINI_FREE.name
                    )
                }.getOrDefault(LlmProviderChoice.GEMINI_FREE),
                llmApiKey = prefs[Keys.LLM_API_KEY].orEmpty(),
                selectedTtsVoiceId = prefs[Keys.TTS_VOICE_ID].orEmpty(),
                ttsSpeed = prefs[Keys.TTS_SPEED] ?: 1.0f,
                ttsPitch = prefs[Keys.TTS_PITCH] ?: 1.0f,
                aiCooldownSeconds = prefs[Keys.AI_COOLDOWN_SEC]
                    ?: AiReplyConfig.DEFAULT_COOLDOWN_SECONDS,
                dailyAiRequestCount = dailyCount
            )
        }.getOrElse {
            StudioUiState()
        }
    }

    suspend fun saveState(state: StudioUiState) = withContext(Dispatchers.IO) {
        runCatching {
            val today = todayDateKey()
            context.studioDataStore.edit { prefs ->
                prefs[Keys.ACCOUNT_NAME] = state.accountName
                if (state.avatarLocalPath != null) {
                    prefs[Keys.AVATAR_PATH] = state.avatarLocalPath
                } else {
                    prefs.remove(Keys.AVATAR_PATH)
                }
                prefs[Keys.ACTIVE_URL] = state.activeUrl
                if (state.gameplayLocalPath != null) {
                    prefs[Keys.GAMEPLAY_PATH] = state.gameplayLocalPath
                } else {
                    prefs.remove(Keys.GAMEPLAY_PATH)
                }
                if (state.characterLocalPath != null) {
                    prefs[Keys.CHARACTER_PATH] = state.characterLocalPath
                } else {
                    prefs.remove(Keys.CHARACTER_PATH)
                }
                prefs.remove(Keys.LEGACY_CHARACTER_IS_IMAGE)

                prefs[Keys.ENGINE_MODE] = state.engineMode.name
                prefs[Keys.QUALITY_PREF] = state.qualityPreference.name
                prefs[Keys.ADBLOCK_ENABLED] = state.isAdBlockerEnabled
                prefs[Keys.UA_MODE] = state.userAgentMode.name

                prefs[Keys.G_X] = state.gameplayRect.x
                prefs[Keys.G_Y] = state.gameplayRect.y
                prefs[Keys.G_W] = state.gameplayRect.w
                prefs[Keys.G_H] = state.gameplayRect.h
                prefs[Keys.ASPECT_PRESET] = state.aspectPreset.name
                prefs[Keys.SCALE_MODE] = state.displayScaleMode.name
                prefs[Keys.G_SOUND] = state.isGameplaySoundOn
                prefs[Keys.AUTO_HIDE_ADS] = state.isAutoHideAdsOn
                prefs[Keys.VOICE_REDUCE] = state.isVoiceReduceOn

                prefs[Keys.C_X] = state.characterPortraitRect.x
                prefs[Keys.C_Y] = state.characterPortraitRect.y
                prefs[Keys.C_W] = state.characterPortraitRect.w
                prefs[Keys.C_H] = state.characterPortraitRect.h

                prefs[Keys.CL_X] = state.characterLandscapeRect.x
                prefs[Keys.CL_Y] = state.characterLandscapeRect.y
                prefs[Keys.CL_W] = state.characterLandscapeRect.w
                prefs[Keys.CL_H] = state.characterLandscapeRect.h

                prefs[Keys.C_SOUND] = state.isCharacterSoundOn
                prefs[Keys.FRONT_CAMERA] = state.isFrontCamera
                prefs[Keys.PULSE_ON_SPEAK] = state.isPulseOnAiSpeakEnabled

                prefs[Keys.AI_ENABLED] = state.isAiRepliesEnabled
                prefs[Keys.TTS_ENABLED] = state.isVoiceTtsEnabled
                prefs[Keys.LLM_PROVIDER] = state.llmProvider.name
                prefs[Keys.LLM_API_KEY] = state.llmApiKey
                prefs[Keys.TTS_VOICE_ID] = state.selectedTtsVoiceId
                prefs[Keys.TTS_SPEED] = state.ttsSpeed
                prefs[Keys.TTS_PITCH] = state.ttsPitch
                prefs[Keys.AI_COOLDOWN_SEC] = state.aiCooldownSeconds
                prefs[Keys.DAILY_REQ_COUNT] = state.dailyAiRequestCount
                prefs[Keys.DAILY_REQ_DATE] = today
            }
        }
    }

    /**
     * Copies a newly picked character video immediately into internal storage (`filesDir`)
     * on `Dispatchers.IO`. Never keeps or returns the temporary picker URI, and never deletes
     * the currently playing video file before the player has stopped & switched.
     */
    suspend fun copyCharacterVideoToInternalStorage(uri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                tryTakePersistableReadPermission(uri)

                val mime = runCatching { context.contentResolver.getType(uri) }
                    .getOrNull()
                    .orEmpty()
                    .lowercase(Locale.US)

                if (mime.startsWith("image/")) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Only video files are supported in the character circle")
                    )
                }

                val ext = when {
                    mime.contains("webm") -> "webm"
                    mime.contains("matroska") || mime.contains("mkv") -> "mkv"
                    mime.contains("quicktime") || mime.contains("mov") -> "mov"
                    mime.contains("3gpp") -> "3gp"
                    else -> "mp4"
                }

                val charVideoDir = File(context.filesDir, "character_videos").apply { mkdirs() }
                val uniqueToken = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
                val tempFile = File(charVideoDir, "character_video_$uniqueToken.tmp")
                val destFile = File(charVideoDir, "character_video_$uniqueToken.$ext")

                val bytesCopied = context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        val copied = input.copyTo(output, bufferSize = 64 * 1024)
                        output.flush()
                        runCatching { output.fd.sync() }
                        copied
                    }
                } ?: run {
                    runCatching { tempFile.delete() }
                    return@withContext Result.failure(
                        IllegalStateException("Unable to open video stream from gallery URI")
                    )
                }

                if (bytesCopied <= 0L || !tempFile.exists() || tempFile.length() <= 0L) {
                    runCatching { tempFile.delete() }
                    return@withContext Result.failure(
                        IllegalStateException("Selected video file is empty")
                    )
                }

                val moved = tempFile.renameTo(destFile)
                val finalFile = if (moved && destFile.exists() && destFile.length() > 0L) {
                    destFile
                } else {
                    tempFile.copyTo(destFile, overwrite = true)
                    runCatching { tempFile.delete() }
                    destFile
                }

                if (!finalFile.exists() || finalFile.length() <= 0L) {
                    return@withContext Result.failure(
                        IllegalStateException("Failed to save video copy into internal storage")
                    )
                }

                Result.success(finalFile.absolutePath)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    suspend fun cleanupOldCharacterVideos(activePathToKeep: String?) =
        withContext(Dispatchers.IO) {
            runCatching {
                val keepCanonical = activePathToKeep?.let {
                    runCatching { File(it).canonicalPath }.getOrDefault(File(it).absolutePath)
                }

                val charVideoDir = File(context.filesDir, "character_videos")
                charVideoDir.listFiles()?.forEach { file ->
                    val candidatePath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                    if (candidatePath != keepCanonical) {
                        runCatching { file.delete() }
                    }
                }

                // Also clean up any legacy character_* files in studio_media except activePathToKeep
                val legacyMediaDir = File(context.filesDir, "studio_media")
                legacyMediaDir.listFiles()
                    ?.filter { it.name.startsWith("character_") }
                    ?.forEach { file ->
                        val candidatePath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                        if (candidatePath != keepCanonical) {
                            runCatching { file.delete() }
                        }
                    }
            }
        }

    suspend fun copyPickedMediaToInternalStorage(
        uri: Uri,
        slotPrefix: String
    ): Pair<String, Boolean>? = withContext(Dispatchers.IO) {
        runCatching {
            tryTakePersistableReadPermission(uri)

            val mediaDir = File(context.filesDir, "studio_media").apply { mkdirs() }
            val mime = runCatching { context.contentResolver.getType(uri) }
                .getOrNull()
                .orEmpty()
                .lowercase(Locale.US)
            val isImage = mime.startsWith("image/")
            val ext = when {
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                mime.contains("gif") -> "gif"
                isImage -> "jpg"
                mime.contains("webm") -> "webm"
                mime.contains("matroska") || mime.contains("mkv") -> "mkv"
                else -> "mp4"
            }

            val uniqueToken = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val tempFile = File(mediaDir, "${slotPrefix}_$uniqueToken.tmp")
            val destFile = File(mediaDir, "${slotPrefix}_$uniqueToken.$ext")

            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                    output.flush()
                    runCatching { output.fd.sync() }
                }
            } ?: return@withContext null

            if (!tempFile.renameTo(destFile)) {
                tempFile.copyTo(destFile, overwrite = true)
                runCatching { tempFile.delete() }
            }

            if (destFile.exists() && destFile.length() > 0L) {
                destFile.absolutePath to isImage
            } else {
                runCatching { destFile.delete() }
                null
            }
        }.getOrNull()
    }

    suspend fun cleanupOldSlotMedia(slotPrefix: String, activePathToKeep: String?) =
        withContext(Dispatchers.IO) {
            runCatching {
                val keepCanonical = activePathToKeep?.let {
                    runCatching { File(it).canonicalPath }.getOrDefault(File(it).absolutePath)
                }
                val mediaDir = File(context.filesDir, "studio_media")
                mediaDir.listFiles()
                    ?.filter { it.name.startsWith("${slotPrefix}_") }
                    ?.forEach { file ->
                        val candidate = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                        if (candidate != keepCanonical) {
                            runCatching { file.delete() }
                        }
                    }
            }
        }

    fun tryTakePersistableReadPermission(uri: Uri) {
        try {
            if (uri.scheme == "content") {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        } catch (_: Throwable) {
            // Photo Picker or single-grant providers may not offer persistable URI grants;
            // safe because we immediately copy the file into filesDir.
        }
    }

    fun isValidCharacterVideoFile(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        if (path.startsWith("content://", ignoreCase = true) ||
            path.startsWith("http://", ignoreCase = true) ||
            path.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        return runCatching {
            val file = File(path)
            file.exists() && file.isFile && file.length() > 0L && !isImageExtension(file.name)
        }.getOrDefault(false)
    }

    private fun isValidExistingFile(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return runCatching {
            val file = File(path)
            file.exists() && file.isFile && file.length() > 0L
        }.getOrDefault(false)
    }

    private fun isImageExtension(nameOrPath: String?): Boolean {
        val lower = nameOrPath?.lowercase(Locale.US).orEmpty()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") ||
            lower.endsWith(".bmp") || lower.endsWith(".heic") || lower.endsWith(".heif")
    }
}
