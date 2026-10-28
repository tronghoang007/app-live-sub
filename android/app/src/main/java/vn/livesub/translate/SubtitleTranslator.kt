package vn.livesub.translate

import java.io.Closeable

sealed interface TranslationResult {
    /** @param note thông báo kèm theo (vd. server mất kết nối, đang dùng engine dự phòng). */
    data class Success(val text: String, val note: String? = null) : TranslationResult

    /** Nguồn trùng đích: không dịch, chỉ hiện phụ đề gốc. */
    data object SameLanguage : TranslationResult

    data class Unsupported(val sourceLang: String) : TranslationResult

    /** Model dịch đang tải nền; tạm thời chỉ hiện câu gốc. */
    data class Preparing(val sourceLang: String, val targetLang: String) : TranslationResult

    data class Failed(val message: String) : TranslationResult
}

/** Interface cho engine dịch để có thể thêm engine khác sau này. */
interface SubtitleTranslator : Closeable {
    /** Chuẩn bị trước (vd. tải model) khi đã biết cặp ngôn ngữ. */
    fun prepare(sourceLang: String, targetLang: String)

    /** @param isFinal false = phụ đề tạm (partial), engine chậm có thể dịch qua loa hơn. */
    suspend fun translate(text: String, sourceLang: String, targetLang: String, isFinal: Boolean = true): TranslationResult
}
