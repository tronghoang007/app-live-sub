package vn.livesub.translate

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import vn.livesub.AppLog
import java.net.HttpURLConnection
import java.net.URI

/**
 * Dịch câu final bằng LLM chạy trên máy tính trong mạng nội bộ (`server/livesub_server.py`).
 *
 * Câu final dịch bằng [refine] (LLM). [quick] là bản [fallback] (ML Kit) cho lúc LLM không có:
 * server lỗi hoặc không theo kịp nhịp thoại. Mất kết nối thì tạm không gọi server trong
 * [BACKOFF_MS] để phụ đề không bị treo chờ timeout ở từng câu.
 */
class ServerTranslator(private val fallback: SubtitleTranslator) : SubtitleTranslator {

    /** Địa chỉ server, nhập tay hoặc do [ServerDiscovery] tìm thấy; null = chưa có, dịch bằng [fallback]. */
    @Volatile
    var baseUrl: String? = null
        set(value) {
            if (value != field) downUntil = 0L
            field = value
        }

    private data class Line(val lang: String, val src: String, val dst: String)

    /** Vài câu gần nhất gửi kèm để LLM chọn xưng hô, giọng điệu cho đúng. */
    private val history = ArrayDeque<Line>()
    @Volatile
    private var downUntil = 0L

    override fun prepare(sourceLang: String, targetLang: String) = fallback.prepare(sourceLang, targetLang)

    override suspend fun translate(text: String, sourceLang: String, targetLang: String, isFinal: Boolean): TranslationResult {
        if (sourceLang == targetLang) return TranslationResult.SameLanguage
        if (!isFinal || text.isBlank()) return fallback.translate(text, sourceLang, targetLang, isFinal)
        // Đang tìm server (chưa thấy) thì dịch ML Kit như bình thường, không báo lỗi.
        if (baseUrl == null) return fallback.translate(text, sourceLang, targetLang, isFinal)
        if (!canRefine()) return viaFallback(text, sourceLang, targetLang)
        return refine(text, sourceLang, targetLang)?.let { TranslationResult.Success(it) }
            ?: viaFallback(text, sourceLang, targetLang)
    }

    /** Server đã có địa chỉ và không đang tạm nghỉ sau lần lỗi gần nhất. */
    fun canRefine(): Boolean = baseUrl != null && SystemClock.elapsedRealtime() >= downUntil

    /**
     * Bản dịch bằng [fallback], không bao giờ chờ server (cũng là chỗ [PhraseBook] được tra).
     * Server đang tạm nghỉ sau lỗi thì kèm thông báo như [translate].
     */
    suspend fun quick(text: String, sourceLang: String, targetLang: String): TranslationResult =
        if (baseUrl != null && !canRefine()) viaFallback(text, sourceLang, targetLang)
        else fallback.translate(text, sourceLang, targetLang, isFinal = true)

    /** Dịch câu final bằng LLM; null nếu lỗi (server tạm nghỉ [BACKOFF_MS]), khi đó giữ bản [quick]. */
    suspend fun refine(text: String, sourceLang: String, targetLang: String): String? {
        val url = baseUrl ?: return null
        val context = synchronized(history) { history.filter { it.lang == sourceLang } }
        return runCatching { withContext(Dispatchers.IO) { request(url, text, sourceLang, targetLang, context) } }
            .onSuccess { out ->
                synchronized(history) {
                    history.addLast(Line(sourceLang, text, out))
                    while (history.size > MAX_HISTORY) history.removeFirst()
                }
            }
            .onFailure { e ->
                AppLog.w(TAG) { "server translate failed: ${e.message}" }
                downUntil = SystemClock.elapsedRealtime() + BACKOFF_MS
            }
            .getOrNull()
    }

    private suspend fun viaFallback(text: String, sourceLang: String, targetLang: String): TranslationResult {
        val result = fallback.translate(text, sourceLang, targetLang, isFinal = true)
        return if (result is TranslationResult.Success) result.copy(note = SERVER_DOWN_NOTE) else result
    }

    private fun request(baseUrl: String, text: String, source: String, target: String, context: List<Line>): String {
        val body = JSONObject()
            .put("text", text)
            .put("source", source)
            .put("target", target)
            .put("context", JSONArray(context.map { JSONObject().put("src", it.src).put("dst", it.dst) }))
        val conn = URI("$baseUrl/translate").toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            return json.getString("text").also { check(it.isNotBlank()) { "bản dịch rỗng" } }
        } finally {
            conn.disconnect()
        }
    }

    override fun close() = fallback.close()

    companion object {
        private const val TAG = "ServerTranslator"
        private const val MAX_HISTORY = 4
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val READ_TIMEOUT_MS = 8_000
        private const val BACKOFF_MS = 15_000L
        const val SERVER_DOWN_NOTE = "Không kết nối được server dịch trên máy tính, tạm dịch bằng ML Kit."

        /** "192.168.1.5" / "192.168.1.5:8765" / "http://…/" → "http://192.168.1.5:8765"; rỗng = tắt. */
        fun normalizeUrl(input: String): String? {
            var s = input.trim()
            if (s.isEmpty()) return null
            if (!s.contains("://")) s = "http://$s"
            val uri = runCatching { URI(s) }.getOrNull() ?: return null
            if (uri.host.isNullOrEmpty()) return null
            val port = if (uri.port == -1) DEFAULT_PORT else uri.port
            return "${uri.scheme}://${uri.host}:$port"
        }

        /** Gọi /health, trả về tên model nếu server đang chạy. */
        suspend fun ping(baseUrl: String): Result<String> = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URI("$baseUrl/health").toURL().openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 3_000
                    conn.readTimeout = 3_000
                    check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
                    JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getString("model")
                } finally {
                    conn.disconnect()
                }
            }
        }

        private const val DEFAULT_PORT = 8765
    }
}
