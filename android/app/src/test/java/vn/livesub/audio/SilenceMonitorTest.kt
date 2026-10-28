package vn.livesub.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceMonitorTest {

    private val zeros = ShortArray(1600)
    private val sound = ShortArray(1600) { if (it % 2 == 0) 1200 else -1200 }

    @Test
    fun `im lang tuyet doi keo dai thi canh bao`() {
        val m = SilenceMonitor(warnAfterMs = 1_000)
        repeat(9) { m.onChunk(zeros, 100) }
        assertEquals(SilenceMonitor.State.SILENT, m.state)
        assertTrue(m.onChunk(zeros, 100))
        assertEquals(SilenceMonitor.State.LONG_SILENCE, m.state)
    }

    @Test
    fun `co am thanh thi dat lai bo dem`() {
        val m = SilenceMonitor(warnAfterMs = 1_000)
        repeat(9) { m.onChunk(zeros, 100) }
        m.onChunk(sound, 100)
        assertEquals(SilenceMonitor.State.SOUND, m.state)
        repeat(9) { m.onChunk(zeros, 100) }
        assertEquals(SilenceMonitor.State.SILENT, m.state)
    }
}
