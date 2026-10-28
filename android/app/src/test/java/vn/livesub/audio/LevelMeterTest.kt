package vn.livesub.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LevelMeterTest {

    private fun chunk(amplitude: Int) = ShortArray(1_600) { amplitude.toShort() }

    @Test
    fun `chua du cua so thi chua tra ve`() {
        val m = LevelMeter(windowMs = 1_000)
        repeat(9) { assertNull(m.onChunk(chunk(1_000), 100)) }
        assertNotNull(m.onChunk(chunk(1_000), 100))
    }

    @Test
    fun `do dinh va rms theo thang 0 den 1`() {
        val m = LevelMeter(windowMs = 200)
        m.onChunk(chunk(16_384), 100)
        val level = m.onChunk(chunk(16_384), 100)!!
        assertEquals(0.5f, level.peak, 0.001f)
        assertEquals(0.5f, level.rms, 0.001f)
    }

    @Test
    fun `im lang tuyet doi cho muc 0`() {
        val m = LevelMeter(windowMs = 100)
        val level = m.onChunk(chunk(0), 100)!!
        assertEquals(0f, level.peak, 0f)
        assertEquals(0f, level.rms, 0f)
    }

    @Test
    fun `moi cua so tinh rieng khong cong don`() {
        val m = LevelMeter(windowMs = 100)
        m.onChunk(chunk(16_384), 100)
        val quiet = m.onChunk(chunk(1_024), 100)!!
        assertEquals(0.03125f, quiet.peak, 0.0001f)
    }
}
