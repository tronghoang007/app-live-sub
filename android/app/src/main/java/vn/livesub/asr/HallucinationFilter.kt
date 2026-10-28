package vn.livesub.asr

/**
 * Whisper hay "bịa" câu khi gặp nhạc nền, tiếng ồn hoặc im lặng (thường là
 * câu cảm ơn đã xem, mời đăng ký kênh...). Lọc các mẫu phổ biến để không
 * hiện phụ đề rác và không dùng chúng để đoán ngôn ngữ.
 */
object HallucinationFilter {
    private val knownPhrases = listOf(
        "ご視聴ありがとうございました",
        "チャンネル登録",
        "thank you for watching",
        "thanks for watching",
        "subtitles by",
        "amara.org",
        "시청해주셔서 감사합니다",
        "구독과 좋아요",
        "请不吝点赞",
        "訂閱",
        "字幕由",
        "字幕提供",
        "字幕by",
    )

    fun isLikelyHallucination(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        // Chỉ có ký hiệu/dấu câu (♪, ..., -), không có chữ.
        if (t.none { it.isLetterOrDigit() }) return true
        // Chú thích âm thanh kiểu phụ đề: (音楽), [拍手], （笑）, (music... — kể cả khi thiếu ngoặc đóng.
        if (t.length <= 15 && t.first() in "([（［【") return true
        val lower = t.lowercase()
        if (t.length <= 40 && knownPhrases.any { lower.contains(it) }) return true
        return hasRunawayRepetition(t)
    }

    /**
     * Chữ quá ít so với độ dài đoạn âm thanh: thường là nhạc/tiếng ồn bị nhận nhầm
     * (ví dụ ReazonSpeech ra "リ", "プ", hoặc "はい" cho cả đoạn nhạc 6 giây).
     */
    fun isImplausible(text: String, speechSec: Float): Boolean {
        val chars = text.filter { it.isLetterOrDigit() }
        // Một chữ kana/La-tinh lẻ loi gần như luôn là rác; một chữ Hán (何, 好...) thì có thể có nghĩa.
        if (chars.length == 1 && (chars[0] in '\u3040'..'\u30FF' || (chars[0].isLetter() && chars[0].code < 128))) return true
        return chars.length <= 2 && speechSec >= 2.5f
    }

    /** Whisper lặp vô hạn kiểu "ああああああ..." hoặc "no no no no no...". */
    private fun hasRunawayRepetition(t: String): Boolean {
        var run = 1
        for (i in 1 until t.length) {
            run = if (t[i] == t[i - 1] && t[i].isLetter()) run + 1 else 1
            if (run >= 12) return true
        }
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size >= 6) {
            val top = words.groupingBy { it.lowercase() }.eachCount().maxOf { it.value }
            if (top.toFloat() / words.size >= 0.7f) return true
        }
        return false
    }
}
