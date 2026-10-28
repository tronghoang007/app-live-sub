package vn.livesub.asr

/**
 * Giữ [capacity] mẫu âm thanh gần nhất, đánh số theo vị trí tuyệt đối từ đầu luồng (cùng hệ
 * với `SpeechSegment.start` của VAD) để lấy lại phần âm thanh nằm ngoài đoạn VAD đã chốt.
 * Thuần Kotlin để unit test được.
 */
class AudioHistory(private val capacity: Int) {
    private val buf = FloatArray(capacity)

    /** Tổng số mẫu đã nhận từ đầu luồng. */
    var total = 0L
        private set

    fun append(samples: FloatArray, count: Int = samples.size) {
        for (i in 0 until count) buf[((total + i) % capacity).toInt()] = samples[i]
        total += count
    }

    /** Âm thanh trong [from, to) theo vị trí tuyệt đối; null nếu đã bị ghi đè hoặc chưa nhận tới. */
    fun slice(from: Long, to: Long): FloatArray? {
        if (from < 0 || from > to || to > total || total - from > capacity) return null
        return FloatArray((to - from).toInt()) { buf[((from + it) % capacity).toInt()] }
    }
}
