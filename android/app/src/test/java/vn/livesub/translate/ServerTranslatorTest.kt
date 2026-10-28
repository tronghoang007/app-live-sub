package vn.livesub.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerTranslatorTest {

    @Test
    fun `them http va cong mac dinh`() {
        assertEquals("http://192.168.1.5:8765", ServerTranslator.normalizeUrl("192.168.1.5"))
        assertEquals("http://192.168.1.5:9000", ServerTranslator.normalizeUrl(" 192.168.1.5:9000 "))
        assertEquals("http://macbook.local:8765", ServerTranslator.normalizeUrl("http://macbook.local/"))
    }

    @Test
    fun `rong hoac sai thi tat server`() {
        assertNull(ServerTranslator.normalizeUrl(""))
        assertNull(ServerTranslator.normalizeUrl("   "))
        assertNull(ServerTranslator.normalizeUrl("http://"))
    }
}
