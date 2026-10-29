package vn.livesub.asr

/**
 * Whisper hay "bịa" câu khi gặp nhạc nền, tiếng ồn hoặc im lặng (thường là
 * câu cảm ơn đã xem, mời đăng ký kênh...). Lọc các mẫu phổ biến để không
 * hiện phụ đề rác và không dùng chúng để đoán ngôn ngữ.
 *
 * Rác hay nằm lẫn trong lời thoại thật: cảnh có tiếng hét, rên thì Whisper ra
 * "ああああああああ、やめて！" hoặc "(悲鳴) やめて！". Chỉ bỏ phần rác ([salvage]),
 * không bỏ cả câu.
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

    // Chú thích âm thanh kiểu phụ đề ở bất kỳ đâu trong câu: (音楽), [拍手], （笑）, (悲鳴)... và ở cuối câu dù thiếu ngoặc đóng.
    private val soundNote = Regex("[(（\\[［【][^()（）\\[\\]［］【】]{0,20}[)）\\]］】]|[(（\\[［【][^()（）\\[\\]［］【】]{0,15}$")
    private val musicSymbols = Regex("[♪♫♬]")

    /**
     * Một cụm (tối đa 10 ký tự, không có chữ số) lặp liền >= 4 lần: Whisper lặp vô hạn kiểu
     * "ああああ...", "no no no no...", hoặc tiếng hét/rên kéo dài. Chữ số không tính để giữ "1000000".
     */
    private val loop = Regex("(\\D{1,10}?)\\1{3,}")
    private val spaces = Regex("\\s+")

    fun isLikelyHallucination(text: String): Boolean = salvage(text).isEmpty()

    /**
     * Bỏ chú thích âm thanh, rút gọn đoạn lặp còn 3 lần ("いやああああああ" → "いやあああ") và giữ lời thoại xen giữa.
     * Trả về "" khi bỏ hẳn các đoạn lặp đi thì không còn lời nào, hoặc câu là câu bịa quen thuộc.
     */
    fun salvage(text: String): String {
        // Thêm khoảng trắng cuối để cụm lặp có dấu cách ("no ") khớp được cả lần lặp cuối.
        val t = text.replace(soundNote, " ").replace(musicSymbols, " ") + " "
        if (isNoise(t.replace(loop, " ").trim())) return ""
        return t.replace(loop, "$1$1$1").replace(spaces, " ").trim()
    }

    private fun isNoise(t: String): Boolean {
        if (t.isEmpty()) return true
        // Chỉ có ký hiệu/dấu câu (..., -), không có chữ.
        if (t.none { it.isLetterOrDigit() }) return true
        // Chú thích thiếu ngoặc đóng ở đầu: (音楽, (music...
        if (t.length <= 15 && t.first() in "([（［【") return true
        val lower = t.lowercase()
        return t.length <= 40 && knownPhrases.any { lower.contains(it) }
    }

    /**
     * Chữ quá ít so với độ dài đoạn âm thanh: thường là nhạc/tiếng ồn bị nhận nhầm
     * (ví dụ ReazonSpeech ra "リ", "プ", hoặc "はい" cho cả đoạn nhạc 6 giây).
     * @param strict false khi biết không có nhạc: chỉ còn lọc đoạn dài toàn âm giống tiếng rên.
     */
    fun isImplausible(text: String, speechSec: Float, strict: Boolean = true): Boolean {
        val chars = text.filter { it.isLetterOrDigit() }
        // Một chữ kana/La-tinh lẻ loi gần như luôn là rác; một chữ Hán (何, 好...) thì có thể có nghĩa.
        // Có dấu hỏi/than ("え？", "あ！") thì là câu phản ứng thật trong hội thoại: Whisper chỉ thêm dấu khi nghe ra ngữ điệu.
        if (chars.length == 1 && (chars[0] in '぀'..'ヿ' || (chars[0].isLetter() && chars[0].code < 128)) &&
            text.none { it in "?？!！" }
        ) return true
        if (speechSec < 2.5f) return false
        // Đoạn dài chỉ ra vài âm giống tiếng rên ("うん", "ん", "あ" cho cả đoạn 6 s): log trên máy thật thấy tiếng
        // "うーん/んー" thành "うん" rồi hiện "Ừ" liên tục. Luôn lọc, kể cả khi [strict] tắt.
        if (chars.length <= 3 && chars.all { it in MOAN_LIKE }) return true
        // [strict] tắt (không có nhạc): lời ngắn thật trong đoạn dài toàn tiếng rên ("よし") vẫn giữ.
        return strict && chars.length <= 2
    }

    // Không có "い" để giữ "いい" (sướng), "はい".
    private const val MOAN_LIKE = "あぁアァうぅウゥえぇおぉオォんンっッはハふフー"
}
