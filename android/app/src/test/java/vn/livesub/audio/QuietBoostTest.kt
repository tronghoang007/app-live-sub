package vn.livesub.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class QuietBoostTest {

    /** Chunk 100 ms sóng sin có biên độ [amplitude] (0..1). */
    private fun tone(amplitude: Float) = FloatArray(1600) { (amplitude * sin(2 * PI * 300 * it / 16000)).toFloat() }

    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size).toFloat()

    @Test
    fun `khuech dai dan giong nho len gan muc dich`() {
        val boost = QuietBoost()
        var out = FloatArray(0)
        // Nói thầm ~ -43 dBFS (RMS ~0,007): hệ số tiến dần tới 0,05 / 0,007 ≈ 7,1, kéo về mức đích.
        repeat(30) { out = boost.process(tone(0.01f)) }
        assertEquals(7.07f, boost.gain, 0.1f)
        assertEquals(0.05f, rms(out), 0.002f)

        // Nhỏ hơn nữa thì dừng ở hệ số tối đa (x8, +18 dB).
        val faint = QuietBoost()
        repeat(30) { faint.process(tone(0.004f)) }
        assertEquals(8f, faint.gain, 0.05f)
    }

    @Test
    fun `khong khuech dai giong binh thuong va im lang`() {
        val loud = QuietBoost()
        repeat(10) { loud.process(tone(0.3f)) }
        assertEquals(1f, loud.gain, 0.001f)

        val silent = QuietBoost()
        repeat(10) { silent.process(FloatArray(1600)) }
        assertEquals(1f, silent.gain, 0.001f)
    }

    @Test
    fun `tieng to bat choc thi giam he so ngay, khong tran`() {
        val boost = QuietBoost()
        repeat(20) { boost.process(tone(0.01f)) }
        val out = boost.process(tone(0.8f))
        assertEquals(1f, boost.gain, 0.001f)
        assertTrue(out.all { it in -1f..1f })
    }
}
