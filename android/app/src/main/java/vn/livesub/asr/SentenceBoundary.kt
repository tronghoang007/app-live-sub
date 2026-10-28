package vn.livesub.asr

/**
 * Xác định chỗ ngắt câu cho phụ đề.
 *
 * VAD chỉ nghe khoảng lặng, nên nghỉ lấy hơi giữa câu (>= 0,45 s) hay bị cắt ở độ dài
 * tối đa cũng thành một "câu" riêng, được dịch riêng và hiện thành dòng riêng: phụ đề đứt
 * giữa chừng, bản dịch cụt chủ/vị. Dấu kết câu trong chữ nhận dạng cho biết người nói đã
 * dứt câu thật chưa. Chưa dứt thì SpeechPipeline giữ lại, gộp với đoạn kế tiếp rồi nhận
 * dạng lại cả câu.
 * Thuần Kotlin để unit test được.
 */
object SentenceBoundary {
    /** Câu gộp dài hơn mức này không vừa khung phụ đề (tối đa 3 dòng) nên không gộp thêm. */
    const val MAX_SENTENCE_SEC = 8f

    private const val TERMINATORS = "。！？!?.…｡"

    // Ngoặc/trích dẫn có thể đứng sau dấu kết câu: 「行こう。」
    private const val CLOSERS = "」』）)”’\"'】］] "

    // Trợ từ tiếng Nhật không thể đứng cuối câu: "私は" | "学校の学生です", "さんも" | "会社員ですか".
    // Chỉ liệt kê loại chắc chắn; "の", "で", "から", "けど" hay đứng cuối câu nói nên không đưa vào.
    private val openJapaneseEnding = Regex("(は|が|を|に|へ|と|も|や)$")

    // Cũng kết thúc bằng trợ từ nhưng là câu chào trọn vẹn.
    private val closedJapaneseEnding = Regex("(こんにちは|こんばんは|どうも)$")

    fun isComplete(text: String): Boolean {
        val last = text.trimEnd { it in CLOSERS }.lastOrNull() ?: return false
        return last in TERMINATORS
    }

    /** Engine không có dấu câu: chữ dừng ở trợ từ thì câu chắc chắn chưa dứt. */
    private fun endsMidSentence(text: String): Boolean {
        val t = text.trimEnd()
        return openJapaneseEnding.containsMatchIn(t) && !closedJapaneseEnding.containsMatchIn(t)
    }

    /**
     * Đoạn VAD vừa chốt đã là hết câu chưa.
     * @param text chữ nhận dạng của cả câu tính tới giờ (đã gộp các đoạn trước).
     * @param hasPunctuation engine có tự thêm dấu câu không; không có thì khoảng lặng cho biết
     *   hết câu, trừ khi chữ dừng ở trợ từ.
     */
    fun hasEnded(text: String, hasPunctuation: Boolean): Boolean = when {
        hasPunctuation -> isComplete(text)
        else -> !endsMidSentence(text)
    }

    /** Thêm [extraSec] giây giọng nói vào câu đang giữ [sentenceSec] giây thì còn vừa một khung phụ đề không. */
    fun canMerge(sentenceSec: Float, extraSec: Float): Boolean = sentenceSec + extraSec <= MAX_SENTENCE_SEC
}
