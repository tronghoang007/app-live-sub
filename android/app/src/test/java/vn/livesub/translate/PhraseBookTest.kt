package vn.livesub.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhraseBookTest {

    @Test
    fun `cau ngan quen thuoc dich co dinh`() {
        assertEquals("Không", PhraseBook.lookup("いいえ。", "ja", "vi"))
        assertEquals("Cảm ơn", PhraseBook.lookup("どうも", "ja", "vi"))
        assertEquals("Vậy à", PhraseBook.lookup("そうですか。", "ja", "vi"))
    }

    @Test
    fun `giu dau hoi cua cau goc`() {
        assertEquals("Vậy à?", PhraseBook.lookup("そうですか？", "ja", "vi"))
        assertEquals("Có sao không?", PhraseBook.lookup("大丈夫ですか？", "ja", "vi"))
    }

    @Test
    fun `cau dai hoac cap ngon ngu khac thi bo qua`() {
        assertNull(PhraseBook.lookup("この電車は甲子園へ行きますか。", "ja", "vi"))
        assertNull(PhraseBook.lookup("はい", "ja", "en"))
    }
}
