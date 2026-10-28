package vn.livesub.translate

import android.os.SystemClock
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.tasks.await

/**
 * Dịch on-device bằng Google ML Kit Translation: miễn phí, không cần API key,
 * mỗi ngôn ngữ ~30 MB tải một lần rồi chạy offline.
 *
 * Lần đầu gặp một cặp ngôn ngữ, model được tải nền; trong lúc đó trả về
 * [TranslationResult.Preparing] để overlay hiện câu gốc thay vì chặn pipeline.
 */
class MlKitTranslator(
    private val scope: CoroutineScope,
    private val wifiOnly: Boolean,
) : SubtitleTranslator {

    private class Entry(val client: Translator, val ready: Deferred<Result<Unit>>, val createdAt: Long)

    private val entries = HashMap<String, Entry>()

    override fun prepare(sourceLang: String, targetLang: String) {
        if (sourceLang != targetLang) entryFor(sourceLang, targetLang)
    }

    override suspend fun translate(text: String, sourceLang: String, targetLang: String, isFinal: Boolean): TranslationResult {
        if (sourceLang == targetLang) return TranslationResult.SameLanguage
        if (text.isBlank()) return TranslationResult.Success("")
        PhraseBook.lookup(text, sourceLang, targetLang)?.let { return TranslationResult.Success(it) }
        val entry = entryFor(sourceLang, targetLang) ?: return TranslationResult.Unsupported(sourceLang)

        if (!entry.ready.isCompleted) return TranslationResult.Preparing(sourceLang, targetLang)
        entry.ready.await().onFailure {
            // Cho thử tải lại sau một lúc (vd. vừa có mạng trở lại).
            if (SystemClock.elapsedRealtime() - entry.createdAt > RETRY_AFTER_MS) {
                synchronized(entries) { entries.remove(key(sourceLang, targetLang)) }?.client?.close()
            }
            return TranslationResult.Failed(
                "Chưa tải được model dịch ${sourceLang.uppercase()}→${targetLang.uppercase()}. Kiểm tra kết nối mạng.",
            )
        }
        return runCatching { entry.client.translate(text).await() }
            .fold(
                onSuccess = { TranslationResult.Success(it) },
                onFailure = { TranslationResult.Failed("Lỗi dịch: ${it.message}") },
            )
    }

    private fun entryFor(src: String, tgt: String): Entry? {
        val source = TranslateLanguage.fromLanguageTag(src) ?: return null
        val target = TranslateLanguage.fromLanguageTag(tgt) ?: return null
        return synchronized(entries) {
            entries.getOrPut(key(src, tgt)) {
                val client = Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build(),
                )
                val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
                val ready = scope.async { runCatching { client.downloadModelIfNeeded(conditions).await(); Unit } }
                Entry(client, ready, SystemClock.elapsedRealtime())
            }
        }
    }

    private fun key(src: String, tgt: String) = "$src>$tgt"

    override fun close() {
        synchronized(entries) {
            entries.values.forEach { it.client.close() }
            entries.clear()
        }
    }

    companion object {
        private const val RETRY_AFTER_MS = 30_000L
    }
}
