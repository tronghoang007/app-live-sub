package vn.livesub.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HallucinationFilterTest {

    @Test
    fun `loc cau bia quen thuoc cua Whisper`() {
        assertTrue(HallucinationFilter.isLikelyHallucination("ご視聴ありがとうございました"))
        assertTrue(HallucinationFilter.isLikelyHallucination("Thank you for watching!"))
        assertTrue(HallucinationFilter.isLikelyHallucination("시청해주셔서 감사합니다."))
    }

    @Test
    fun `loc ky hieu va lap vo han`() {
        assertTrue(HallucinationFilter.isLikelyHallucination(""))
        assertTrue(HallucinationFilter.isLikelyHallucination("♪ ♪ ..."))
        assertTrue(HallucinationFilter.isLikelyHallucination("あああああああああああああああ"))
        assertTrue(HallucinationFilter.isLikelyHallucination("no no no no no no no"))
    }

    @Test
    fun `loc chu thich am thanh trong ngoac`() {
        assertTrue(HallucinationFilter.isLikelyHallucination("(音楽)"))
        assertTrue(HallucinationFilter.isLikelyHallucination("(音楽"))
        assertTrue(HallucinationFilter.isLikelyHallucination("［拍手］"))
        assertTrue(HallucinationFilter.isLikelyHallucination("[Music]"))
    }

    @Test
    fun `loc ket qua qua ngan so voi do dai am thanh`() {
        assertTrue(HallucinationFilter.isImplausible("リ", 1.2f))
        assertTrue(HallucinationFilter.isImplausible("はい", 6.8f))
        assertFalse(HallucinationFilter.isImplausible("はい", 0.8f))
        assertFalse(HallucinationFilter.isImplausible("何", 0.5f))
        assertFalse(HallucinationFilter.isImplausible("この電車は甲子園へ行きますか", 3.0f))
    }

    @Test
    fun `giu cau thoai binh thuong`() {
        assertFalse(HallucinationFilter.isLikelyHallucination("今日はどこに行くの？"))
        assertFalse(HallucinationFilter.isLikelyHallucination("I told you not to come here."))
        assertFalse(HallucinationFilter.isLikelyHallucination("오빠, 같이 가요!"))
        assertFalse(HallucinationFilter.isLikelyHallucination("ฉันไม่รู้ว่าเขาไปไหน"))
    }
}
