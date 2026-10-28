package vn.livesub.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechPaddingTest {

    private val sr = 16_000

    @Test
    fun `dem 0,3 giay truoc va sau doan`() {
        val (from, to) = SpeechPadding.bounds(start = 32_000, length = 16_000, previousEnd = 0, available = 100_000, cutByLimit = false, sampleRate = sr)
        assertEquals(32_000L - 4_800, from)
        assertEquals(48_000L + 4_800, to)
    }

    @Test
    fun `khong lan sang am thanh cua doan truoc`() {
        // Đoạn trước đã lấy tới 30_000: phần đệm đầu chỉ bắt đầu từ đó.
        val (from, _) = SpeechPadding.bounds(32_000, 16_000, previousEnd = 30_000, available = 100_000, cutByLimit = false, sampleRate = sr)
        assertEquals(30_000L, from)
    }

    @Test
    fun `doan noi tiep ngay sau doan bi cat cung thi khong dem dau`() {
        val (from, _) = SpeechPadding.bounds(96_000, 16_000, previousEnd = 96_000, available = 200_000, cutByLimit = false, sampleRate = sr)
        assertEquals(96_000L, from)
    }

    @Test
    fun `bi cat cung thi khong dem cuoi`() {
        val (_, to) = SpeechPadding.bounds(0, 96_000, previousEnd = 0, available = 200_000, cutByLimit = true, sampleRate = sr)
        assertEquals(96_000L, to)
    }

    @Test
    fun `dem cuoi khong vuot qua am thanh da nhan`() {
        val (_, to) = SpeechPadding.bounds(0, 16_000, previousEnd = 0, available = 17_000, cutByLimit = false, sampleRate = sr)
        assertEquals(17_000L, to)
    }

    @Test
    fun `dau luong khong dem am`() {
        val (from, _) = SpeechPadding.bounds(1_000, 16_000, previousEnd = 0, available = 100_000, cutByLimit = false, sampleRate = sr)
        assertEquals(0L, from)
    }

    @Test
    fun `lich su tra lai dung am thanh theo vi tri tuyet doi`() {
        val h = AudioHistory(capacity = 10)
        h.append(FloatArray(6) { it.toFloat() })
        assertArrayEquals(floatArrayOf(2f, 3f, 4f), h.slice(2, 5)!!, 0f)
        assertEquals(6L, h.total)
    }

    @Test
    fun `lich su van dung sau khi ghi vong lai`() {
        val h = AudioHistory(capacity = 10)
        h.append(FloatArray(8) { it.toFloat() })
        h.append(FloatArray(8) { (8 + it).toFloat() })
        // 16 mẫu đã vào, còn giữ 6..15.
        assertArrayEquals(floatArrayOf(6f, 7f, 8f, 9f, 10f), h.slice(6, 11)!!, 0f)
        assertArrayEquals(floatArrayOf(14f, 15f), h.slice(14, 16)!!, 0f)
    }

    @Test
    fun `lich su tu choi vung da bi ghi de hoac chua toi`() {
        val h = AudioHistory(capacity = 10)
        h.append(FloatArray(16) { it.toFloat() })
        assertNull(h.slice(5, 8))
        assertNull(h.slice(10, 17))
        assertNull(h.slice(8, 7))
    }
}
