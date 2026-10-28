package vn.livesub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import vn.livesub.LiveSubApp
import vn.livesub.asr.ModelDownloadState
import vn.livesub.data.AppSettings
import vn.livesub.data.AsrModel
import vn.livesub.data.Languages
import vn.livesub.service.SessionState
import vn.livesub.service.SessionStatus
import vn.livesub.translate.DiscoveredServer
import vn.livesub.translate.ServerDiscovery
import vn.livesub.translate.ServerTranslator

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as LiveSubApp).container

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val session: StateFlow<SessionStatus> = SessionState.status

    val download: StateFlow<ModelDownloadState> = container.models.state

    /** Model nào đã có sẵn trên máy. */
    val readyModels: StateFlow<Set<AsrModel>> =
        combine(container.models.revision, container.models.state) { _, _ ->
            AsrModel.entries.filter { container.models.isReady(it) }.toSet()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _prefetch = MutableStateFlow<String?>(null)
    /** Trạng thái tải trước model dịch ML Kit. */
    val prefetch: StateFlow<String?> = _prefetch

    private val _serverCheck = MutableStateFlow<String?>(null)
    /** Kết quả lần kiểm tra kết nối server dịch gần nhất. */
    val serverCheck: StateFlow<String?> = _serverCheck

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settings.update(transform) }
    }

    fun downloadModel(size: AsrModel) = container.models.download(size, settings.value.wifiOnlyDownloads)

    fun deleteModel(size: AsrModel) = container.models.delete(size)

    /** Server dịch tìm thấy qua mDNS; chỉ tìm khi màn hình Cài đặt đang theo dõi. */
    val discoveredServer: StateFlow<DiscoveredServer?> = ServerDiscovery(app).servers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Lưu địa chỉ server dịch nhập tay rồi thử gọi /health. Rỗng = tự tìm trong mạng. */
    fun saveTranslationServer(input: String) {
        val url = ServerTranslator.normalizeUrl(input)
        if (input.isNotBlank() && url == null) {
            _serverCheck.value = "Địa chỉ không hợp lệ."
            return
        }
        update { it.copy(translationServer = input.trim()) }
        if (url == null) {
            _serverCheck.value = "Đã xoá địa chỉ, app sẽ tự tìm server trong mạng."
            return
        }
        _serverCheck.value = "Đang kết nối $url…"
        viewModelScope.launch {
            _serverCheck.value = ServerTranslator.ping(url).fold(
                onSuccess = { "Đã kết nối: $it" },
                onFailure = { "Không kết nối được $url (${it.message}). Kiểm tra server đã chạy và cùng mạng Wi-Fi." },
            )
        }
    }

    /** Tải trước model dịch cho các nguồn phổ biến để xem phim không cần mạng. */
    fun prefetchTranslationModels() {
        val target = settings.value.targetLang
        val conditions = DownloadConditions.Builder()
            .apply { if (settings.value.wifiOnlyDownloads) requireWifi() }
            .build()
        viewModelScope.launch {
            val sources = Languages.commonSources.filter { it != target }
            var failed = 0
            sources.forEachIndexed { i, src ->
                _prefetch.value = "Đang tải ${src.uppercase()}→${target.uppercase()} (${i + 1}/${sources.size})…"
                val source = TranslateLanguage.fromLanguageTag(src) ?: return@forEachIndexed
                val tgt = TranslateLanguage.fromLanguageTag(target) ?: return@forEachIndexed
                val client = Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(tgt).build(),
                )
                runCatching { client.downloadModelIfNeeded(conditions).await() }.onFailure { failed++ }
                client.close()
            }
            _prefetch.value = if (failed == 0) "Đã tải xong model dịch." else "Có $failed gói tải lỗi, kiểm tra mạng rồi thử lại."
        }
    }
}
