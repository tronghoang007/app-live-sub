package vn.livesub.asr

/**
 * Silero VAD cắt đoạn giọng nói sát chỗ nó nhận ra tiếng nói, nên mất phụ âm/từ đầu câu
 * ("少々お待ちください" → "お待ちください", "わあ、これ何ですか" → "これ何ですか") và âm cuối nhẹ
 * ("ございます" → "ございま"). Cấu hình VAD không có tham số đệm, nên lấy thêm âm thanh ngay
 * trước và sau đoạn từ [AudioHistory] để engine nghe đủ.
 * Thuần Kotlin để unit test được.
 */
object SpeechPadding {
    const val PRE_SEC = 0.3f

    /** Ngắn hơn khoảng lặng chốt đoạn của VAD (0,45 s) để không lẫn sang câu kế tiếp. */
    const val POST_SEC = 0.3f

    /**
     * Vị trí bắt đầu lấy âm thanh đệm đầu cho giọng nói bắt đầu ở [start].
     * @param previousEnd chỗ kết thúc phần âm thanh đã lấy cho đoạn trước, để không lặp lại nó.
     */
    fun preRollStart(start: Long, previousEnd: Long, sampleRate: Int): Long =
        maxOf(start - (PRE_SEC * sampleRate).toLong(), previousEnd, 0L).coerceAtMost(start)

    /**
     * Khoảng [first, second) (vị trí tuyệt đối) cần lấy cho một đoạn VAD.
     * @param available số mẫu đã nhận tới giờ.
     * @param cutByLimit đoạn bị cắt cứng, lời còn tiếp ngay sau: không đệm cuối, nếu không sẽ
     *   lặp lại phần đầu của đoạn kế tiếp.
     */
    fun bounds(start: Long, length: Int, previousEnd: Long, available: Long, cutByLimit: Boolean, sampleRate: Int): Pair<Long, Long> {
        val end = start + length
        val to = if (cutByLimit) end else minOf(end + (POST_SEC * sampleRate).toLong(), available)
        return preRollStart(start, previousEnd, sampleRate) to to
    }
}
