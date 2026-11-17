package com.sehirnotlari.cloud

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.RequestOptions
import com.google.firebase.ai.type.content
import kotlin.time.Duration.Companion.minutes

/** One piece of a request: plain text or inline bytes (PDF, audio, image). */
sealed interface AiPart {
    class Text(val value: String) : AiPart
    class Data(val bytes: ByteArray, val mimeType: String) : AiPart
}

/** What the model answered. [text] is null or blank when it produced nothing; the two reasons then say why. */
class AiAnswer(val text: String?, val blockReason: String?, val finishReason: String?)

/**
 * Gemini through Firebase AI Logic (Gemini Developer API backend): no API key lives in the app.
 * Calls are authorised by the app's Firebase project (google-services.json, optionally App Check).
 * Firebase stays inside this package; the rest of the app sees only [AiPart] and [AiAnswer].
 */
class GeminiAi(context: Context) {
    /** False for builds without the local Firebase project file: the AI features then stay off. */
    val available: Boolean = FirebaseApp.getApps(context).isNotEmpty()

    private val model: GenerativeModel by lazy {
        FirebaseAI.getInstance(GenerativeBackend.googleAI())
            .generativeModel(MODEL_NAME, requestOptions = RequestOptions(timeout = 5.minutes))
    }

    suspend fun generate(vararg pieces: AiPart): AiAnswer {
        val input = content {
            pieces.forEach { part ->
                when (part) {
                    is AiPart.Text -> text(part.value)
                    is AiPart.Data -> inlineData(part.bytes, part.mimeType)
                }
            }
        }
        val response = model.generateContent(input)
        return AiAnswer(
            text = response.text,
            blockReason = response.promptFeedback?.blockReason?.toString(),
            finishReason = response.candidates.firstOrNull()?.finishReason?.toString()
        )
    }

    private companion object {
        const val MODEL_NAME = "gemini-2.5-flash"
    }
}
