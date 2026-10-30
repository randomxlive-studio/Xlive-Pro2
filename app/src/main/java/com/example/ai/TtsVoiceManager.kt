package com.example.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.model.TtsVoiceOption
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

class TtsVoiceManager(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var rawVoicesById: Map<String, Voice> = emptyMap()

    private val _availableVoices = MutableStateFlow<List<TtsVoiceOption>>(emptyList())
    val availableVoices: StateFlow<List<TtsVoiceOption>> = _availableVoices.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val activeUtterances = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var currentFocusRequest: AudioFocusRequest? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                configureTtsListener()
                refreshVoices()
            }
        }
    }

    private fun configureTtsListener() {
        val engine = tts ?: return
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
                requestTransientDuckingFocus()
            }

            override fun onDone(utteranceId: String?) {
                finishUtterance(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                finishUtterance(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finishUtterance(utteranceId)
            }
        })
    }

    private fun finishUtterance(utteranceId: String?) {
        _isSpeaking.value = false
        abandonDuckingFocus()
        if (utteranceId != null) {
            synchronized(activeUtterances) {
                activeUtterances.remove(utteranceId)?.complete(Unit)
            }
        }
    }

    fun refreshVoices() {
        val engine = tts ?: return
        if (!isReady) return
        runCatching {
            val voices = engine.voices?.filterNotNull().orEmpty()
                .filter { !it.isNetworkConnectionRequired || it.features?.contains("networkTimeoutMs") == true || true }
                .sortedWith(compareBy({ it.locale.displayLanguage }, { it.name }))

            rawVoicesById = voices.associateBy { it.name }
            _availableVoices.value = voices.map { v ->
                val langLabel = v.locale.displayName.ifBlank { v.locale.toLanguageTag() }
                val shortName = v.name.substringAfterLast('#').take(24)
                TtsVoiceOption(
                    id = v.name,
                    displayName = "$langLabel ($shortName)",
                    localeTag = v.locale.toLanguageTag()
                )
            }
        }
    }

    suspend fun speakAndAwait(
        text: String,
        selectedVoiceId: String,
        speed: Float,
        pitch: Float
    ) {
        val engine = tts ?: return
        if (!isReady || text.isBlank()) return

        engine.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
        engine.setPitch(pitch.coerceIn(0.5f, 2.0f))

        val chosenVoice = if (selectedVoiceId.isNotBlank()) rawVoicesById[selectedVoiceId] else null
        if (chosenVoice != null) {
            runCatching { engine.voice = chosenVoice }
        } else {
            val detectedLocale = guessLocaleForText(text)
            runCatching { engine.language = detectedLocale }
        }

        val utteranceId = "rxl_${UUID.randomUUID()}"
        val deferred = CompletableDeferred<Unit>()
        synchronized(activeUtterances) {
            activeUtterances[utteranceId] = deferred
        }

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        val res = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (res == TextToSpeech.ERROR) {
            finishUtterance(utteranceId)
            return
        }

        withTimeoutOrNull(35_000L) {
            deferred.await()
        }
        if (_isSpeaking.value) {
            finishUtterance(utteranceId)
        }
    }

    fun stopSpeaking() {
        runCatching { tts?.stop() }
        _isSpeaking.value = false
        abandonDuckingFocus()
        synchronized(activeUtterances) {
            activeUtterances.values.forEach { it.complete(Unit) }
            activeUtterances.clear()
        }
    }

    fun shutdown() {
        stopSpeaking()
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun guessLocaleForText(text: String): Locale {
        val hasArabic = text.any { ch -> ch.code in 0x0600..0x06FF }
        if (hasArabic) return Locale.forLanguageTag("ar")
        val hasCyrillic = text.any { ch -> ch.code in 0x0400..0x04FF }
        if (hasCyrillic) return Locale.forLanguageTag("ru")
        val hasCjk = text.any { ch -> ch.code in 0x4E00..0x9FFF }
        if (hasCjk) return Locale.SIMPLIFIED_CHINESE
        return Locale.getDefault()
    }

    private fun requestTransientDuckingFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                currentFocusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        }
    }

    private fun abandonDuckingFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                currentFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                currentFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        }
    }
}
