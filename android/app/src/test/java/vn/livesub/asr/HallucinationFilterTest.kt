package vn.livesub.asr

import org.junit.Assert.assertEquals
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
        // Câu phản ứng ngắn có ngữ điệu hỏi/than trong hội thoại.
        assertFalse(HallucinationFilter.isImplausible("え？", 0.6f))
        assertFalse(HallucinationFilter.isImplausible("あ！", 0.5f))
        assertTrue(HallucinationFilter.isImplausible("え", 0.6f))
    }

    @Test
    fun `doan dai toan am giong tieng ren thi bo, loi ngan that thi giu khi khong co nhac`() {
        // Log trên máy thật: "うん" cho cả đoạn 6 s tiếng rên, hiện thành "Ừ" liên tục.
        assertTrue(HallucinationFilter.isImplausible("うん", 6.1f, strict = false))
        assertTrue(HallucinationFilter.isImplausible("あん", 3.0f, strict = false))
        // "よし" giữa 6 s tiếng rên: không có nhạc thì giữ, có nhạc thì vẫn coi là chữ bịa.
        assertFalse(HallucinationFilter.isImplausible("よし", 6.3f, strict = false))
        assertTrue(HallucinationFilter.isImplausible("よし", 6.3f, strict = true))
        assertFalse(HallucinationFilter.isImplausible("いい", 4.0f, strict = false))
        assertFalse(HallucinationFilter.isImplausible("はい", 4.0f, strict = false))
        // Câu ngắn bình thường không bị ảnh hưởng.
        assertFalse(HallucinationFilter.isImplausible("うん", 0.6f))
        assertFalse(HallucinationFilter.isImplausible("この電車は甲子園へ行きますか", 3.0f))
    }

    @Test
    fun `giu loi thoai xen tieng het, ren va chu thich am thanh`() {
        assertEquals("あああ、やめて！", HallucinationFilter.salvage("ああああああああああああああ、やめて！"))
        assertEquals("やめて！", HallucinationFilter.salvage("(悲鳴) やめて！"))
        assertEquals("待って", HallucinationFilter.salvage("待って（叫び声"))
        assertEquals("いやあああ 助けて", HallucinationFilter.salvage("♪ いやああああああ 助けて ♪"))
        assertFalse(HallucinationFilter.isLikelyHallucination("[screaming] Get out of here!"))
    }

    @Test
    fun `giu hoi thoai hai nguoi co cau dem lap lai`() {
        assertEquals("うん。 うん。 うん。 そうだね。", HallucinationFilter.salvage("うん。 うん。 うん。 うん。 うん。 そうだね。"))
        assertFalse(HallucinationFilter.isLikelyHallucination("はい。 はい。 はい。 そうです。 はい。 はい。"))
        assertFalse(HallucinationFilter.isLikelyHallucination("Yeah. No. Yeah. Yeah. Yeah. Okay."))
    }

    @Test
    fun `cau binh thuong giu nguyen`() {
        assertEquals("合計は1000000円です。", HallucinationFilter.salvage("合計は1000000円です。"))
        assertEquals("ははは、面白いね", HallucinationFilter.salvage("ははは、面白いね"))
    }

    @Test
    fun `giu cau thoai binh thuong`() {
        assertFalse(HallucinationFilter.isLikelyHallucination("今日はどこに行くの？"))
        assertFalse(HallucinationFilter.isLikelyHallucination("I told you not to come here."))
        assertFalse(HallucinationFilter.isLikelyHallucination("오빠, 같이 가요!"))
        assertFalse(HallucinationFilter.isLikelyHallucination("ฉันไม่รู้ว่าเขาไปไหน"))
    }
}
