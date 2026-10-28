package vn.livesub.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import vn.livesub.CrashLog
import vn.livesub.asr.ModelDownloadState
import vn.livesub.data.AppSettings
import vn.livesub.data.AsrModel
import vn.livesub.data.Languages
import vn.livesub.service.Notice
import vn.livesub.service.SessionPhase
import vn.livesub.service.SubtitleService

private enum class PermissionDialog { OVERLAY, AUDIO_RATIONALE, AUDIO_SETTINGS }

/** Giữ tham chiếu tới hàm mới nhất để callback của launcher gọi lại được. */
private class Ref<T>(var value: T)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val settings by vm.settings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val readyModels by vm.readyModels.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var audioGranted by remember { mutableStateOf(context.hasPermission(Manifest.permission.RECORD_AUDIO)) }
    var notifGranted by remember { mutableStateOf(context.notificationsAllowed()) }
    var notifAsked by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<PermissionDialog?>(null) }
    var lastCrash by remember { mutableStateOf(CrashLog.read(context)) }

    // Người dùng quay lại từ màn hình cài đặt hệ thống: kiểm tra lại quyền.
    LifecycleResumeEffect(Unit) {
        overlayGranted = Settings.canDrawOverlays(context)
        audioGranted = context.hasPermission(Manifest.permission.RECORD_AUDIO)
        notifGranted = context.notificationsAllowed()
        onPauseOrDispose { }
    }

    fun toast(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    val proceed = remember { Ref<() -> Unit> {} }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            SubtitleService.start(context, r.resultCode, data)
        } else {
            toast("Bạn đã từ chối chia sẻ màn hình nên app không bắt được âm thanh.")
        }
    }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        audioGranted = granted
        when {
            granted -> proceed.value()
            // Từ chối vĩnh viễn ("Không hỏi lại"): chỉ còn cách mở cài đặt app.
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
                dialog = PermissionDialog.AUDIO_SETTINGS
            else -> toast("Cần quyền ghi âm để bắt âm thanh video.")
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifGranted = granted
        if (!granted) toast("Không có thông báo: vẫn chạy được, dừng bằng nút Dừng trong app.")
        proceed.value()
    }

    // Chuỗi bước trước khi bắt đầu; mỗi launcher xong sẽ gọi lại để sang bước kế.
    proceed.value = {
        when {
            settings.asrModel !in readyModels -> toast("Hãy tải model nhận dạng ${settings.asrModel.label} trước.")
            !Settings.canDrawOverlays(context) -> dialog = PermissionDialog.OVERLAY
            !context.hasPermission(Manifest.permission.RECORD_AUDIO) ->
                if (activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    dialog = PermissionDialog.AUDIO_RATIONALE
                } else {
                    audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            Build.VERSION.SDK_INT >= 33 && !context.notificationsAllowed() && !notifAsked -> {
                notifAsked = true
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> projectionLauncher.launch(context.screenCaptureIntent())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LiveSub – Phụ đề trực tiếp") },
                actions = { TextButton(onClick = onOpenSettings) { Text("Cài đặt") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            lastCrash?.let { crash ->
                SectionCard("Lần trước app bị dừng đột ngột") {
                    Text(
                        "Bấm Sao chép rồi gửi nội dung này cho người phát triển để sửa lỗi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(crash.lines().take(25).joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("LiveSub crash", crash))
                            toast("Đã sao chép nội dung lỗi.")
                        }) { Text("Sao chép") }
                        TextButton(onClick = {
                            CrashLog.dismiss(context)
                            lastCrash = null
                        }) { Text("Đóng") }
                    }
                }
            }

            SessionCard(
                phase = session.phase,
                sourceLang = session.sourceLang,
                confirmed = session.sourceConfirmed,
                locked = session.manuallyLocked,
                notice = session.notice,
                onStart = { proceed.value() },
                onStop = { SubtitleService.stop(context) },
            )

            SectionCard("Ngôn ngữ") {
                OptionDropdown(
                    label = "Dịch sang",
                    selected = settings.targetLang,
                    options = Languages.all.map { it.code to it.name },
                    onSelect = { code -> vm.update { it.copy(targetLang = code) } },
                )
                OptionDropdown(
                    label = "Ngôn ngữ trong video",
                    selected = settings.sourceLang,
                    options = listOf(AppSettings.AUTO to "Tự động nhận diện") +
                        Languages.all.map { it.code to "Khoá: ${it.name}" },
                    onSelect = { code -> vm.update { it.copy(sourceLang = code) } },
                )
                Text(
                    "Dịch bằng Google ML Kit trên máy: miễn phí, chạy không cần mạng sau khi tải gói ngôn ngữ (~30 MB/ngôn ngữ).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ModelCard(
                selected = settings.asrModel,
                ready = readyModels,
                download = download,
                onSelect = { size -> vm.update { it.copy(asrModel = size) } },
                onDownload = vm::downloadModel,
            )

            SectionCard("Quyền cần cấp") {
                PermissionRow("Hiển thị trên ứng dụng khác (overlay)", overlayGranted) { dialog = PermissionDialog.OVERLAY }
                PermissionRow("Ghi âm (để bắt âm thanh hệ thống)", audioGranted) {
                    audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    PermissionRow("Thông báo (nút Dừng nhanh)", notifGranted) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                Text(
                    "Quyền chia sẻ màn hình được hỏi mỗi lần bấm Bắt đầu. Chọn \"Toàn bộ màn hình\" hoặc \"Một ứng dụng\" " +
                        "(app đang phát video, vd. Chrome) đều được. App chỉ dùng nó để bắt âm thanh, không ghi hình; " +
                        "âm thanh được xử lý ngay trên máy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    when (dialog) {
        PermissionDialog.OVERLAY -> InfoDialog(
            title = "Cho phép hiển thị phụ đề nổi",
            text = "Ở màn hình tiếp theo, tìm LiveSub và bật \"Cho phép hiển thị trên các ứng dụng khác\", rồi quay lại app.",
            confirm = "Mở cài đặt",
            onConfirm = {
                dialog = null
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                )
            },
            onDismiss = { dialog = null },
        )
        PermissionDialog.AUDIO_RATIONALE -> InfoDialog(
            title = "Cần quyền ghi âm",
            text = "Android yêu cầu quyền ghi âm để bắt âm thanh video đang phát. App không dùng micro, mọi xử lý diễn ra trên máy.",
            confirm = "Cấp quyền",
            onConfirm = {
                dialog = null
                audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onDismiss = { dialog = null },
        )
        PermissionDialog.AUDIO_SETTINGS -> InfoDialog(
            title = "Quyền ghi âm đang bị chặn",
            text = "Bạn đã chọn không hỏi lại. Vào Cài đặt > Quyền > Micrô và chọn \"Chỉ cho phép khi dùng ứng dụng\".",
            confirm = "Mở cài đặt app",
            onConfirm = {
                dialog = null
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                )
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun SessionCard(
    phase: SessionPhase,
    sourceLang: String?,
    confirmed: Boolean,
    locked: Boolean,
    notice: Notice?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    SectionCard("Phiên phụ đề") {
        val running = phase != SessionPhase.IDLE
        Button(
            onClick = if (running) onStop else onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = if (running) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            Text(if (running) "Dừng" else "Bắt đầu", style = MaterialTheme.typography.titleMedium)
        }
        val status = when (phase) {
            SessionPhase.IDLE -> "Chưa chạy"
            SessionPhase.STARTING -> "Đang khởi động…"
            SessionPhase.RUNNING -> when {
                sourceLang == null -> "Đang nghe, chờ giọng nói để nhận diện ngôn ngữ…"
                locked -> "Nguồn: ${Languages.nameOf(sourceLang)} (${sourceLang.uppercase()}, đã khoá)"
                confirmed -> "Nguồn: ${Languages.nameOf(sourceLang)} (${sourceLang.uppercase()}, tự động)"
                else -> "Đang đoán: ${sourceLang.uppercase()}…"
            }
        }
        Text(status)
        if (notice != null) {
            val color = when (notice.level) {
                Notice.Level.ERROR -> MaterialTheme.colorScheme.error
                Notice.Level.WARNING -> Color(0xFFB26A00)
                Notice.Level.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(notice.text, color = color, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ModelCard(
    selected: AsrModel,
    ready: Set<AsrModel>,
    download: ModelDownloadState,
    onSelect: (AsrModel) -> Unit,
    onDownload: (AsrModel) -> Unit,
) {
    SectionCard("Model nhận dạng giọng nói") {
        AsrModel.entries.forEach { size ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = size == selected, role = Role.RadioButton, onClick = { onSelect(size) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = size == selected, onClick = null)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(size.label)
                    Text(
                        size.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (size in ready) "Đã tải" else "Chưa tải · ~${size.approxMb} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (size !in ready && download !is ModelDownloadState.Downloading) {
                    OutlinedButton(onClick = { onDownload(size) }) { Text("Tải") }
                }
            }
        }
        when (download) {
            is ModelDownloadState.Downloading -> {
                Text("Đang tải ${download.size.label}… ${(download.progress * 100).toInt()}%")
                LinearProgressIndicator(progress = { download.progress }, modifier = Modifier.fillMaxWidth())
            }
            is ModelDownloadState.Failed -> Text(download.message, color = MaterialTheme.colorScheme.error)
            ModelDownloadState.Idle -> Unit
        }
        Text(
            "Xem phim Nhật: thử ReazonSpeech. Ngôn ngữ khác (Hàn, Trung, Thái, Indonesia...): dùng Whisper.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (granted) "✓" else "✗", color = if (granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
        Text(label, Modifier.weight(1f).padding(start = 8.dp))
        if (!granted) TextButton(onClick = onRequest) { Text("Cấp") }
    }
}

@Composable
private fun InfoDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Để sau") } },
    )
}

private fun Context.hasPermission(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.notificationsAllowed() =
    Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS)

// Android 14+ cho chọn "Toàn bộ màn hình" hoặc "Một ứng dụng"; app chỉ dùng quyền này để
// bắt âm thanh nên chọn kiểu nào cũng được.
private fun Context.screenCaptureIntent(): Intent =
    getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
