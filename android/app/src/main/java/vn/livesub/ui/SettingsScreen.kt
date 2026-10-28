package vn.livesub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vn.livesub.data.AsrModel
import vn.livesub.data.Languages
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val readyModels by vm.readyModels.collectAsStateWithLifecycle()
    val prefetch by vm.prefetch.collectAsStateWithLifecycle()
    val serverCheck by vm.serverCheck.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Quay lại") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard("Hiển thị phụ đề") {
                Text(
                    "Thay đổi áp dụng ngay cả khi đang chạy. Kéo thẳng khung phụ đề (hoặc nhãn ngôn ngữ) để đổi vị trí.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LabeledSlider(
                    label = "Số câu hiển thị cùng lúc",
                    valueText = if (s.stackSize == 1) "1 (kiểu phụ đề)" else "${s.stackSize} (kiểu chat)",
                    value = s.stackSize.toFloat(),
                    range = 1f..5f,
                    steps = 3,
                ) { v -> vm.update { it.copy(stackSize = v.roundToInt()) } }
                LabeledSlider("Cỡ chữ", "${s.textSizeSp.roundToInt()} sp", s.textSizeSp, 14f..40f) { v ->
                    vm.update { it.copy(textSizeSp = v) }
                }
                LabeledSlider("Độ đậm nền", "${(s.backgroundAlpha * 100).roundToInt()}%", s.backgroundAlpha, 0f..1f) { v ->
                    vm.update { it.copy(backgroundAlpha = v) }
                }
                LabeledSlider("Vị trí (cách đáy)", "${s.bottomOffsetPercent.roundToInt()}%", s.bottomOffsetPercent, 0f..60f) { v ->
                    vm.update { it.copy(bottomOffsetPercent = v) }
                }
                OutlinedButton(onClick = { vm.update { it.copy(horizontalOffsetPercent = 0f, bottomOffsetPercent = 8f) } }) {
                    Text("Đặt lại vị trí mặc định")
                }
                LabeledSwitch(
                    label = "Luôn hiện khung phụ đề",
                    description = "Giữ các câu gần nhất trên màn hình, không tự xoá khi im lặng.",
                    checked = s.alwaysShowBox,
                    onChange = { v -> vm.update { it.copy(alwaysShowBox = v) } },
                )
                if (!s.alwaysShowBox) {
                    LabeledSlider("Tự xoá sau im lặng", "%.1f giây".format(s.clearAfterMs / 1000f), s.clearAfterMs / 1000f, 2f..6f) { v ->
                        vm.update { it.copy(clearAfterMs = (v * 1000).toLong()) }
                    }
                }
                LabeledSwitch(
                    label = "Hiện câu gốc phía trên bản dịch",
                    checked = s.showSourceText,
                    onChange = { v -> vm.update { it.copy(showSourceText = v) } },
                )
                LabeledSwitch(
                    label = "Chạm xuyên qua phụ đề",
                    description = "Bật để chạm được vào video nằm dưới khung phụ đề. Android bắt buộc khung phải hơi trong suốt (80%) khi bật.",
                    checked = s.touchThrough,
                    onChange = { v -> vm.update { it.copy(touchThrough = v) } },
                )
                LabeledSwitch(
                    label = "Hiện phụ đề tạm (partial)",
                    description = "Hiện mờ trong lúc người nói chưa dứt câu. Tự bỏ qua khi máy xử lý không kịp.",
                    checked = s.showPartials,
                    onChange = { v -> vm.update { it.copy(showPartials = v) } },
                )
            }

            SectionCard("Server dịch trên máy tính") {
                LabeledSwitch(
                    label = "Dịch bằng server trên máy tính",
                    description = "LLM chạy trên máy tính cùng mạng (thư mục server/), dịch tự nhiên hơn ML Kit. " +
                        "Phụ đề tạm vẫn dịch bằng ML Kit; chưa thấy server hoặc mất kết nối thì tự dùng ML Kit. " +
                        "Áp dụng từ phiên sau.",
                    checked = s.useTranslationServer,
                    onChange = { v -> vm.update { it.copy(useTranslationServer = v) } },
                )
                if (s.useTranslationServer) {
                    var address by remember(s.translationServer) { mutableStateOf(s.translationServer) }
                    if (s.translationServer.isBlank()) {
                        val found by vm.discoveredServer.collectAsStateWithLifecycle()
                        Text(
                            found?.let { "Đã tìm thấy: ${it.name} (${it.url.removePrefix("http://")})" + (it.model?.let { m -> " · $m" } ?: "") }
                                ?: "Đang tìm server trong mạng… Hãy chạy server/run.sh trên máy tính và dùng chung mạng Wi-Fi.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Địa chỉ nhập tay (tuỳ chọn)") },
                        placeholder = { Text("Để trống = tự tìm, vd. 192.168.1.5:8765") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = { vm.saveTranslationServer(address) }) { Text("Lưu và kiểm tra kết nối") }
                    serverCheck?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            SectionCard("Tải xuống") {
                LabeledSwitch(
                    label = "Chỉ tải qua Wi-Fi",
                    description = "Áp dụng cho model nhận dạng và model dịch.",
                    checked = s.wifiOnlyDownloads,
                    onChange = { v -> vm.update { it.copy(wifiOnlyDownloads = v) } },
                )
                Text("Tải trước model dịch ${Languages.commonSources.joinToString("/") { it.uppercase() }} → ${s.targetLang.uppercase()} để xem phim không cần mạng.")
                OutlinedButton(onClick = vm::prefetchTranslationModels) { Text("Tải trước model dịch") }
                prefetch?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            SectionCard("Model nhận dạng đã tải") {
                AsrModel.entries.filter { it in readyModels }.forEach { size ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(size.label, Modifier.weight(1f))
                        TextButton(onClick = { vm.deleteModel(size) }) { Text("Xoá") }
                    }
                }
                if (readyModels.isEmpty()) Text("Chưa có model nào.")
                Text(
                    "Nếu app báo model lỗi, hãy xoá rồi tải lại ở màn hình chính.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard("Quyền riêng tư") {
                Text(
                    "Âm thanh được xử lý hoàn toàn trên điện thoại (Whisper qua sherpa-onnx) và không gửi đi đâu. " +
                        "Phụ đề dịch bằng Google ML Kit trên máy; nếu bạn nhập server dịch, câu thoại đã nhận dạng " +
                        "(chữ, không phải âm thanh) được gửi tới máy tính đó trong mạng nội bộ. App dùng mạng để tải model; " +
                        "thư viện ML Kit có thể gửi số liệu sử dụng theo chính sách của Google.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
