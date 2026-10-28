package vn.livesub.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCutterTest {

    private val sr = 16_000

    @Test
    fun `cat o cho lang gan cuoi thay vi dung moc thoi gian`() {
        val samples = FloatArray(sr * 7) { 0.2f }
        // Khoảng lặng 100 ms ở giây 6,0–6,1.
        for (i in sr * 6 until sr * 6 + sr / 10) samples[i] = 0f
        val cut = SpeechCutter.cutPoint(samples, samples.size, sr)
        assertTrue("cắt ở $cut", cut in sr * 6..sr * 6 + sr / 10)
    }

    @Test
    fun `khong co cho lang thi cat o gan cuoi nhat co the`() {
        val samples = FloatArray(sr * 7) { 0.2f }
        val cut = SpeechCutter.cutPoint(samples, samples.size, sr)
        // Chừa 0,2 s cuối.
        assertTrue("cắt ở $cut", cut >= samples.size - (0.25f * sr).toInt() && cut <= samples.size - (0.2f * sr).toInt())
    }

    @Test
    fun `doan qua ngan thi khong cat`() {
        val samples = FloatArray(sr / 10) { 0.2f }
        assertEquals(samples.size, SpeechCutter.cutPoint(samples, samples.size, sr))
    }

    @Test
    fun `chi bo phan nam truoc moc da cat`() {
        assertEquals(200, SpeechCutter.skip(start = 1_000, size = 500, consumedEnd = 1_200))
        assertEquals(0, SpeechCutter.skip(1_000, 500, consumedEnd = 900))
        assertEquals(500, SpeechCutter.skip(1_000, 500, consumedEnd = 5_000))
    }
}
