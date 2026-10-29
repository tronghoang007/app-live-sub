package vn.livesub.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class VocalNoiseFilterTest {

    /** Dựng danh sách chữ + thời điểm từ các cặp (chữ, giây). */
    private fun filter(vararg tokens: Pair<String, Float>) =
        VocalNoiseFilter.filter(tokens.map { it.first }, tokens.map { it.second }.toFloatArray())

    @Test
    fun `chu dau luon ghep voi chu thu hai (thoi diem chu dau luon ~0)`() {
        // Lấy từ log trên máy thật: trước đây thành "うね", "", "どあんなんでも…".
        assertEquals("そうね" to false, filter("そ" to 0f, "う" to 1.72f, "ね" to 1.96f))
        assertEquals("はい" to false, filter("は" to 0f, "い" to 0.88f))
        assertEquals(
            "けどあんなんでも" to false,
            filter("け" to 0f, "ど" to 0.8f, "あ" to 1.28f, "ん" to 1.44f, "な" to 1.64f, "ん" to 1.84f, "で" to 2.0f, "も" to 2.2f),
        )
    }

    @Test
    fun `bo cum toan am ren tho, giu loi that`() {
        assertEquals("" to true, filter("あ" to 0f, "っ" to 0.88f))
        assertEquals("" to true, filter("は" to 0.5f, "ぁ" to 0.6f, "ー" to 0.7f))
        // Tiếng rên tách xa lời nói thật ở giữa câu.
        assertEquals("やめて" to true, filter("や" to 0f, "め" to 0.15f, "て" to 0.3f, "あ" to 1.2f, "ん" to 1.3f))
        assertEquals("だめ" to true, filter("だ" to 0f, "め" to 0.2f, "ん" to 1.4f, "っ" to 1.5f))
    }

    @Test
    fun `chu le giua cau va chu Han le duoc giu`() {
        // Ngắt giữa hai vế câu > 0,5 s vẫn giữ cả hai vế.
        assertEquals("のよねほれたら" to false, filter("の" to 0f, "よ" to 0.16f, "ね" to 0.4f, "ほ" to 1.12f, "れ" to 1.28f, "た" to 1.56f, "ら" to 1.72f))
        assertEquals("何" to false, filter("何" to 0.5f))
        assertEquals("えっ" to false, filter("え" to 0f, "っ" to 0.92f))
    }

    @Test
    fun `thieu thoi diem thi tra nguyen chu`() {
        assertEquals("あだめ" to false, VocalNoiseFilter.filter(listOf("あ", "だ", "め"), floatArrayOf()))
    }
}
