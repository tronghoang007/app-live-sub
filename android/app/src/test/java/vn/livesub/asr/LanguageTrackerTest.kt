package vn.livesub.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.livesub.asr.LanguageTracker.Decision

class LanguageTrackerTest {

    @Test
    fun `chua du 5 giay giong noi thi chua chot`() {
        val t = LanguageTracker()
        t.observe("ja", 2f, voteable = true)
        t.observe("ja", 2f, voteable = true)
        assertNull(t.sessionLang)
        assertEquals("", t.forcedLanguage())
    }

    @Test
    fun `du 5 giay va do tin cay tu 0_6 thi chot ngon ngu`() {
        val t = LanguageTracker()
        t.observe("ja", 2f, true)
        t.observe("ko", 1f, true)
        t.observe("ja", 2.5f, true) // ja = 4.5 / 5.5 = 0.82
        assertEquals("ja", t.sessionLang)
        assertEquals("ja", t.forcedLanguage())
    }

    @Test
    fun `do tin cay duoi 0_6 thi gom them`() {
        val t = LanguageTracker()
        t.observe("ja", 2f, true)
        t.observe("zh", 2f, true)
        t.observe("ko", 2f, true) // mỗi ngôn ngữ 1/3
        assertNull(t.sessionLang)
        t.observe("ja", 3f, true)
        t.observe("ja", 3f, true) // cửa sổ 10s gần nhất: ja chiếm đa số
        assertEquals("ja", t.sessionLang)
    }

    @Test
    fun `doan khong voteable khong duoc tinh`() {
        val t = LanguageTracker()
        repeat(5) { t.observe("en", 2f, voteable = false) }
        assertNull(t.sessionLang)
    }

    @Test
    fun `da chot thi cau lech duoc giai ma lai voi ngon ngu phien`() {
        val t = lockedTracker("ja")
        val d = t.observe("zh", 2f, true)
        assertEquals(Decision.Redecode("ja"), d)
        assertEquals("ja", t.sessionLang)
    }

    @Test
    fun `doi ngon ngu khi 3 cau lien tiep cung ra ngon ngu moi`() {
        val t = lockedTracker("ja")
        assertEquals(Decision.Redecode("ja"), t.observe("ko", 2f, true))
        assertEquals(Decision.Redecode("ja"), t.observe("ko", 2f, true))
        assertEquals(Decision.Accept("ko"), t.observe("ko", 2f, true))
        assertEquals("ko", t.sessionLang)
    }

    @Test
    fun `cau dung ngon ngu xen giua thi khong doi`() {
        val t = lockedTracker("ja")
        t.observe("ko", 2f, true)
        t.observe("ko", 2f, true)
        t.observe("ja", 2f, true)
        assertEquals(Decision.Redecode("ja"), t.observe("ko", 2f, true))
        assertEquals("ja", t.sessionLang)
    }

    @Test
    fun `cau ngan khong duoc tinh la bang chung doi ngon ngu`() {
        val t = lockedTracker("ja")
        repeat(5) { assertEquals(Decision.Redecode("ja"), t.observe("en", 0.5f, true)) }
        assertEquals("ja", t.sessionLang)
    }

    @Test
    fun `do tin cay giam lien tuc thi nhan dien lai tu dau`() {
        val t = lockedTracker("ja")
        // Xen kẽ hai ngôn ngữ lạ: không đủ 3 câu liên tiếp để đổi, nhưng đồng thuận rơi xuống 0.
        listOf("ko", "zh", "ko", "zh", "ko").forEach { t.observe(it, 2f, true) }
        assertEquals("ja", t.sessionLang)
        val d = t.observe("zh", 2f, true)
        assertTrue(d is Decision.Accept)
        assertNull(t.sessionLang)
    }

    @Test
    fun `khoa thu cong thi bo qua phat hien`() {
        val t = LanguageTracker()
        t.lock("th")
        repeat(5) { assertEquals(Decision.Accept("th"), t.observe("en", 3f, true)) }
        assertEquals("th", t.sessionLang)
        t.unlock()
        assertEquals("th", t.sessionLang)
    }

    private fun lockedTracker(lang: String) = LanguageTracker().apply {
        observe(lang, 3f, true)
        observe(lang, 3f, true)
        check(sessionLang == lang)
    }
}
