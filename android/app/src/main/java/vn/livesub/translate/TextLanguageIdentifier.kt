package vn.livesub.translate

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import kotlinx.coroutines.tasks.await
import java.io.Closeable

/**
 * Đoán ngôn ngữ từ chữ vừa nhận dạng bằng ML Kit Language ID (on-device).
 * Dùng để kiểm tra chéo kết quả Whisper: chữ ML Kit không nhận ra được
 * ("und") thường là nhạc/tiếng ồn bị Whisper phiên âm bừa.
 */
class TextLanguageIdentifier : Closeable {

    data class Guess(val lang: String, val confidence: Float)

    private val client = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(MIN_CONFIDENCE).build(),
    )

    /** @return ngôn ngữ có độ tin cậy cao nhất, null nếu không xác định. */
    suspend fun identify(text: String): Guess? {
        if (text.count { it.isLetter() } < 4) return null
        val candidates = runCatching { client.identifyPossibleLanguages(text).await() }.getOrNull()
        val best = candidates?.maxByOrNull { it.confidence } ?: return null
        if (best.languageTag == LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG) return null
        // ML Kit trả cả dạng "ja-Latn", "zh-Latn" (chữ La-tinh hoá); Whisper chỉ dùng mã gốc.
        return Guess(best.languageTag.substringBefore('-'), best.confidence)
    }

    override fun close() = client.close()

    companion object {
        const val MIN_CONFIDENCE = 0.5f
    }
}
