package vn.livesub.audio

import kotlin.math.sqrt

/**
 * Nhận ra đang phát nhạc, để tạm tắt chế độ bắt nói thầm (VAD nhạy, khuếch đại) vốn hay bắt nhầm nhạc thành chữ.
 *
 * Dùng tỉ lệ khung âm thấp: trong [WINDOW_FRAMES] khung 20 ms gần nhất (2 s), bao nhiêu khung nhỏ hơn
 * nửa mức trung bình. Lời nói có quãng ngắt giữa các âm tiết nên tỉ lệ cao; nhạc kêu liên tục nên rất thấp.
 * Đo trên máy tính bằng giọng tổng hợp và nhạc tổng hợp: giọng nói, giọng thì thầm, giọng có nhạc nền nhỏ
 * 0,20–0,54; nhạc (dàn dây, piano, pop có trống) 0,00–0,02. Nhạc thật chưa đo.
 * Phải ổn định [CONFIRM] lần đo liền (0,5 s với chunk 100 ms) mới đổi trạng thái, để không đổi qua đổi lại.
 * Thuần Kotlin để unit test được.
 */
class MusicDetector(sampleRate: Int = 16_000) {
    private val frameSize = sampleRate / 50
    private val frames = FloatArray(WINDOW_FRAMES)
    private var filled = 0
    private var next = 0
    private var sum = 0.0
    private var inFrame = 0
    private var streak = 0

    var isMusic = false
        private set

    /** Tỉ lệ khung âm thấp lần đo gần nhất (để ghi log). */
    var lowEnergyRatio = 0f
        private set

    /** Đưa âm thanh gốc (chưa khuếch đại, -1..1) vào; trả về có đang là nhạc không. */
    fun onChunk(samples: FloatArray): Boolean {
        for (v in samples) {
            sum += v.toDouble() * v
            if (++inFrame < frameSize) continue
            frames[next] = sqrt(sum / frameSize).toFloat()
            next = (next + 1) % WINDOW_FRAMES
            if (filled < WINDOW_FRAMES) filled++
            sum = 0.0
            inFrame = 0
        }
        if (filled < WINDOW_FRAMES) return isMusic
        val mean = frames.average().toFloat()
        // Im lặng hoặc nhiễu nền: không đủ để biết, giữ nguyên trạng thái.
        if (mean < SILENCE_RMS) {
            streak = 0
            return isMusic
        }
        lowEnergyRatio = frames.count { it < 0.5f * mean }.toFloat() / WINDOW_FRAMES
        val flip = if (isMusic) lowEnergyRatio > EXIT_RATIO else lowEnergyRatio < ENTER_RATIO
        streak = if (flip) streak + 1 else 0
        if (streak >= CONFIRM) {
            isMusic = !isMusic
            streak = 0
        }
        return isMusic
    }

    private companion object {
        const val WINDOW_FRAMES = 100
        const val SILENCE_RMS = 0.001f
        const val ENTER_RATIO = 0.10f
        const val EXIT_RATIO = 0.18f
        const val CONFIRM = 5
    }
}
