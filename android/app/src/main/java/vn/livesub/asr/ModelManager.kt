package vn.livesub.asr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.livesub.data.AsrModel
import vn.livesub.data.ModelPart
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Đường dẫn các file model cần cho một phiên nhận dạng. */
data class AsrModelFiles(val parts: Map<ModelPart, File>, val vad: File) {
    operator fun get(part: ModelPart): File = parts.getValue(part)
    val all get() = parts.values + vad
}

sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    data class Downloading(val size: AsrModel, val progress: Float) : ModelDownloadState
    data class Failed(val size: AsrModel, val message: String) : ModelDownloadState
}

/**
 * Tải và quản lý model nhận dạng (sherpa-onnx) + Silero VAD trong bộ nhớ
 * trong của app. Tải tiếp được khi rớt mạng nhờ file .part + HTTP Range.
 */
class ModelManager(private val context: Context, private val scope: CoroutineScope) {

    private val root = File(context.filesDir, "models")
    private val _state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val state: StateFlow<ModelDownloadState> = _state

    /** Tăng mỗi khi có model được tải xong/xoá để UI kiểm tra lại. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    private var job: Job? = null

    init {
        // Model đã bỏ khỏi app: dọn file đã tải, nếu không chúng chiếm bộ nhớ mà UI không còn xoá được.
        scope.launch(Dispatchers.IO) { RETIRED_DIRS.forEach { File(root, it).deleteRecursively() } }
    }

    fun files(model: AsrModel): AsrModelFiles {
        val dir = File(root, model.dirName)
        return AsrModelFiles(
            parts = model.files.mapValues { (_, name) -> File(dir, name) },
            vad = File(root, "silero_vad.onnx"),
        )
    }

    fun isReady(model: AsrModel): Boolean = files(model).all.all { it.isFile && it.length() > 0 }

    fun download(size: AsrModel, wifiOnly: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (wifiOnly && isMetered()) {
                _state.value = ModelDownloadState.Failed(size, "Đang dùng mạng tính phí. Hãy bật Wi-Fi hoặc tắt \"Chỉ tải qua Wi-Fi\".")
                return@launch
            }
            val f = files(size)
            val base = "https://huggingface.co/${size.repo}/resolve/main"
            val tasks = (f.parts.values.map { file -> "$base/${file.name}" to file } + (VAD_URL to f.vad))
                .filterNot { (_, file) -> file.isFile && file.length() > 0 }

            try {
                _state.value = ModelDownloadState.Downloading(size, 0f)
                tasks.forEachIndexed { index, (url, file) ->
                    fetch(url, file) { fraction ->
                        _state.value = ModelDownloadState.Downloading(size, (index + fraction) / tasks.size)
                    }
                }
                _state.value = ModelDownloadState.Idle
                _revision.value++
            } catch (e: IOException) {
                _state.value = ModelDownloadState.Failed(
                    size, "Tải model thất bại (${e.message ?: "mất mạng"}). Bấm tải lại để tiếp tục từ chỗ dừng.",
                )
            }
        }
    }

    fun delete(model: AsrModel) {
        File(root, model.dirName).deleteRecursively()
        _revision.value++
    }

    private suspend fun fetch(url: String, target: File, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            val part = File(target.path + ".part")
            val existing = if (part.exists()) part.length() else 0L
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            try {
                val append = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
                if (!append && conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP ${conn.responseCode}")
                }
                val offset = if (append) existing else 0L
                val total = offset + conn.contentLengthLong.coerceAtLeast(0)
                var done = offset
                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
                if (!part.renameTo(target)) throw IOException("Không ghi được ${target.name}")
            } finally {
                conn.disconnect()
            }
        }

    private fun isMetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    companion object {
        /** Whisper tiny, Whisper base và SenseVoice. */
        private val RETIRED_DIRS = listOf("whisper-tiny", "whisper-base", "sense-voice-2025-09-09")

        private const val VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
    }
}
