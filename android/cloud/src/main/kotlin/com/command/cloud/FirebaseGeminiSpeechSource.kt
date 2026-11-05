package com.command.cloud

import com.command.voice.GeminiAudio
import com.command.voice.GeminiSpeechSource
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.generationConfig
import java.util.concurrent.ConcurrentHashMap

/** Gemini text-to-speech model (Firebase AI Logic). Preview: verify the name if requests start failing. */
private const val GEMINI_TTS_MODEL = "gemini-3.1-flash-tts-preview"

/** Gemini TTS through Firebase AI Logic, one model per voice. Nothing uses it until the Studio voices arrive, behind their own consent (M14). */
class FirebaseGeminiSpeechSource : GeminiSpeechSource {
    private val models = ConcurrentHashMap<String, GenerativeModel>()

    @OptIn(PublicPreviewAPI::class) // Speech output is a preview API in firebase-ai
    private fun model(voiceName: String): GenerativeModel = models.getOrPut(voiceName) {
        Firebase.ai.generativeModel(
            modelName = GEMINI_TTS_MODEL,
            generationConfig = generationConfig {
                responseModalities = listOf(ResponseModality.AUDIO)
                speechConfig = SpeechConfig(voice = Voice(voiceName))
            }
        )
    }

    override suspend fun synthesize(voiceName: String, prompt: String): GeminiAudio? =
        model(voiceName).generateContent(prompt)
            .candidates.firstOrNull()?.content?.parts
            ?.filterIsInstance<InlineDataPart>()
            ?.firstOrNull { it.mimeType.startsWith("audio") }
            ?.let { GeminiAudio(it.inlineData, it.mimeType) }
}
