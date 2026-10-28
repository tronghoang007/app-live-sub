package vn.livesub.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleanerTest {

    @Test
    fun `bo tu dem dau cau va them dau cham`() {
        assertEquals("どこへ行きますか。", TextCleaner.clean("えっと、どこへ行きますか", "ja", isFinal = true))
        assertEquals("ぜひ。", TextCleaner.clean("A ぜひ", "ja", isFinal = true))
        assertEquals("行こう。", TextCleaner.clean("えっと、あのー、行こう", "ja", isFinal = true))
    }

    @Test
    fun `giu nguyen tu co nghia va dau cau san co`() {
        assertEquals("あの人は誰ですか？", TextCleaner.clean("あの人は誰ですか？", "ja", isFinal = true))
        assertEquals("「はい」", TextCleaner.clean("「はい」", "ja", isFinal = true))
    }

    @Test
    fun `cau tam va ngon ngu khac khong them dau`() {
        assertEquals("今日は", TextCleaner.clean("今日は", "ja", isFinal = false))
        assertEquals("A nice day", TextCleaner.clean("A nice day", "en", isFinal = true))
    }

    @Test
    fun `nhan ra cau phien am tieng nuoc ngoai`() {
        org.junit.Assert.assertTrue(TextCleaner.isMostlyKatakana("エクスキューズミー"))
        org.junit.Assert.assertTrue(TextCleaner.isMostlyKatakana("デンOBIGO"))
        org.junit.Assert.assertFalse(TextCleaner.isMostlyKatakana("この電車は甲子園へ行きますか"))
        org.junit.Assert.assertFalse(TextCleaner.isMostlyKatakana("はい"))
    }

    @Test
    fun `chi co tu dem thi thanh rong`() {
        assertEquals("", TextCleaner.clean("えっと", "ja", isFinal = true))
    }
}
