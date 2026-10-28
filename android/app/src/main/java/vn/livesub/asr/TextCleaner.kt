package vn.livesub.asr

/**
 * Làm sạch câu nhận dạng trước khi hiện và dịch. Máy dịch offline dịch từng câu,
 * nên bỏ từ đệm và có dấu kết câu giúp nó hiểu đúng ranh giới/ngữ pháp hơn.
 * Thuần Kotlin để unit test được.
 */
object TextCleaner {
    // Từ đệm đầu câu chỉ khi đứng riêng (có dấu phẩy/kéo dài), để không xoá "あの人" (người đó).
    private val leadingFillers = Regex("^(えっと|ええと|えーと|えー|あのー|あの、|うーん|んー|まあ、)[、,\\s]*")

    // Một chữ La-tinh lạc trước câu tiếng Nhật, ví dụ "A ぜひ" (Whisper hay sinh ra).
    private val strayLatin = Regex("^[A-Za-z][\\s、,]+(?=[\\u3040-\\u30FF\\u4E00-\\u9FFF])")

    private const val SENTENCE_END = "。！？!?…」』)）"

    /**
     * Câu gần như toàn katakana: dấu hiệu model chỉ biết tiếng Nhật đang phiên âm
     * một ngôn ngữ khác (ví dụ tiếng Anh "Excuse me" → エクスキューズミー).
     */
    fun isMostlyKatakana(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 4) return false
        val katakana = letters.count { it in '\u30A0'..'\u30FF' || it.isLetter() && it.code < 128 }
        return katakana.toFloat() / letters.length >= 0.8f
    }

    fun clean(text: String, lang: String, isFinal: Boolean): String {
        var t = text.trim()
        if (lang != "ja") return t
        // Lặp vài lần cho trường hợp nhiều từ đệm nối nhau ("えっと、あのー、...").
        repeat(3) { t = leadingFillers.replace(t, "").trim() }
        t = strayLatin.replace(t, "").trim()
        if (isFinal && t.isNotEmpty() && t.last() !in SENTENCE_END) t += "。"
        return t
    }
}
