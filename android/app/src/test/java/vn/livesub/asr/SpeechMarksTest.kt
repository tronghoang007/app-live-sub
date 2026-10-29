package vn.livesub.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechMarksTest {

    @Test
    fun `ti le cua so co tieng noi trong khoang mau`() {
        val marks = SpeechMarks(window = 512, capacity = 10)
        // Cửa sổ 0..3 im lặng, 4..7 có tiếng nói.
        repeat(4) { marks.add(false) }
        repeat(4) { marks.add(true) }
        assertEquals(1f, marks.fraction(4 * 512L, 8 * 512L), 0.001f)
        assertEquals(0.5f, marks.fraction(2 * 512L, 6 * 512L), 0.001f)
        assertEquals(0f, marks.fraction(0, 4 * 512L), 0.001f)
    }

    @Test
    fun `cua so da troi khoi bo nho thi khong tinh`() {
        val marks = SpeechMarks(window = 512, capacity = 4)
        repeat(4) { marks.add(false) }
        repeat(4) { marks.add(true) }
        // Cửa sổ 0..3 đã bị ghi đè: chỉ còn 4..7.
        assertEquals(1f, marks.fraction(0, 8 * 512L), 0.001f)
        assertEquals(0f, marks.fraction(0, 3 * 512L), 0.001f)
    }
}
