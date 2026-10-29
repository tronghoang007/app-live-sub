package vn.livesub.asr

/**
 * Ghi lại từng cửa sổ VAD gần đây có tiếng nói hay không, theo vị trí tuyệt đối, để hỏi lại sau khi
 * VAD chính đã chốt đoạn: trong khoảng này VAD thứ hai (ngưỡng thường) nghe ra tiếng nói được bao nhiêu.
 * Thuần Kotlin để unit test được.
 */
class SpeechMarks(private val window: Int, private val capacity: Int) {
    private val marks = BooleanArray(capacity)
    private var count = 0L

    fun add(speech: Boolean) {
        marks[(count % capacity).toInt()] = speech
        count++
    }

    /** Tỉ lệ cửa sổ có tiếng nói trong khoảng mẫu [from, to); cửa sổ đã trôi khỏi bộ nhớ thì không tính. */
    fun fraction(from: Long, to: Long): Float {
        val first = maxOf(from / window, count - capacity, 0L)
        val last = minOf((to + window - 1) / window, count)
        if (last <= first) return 0f
        var speech = 0
        for (i in first until last) if (marks[(i % capacity).toInt()]) speech++
        return speech.toFloat() / (last - first)
    }
}
