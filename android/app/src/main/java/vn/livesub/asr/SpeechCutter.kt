package vn.livesub.asr

/**
 * Silero VAD không luôn cắt đoạn nói liền ở độ dài tối đa (đo trên máy: đoạn 7–15 s dù cấu hình 6 s),
 * khiến phụ đề đứng hình cả chục giây. Pipeline tự cắt ở chỗ lặng nhất gần cuối đoạn rồi nhận dạng
 * phần đã nói; phần VAD chốt sau đó bỏ đi đoạn đầu đã cắt ([skip]).
 * Thuần Kotlin để unit test được.
 */
object SpeechCutter {
    private const val WINDOW_MS = 20

    /**
     * Chỉ số mẫu để cắt: giữa cửa sổ 20 ms có năng lượng thấp nhất trong [searchSec] giây cuối của
     * [samples] (bỏ [guardSec] sát cuối), gần cuối nhất nếu nhiều chỗ ngang nhau. Cắt ở chỗ lặng thì ít
     * chẻ đôi một từ hơn cắt đúng mốc thời gian.
     */
    fun cutPoint(samples: FloatArray, length: Int, sampleRate: Int, searchSec: Float = 1.5f, guardSec: Float = 0.2f): Int {
        val window = sampleRate * WINDOW_MS / 1000
        val first = maxOf(0, length - (searchSec * sampleRate).toInt())
        val last = length - (guardSec * sampleRate).toInt() - window
        if (last < first) return length
        var best = first
        var bestEnergy = Double.MAX_VALUE
        var i = first
        while (i <= last) {
            var energy = 0.0
            for (j in i until i + window) energy += samples[j].toDouble() * samples[j]
            if (energy <= bestEnergy) {
                bestEnergy = energy
                best = i
            }
            i += window / 2
        }
        return best + window / 2
    }

    /** Số mẫu đầu của đoạn [start, start + size) nằm trước [consumedEnd], tức đã được tự cắt và nhận dạng. */
    fun skip(start: Long, size: Int, consumedEnd: Long): Int = (consumedEnd - start).coerceIn(0L, size.toLong()).toInt()
}
