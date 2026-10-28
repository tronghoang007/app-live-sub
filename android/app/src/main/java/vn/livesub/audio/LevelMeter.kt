package vn.livesub.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Đo mức âm thanh bắt được theo từng cửa sổ ~[windowMs], để biết nguồn có nhỏ quá cho nhận dạng
 * không (AudioPlaybackCapture có thể phụ thuộc âm lượng media của máy). Chỉ dùng khi ghi log debug.
 * Thuần Kotlin để unit test được.
 */
class LevelMeter(private val windowMs: Long = 1_000) {

    /** Mức đỉnh và RMS theo thang 0..1 (1 = tràn thang PCM16). */
    data class Level(val peak: Float, val rms: Float)

    private var elapsedMs = 0L
    private var peak = 0
    private var sumSquares = 0.0
    private var count = 0L

    /** Trả về mức của cửa sổ vừa đủ [windowMs], hoặc null khi chưa đủ. */
    fun onChunk(samples: ShortArray, chunkMs: Long): Level? {
        for (s in samples) {
            val v = abs(s.toInt())
            if (v > peak) peak = v
            sumSquares += v.toDouble() * v
        }
        count += samples.size
        elapsedMs += chunkMs
        if (elapsedMs < windowMs) return null
        val level = Level(peak / FULL_SCALE, if (count > 0) (sqrt(sumSquares / count) / FULL_SCALE).toFloat() else 0f)
        elapsedMs = 0
        peak = 0
        sumSquares = 0.0
        count = 0
        return level
    }

    private companion object {
        const val FULL_SCALE = 32768f
    }
}
