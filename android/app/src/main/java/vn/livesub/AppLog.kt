package vn.livesub

import android.util.Log

/**
 * Chỉ bản debug mới ghi logcat ([LiveSubApp.onCreate] bật). Log có cả chữ nhận dạng từ video người
 * dùng xem, nên bản phát hành không ghi gì. Lời nhắn là lambda để bản phát hành cũng không tốn
 * công dựng chuỗi.
 */
object AppLog {

    @PublishedApi
    internal var enabled = false

    inline fun d(tag: String, message: () -> String) {
        if (enabled) Log.d(tag, message())
    }

    inline fun i(tag: String, message: () -> String) {
        if (enabled) Log.i(tag, message())
    }

    inline fun w(tag: String, message: () -> String) {
        if (enabled) Log.w(tag, message())
    }

    inline fun e(tag: String, error: Throwable, message: () -> String) {
        if (enabled) Log.e(tag, message(), error)
    }
}
