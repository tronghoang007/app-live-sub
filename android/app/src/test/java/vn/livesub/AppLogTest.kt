package vn.livesub

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLogTest {

    @Test
    fun `mac dinh tat nen khong dung chuoi va khong goi Log`() {
        // Log của Android không có trong unit test: nếu gọi thì ném lỗi, nên test này chứng minh bản
        // phát hành (enabled = false) không ghi gì và cũng không dựng chuỗi lời nhắn.
        assertEquals(false, AppLog.enabled)
        var built = 0
        AppLog.d("t") { built++; "x" }
        AppLog.i("t") { built++; "x" }
        AppLog.w("t") { built++; "x" }
        AppLog.e("t", RuntimeException()) { built++; "x" }
        assertEquals(0, built)
    }
}
