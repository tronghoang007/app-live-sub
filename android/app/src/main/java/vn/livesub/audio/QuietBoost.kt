package vn.livesub.audio

import kotlin.math.sqrt

/**
 * Khuếch đại đoạn âm thanh nhỏ (nói thầm, thì thào) trước khi đưa cho VAD và engine nhận dạng: giọng
 * thì thầm nhỏ và không có thanh, Silero VAD cho xác suất dưới ngưỡng nên cả câu không được nhận dạng.
 *
 * Mức âm đo theo từng chunk, lên nhanh xuống chậm, để tiếng to bất chợt không bị khuếch đại vọt lên;
 * dưới [noiseFloor] là im lặng/nhiễu nền nên không khuếch đại. Hệ số thay đổi dần qua các chunk để không
 * nghe "bơm" to nhỏ. Thuần Kotlin để unit test được.
 */
class QuietBoost(
    private val targetRms: Float = 0.05f,
    private val maxGain: Float = 8f,
    private val noiseFloor: Float = 0.001f,
) {
    private var level = 0f

    /** Hệ số đang dùng (để ghi log). */
    var gain = 1f
        private set

    /** Bản khuếch đại của chunk (-1..1); không sửa [samples]. */
    fun process(samples: FloatArray): FloatArray {
        val out = samples.copyOf()
        if (out.isEmpty()) return out
        var sum = 0.0
        for (v in out) sum += v.toDouble() * v
        val rms = sqrt(sum / out.size).toFloat()
        level = if (rms > level) rms else 0.9f * level + 0.1f * rms
        val wanted = if (level < noiseFloor) 1f else (targetRms / level).coerceIn(1f, maxGain)
        // Giảm ngay (tránh tràn khi tiếng to đến), tăng dần.
        gain = if (wanted < gain) wanted else gain + 0.2f * (wanted - gain)
        if (gain > 1f) for (i in out.indices) out[i] = (out[i] * gain).coerceIn(-1f, 1f)
        return out
    }
}
