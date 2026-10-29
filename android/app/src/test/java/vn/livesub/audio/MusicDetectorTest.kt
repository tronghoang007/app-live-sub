package vn.livesub.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MusicDetectorTest {
    private val rate = 16_000

    /** [sec] giây âm thanh, chia thành chunk 100 ms như lúc bắt âm thanh. */
    private fun chunks(sec: Float, sample: (Int) -> Float): List<FloatArray> {
        val all = FloatArray((sec * rate).toInt()) { sample(it) }
        return all.toList().chunked(rate / 10).map { it.toFloatArray() }
    }

    // Lời nói: âm tiết ~150 ms xen quãng ngắt ~100 ms, độ to thay đổi.
    private fun speech(amplitude: Float) = chunks(6f) { i ->
        val t = i % 4000
        val on = t % 250 < 150 && t < 3500
        if (on) amplitude * (0.6f + 0.4f * sin(2 * PI * i / 9000).toFloat()) * sin(2 * PI * 180 * i / rate).toFloat() else 0f
    }

    // Nhạc: hợp âm kéo dài, kêu liên tục.
    private val music = chunks(6f) { i ->
        (0.1 * (sin(2 * PI * 220 * i / rate) + sin(2 * PI * 277 * i / rate) + sin(2 * PI * 330 * i / rate))).toFloat()
    }

    @Test
    fun `nhan ra nhac, khong nham giong noi va giong thi tham`() {
        assertTrue(MusicDetector().run { music.forEach { onChunk(it) }; isMusic })
        assertFalse(MusicDetector().run { speech(0.3f).forEach { onChunk(it) }; isMusic })
        assertFalse(MusicDetector().run { speech(0.01f).forEach { onChunk(it) }; isMusic })
    }

    @Test
    fun `het nhac sang loi noi thi tat, im lang khong bi coi la nhac`() {
        val d = MusicDetector()
        music.forEach { d.onChunk(it) }
        assertTrue(d.isMusic)
        speech(0.3f).forEach { d.onChunk(it) }
        assertFalse(d.isMusic)

        assertFalse(MusicDetector().run { chunks(5f) { 0f }.forEach { onChunk(it) }; isMusic })
    }
}
