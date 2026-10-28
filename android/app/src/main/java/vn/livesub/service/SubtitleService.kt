package vn.livesub.service

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.livesub.LiveSubApp
import vn.livesub.MainActivity
import vn.livesub.R
import vn.livesub.asr.LanguageTracker
import vn.livesub.asr.RecognizedSegment
import vn.livesub.asr.SpeechEngine
import vn.livesub.asr.SpeechPipeline
import vn.livesub.asr.TextCleaner
import vn.livesub.audio.AudioCaptureException
import vn.livesub.audio.PlaybackAudioCapture
import vn.livesub.audio.SilenceMonitor
import vn.livesub.data.AppSettings
import vn.livesub.overlay.SubtitleEvent
import vn.livesub.overlay.SubtitleOverlay
import vn.livesub.translate.MlKitTranslator
import vn.livesub.translate.ServerTranslator
import vn.livesub.translate.SubtitleTranslator
import vn.livesub.translate.TextLanguageIdentifier
import vn.livesub.translate.TranslationResult
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service giữ MediaProjection và chạy toàn bộ pipeline:
 * bắt âm thanh -> VAD/Whisper -> dịch ML Kit -> overlay.
 */
class SubtitleService : LifecycleService() {

    private val container by lazy { (application as LiveSubApp).container }
    private val mainHandler = Handler(Looper.getMainLooper())

    // VAD/Whisper không thread-safe: dồn hết vào một luồng riêng.
    private val asrExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "asr").apply { priority = Thread.MAX_PRIORITY } }
    private val asrDispatcher = asrExecutor.asCoroutineDispatcher()

    private var projection: MediaProjection? = null
    private var overlay: SubtitleOverlay? = null
    private var pipeline: SpeechPipeline? = null
    private var translator: SubtitleTranslator? = null
    private var textLangId: TextLanguageIdentifier? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionJob: Job? = null
    private val tracker = LanguageTracker()

    @Volatile private var settings = AppSettings()
    private val queuedChunks = AtomicInteger(0)
    private var stopping = false
    private var translationNoticeShown = false
    /** Số câu gần nhất toàn katakana khi dùng model chỉ biết tiếng Nhật. */
    private val recentKatakana = ArrayDeque<Boolean>()
    private var wrongLanguageWarned = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                if (sessionJob?.isActive == true) return START_NOT_STICKY
                if (data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startSession(resultCode, data)
            }
            ACTION_STOP -> stopSession(null)
            ACTION_TOGGLE_LOCK -> lifecycleScope.launch(asrDispatcher) { toggleLock() }
        }
        // Không tự khởi động lại: token MediaProjection không dùng lại được sau khi process chết.
        return START_NOT_STICKY
    }

    private fun startSession(resultCode: Int, data: Intent) {
        stopping = false
        SessionState.reset()
        SessionState.update { it.copy(phase = SessionPhase.STARTING) }

        // Android 14+: phải vào foreground với type mediaProjection TRƯỚC khi getMediaProjection().
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSession(Notice(Notice.Level.ERROR, "Không khởi động được dịch vụ nền: ${e.message}"))
            return
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull()
        if (mp == null) {
            stopSession(Notice(Notice.Level.ERROR, "Không nhận được quyền chia sẻ màn hình. Hãy bấm Bắt đầu lại."))
            return
        }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // Người dùng bấm dừng chia sẻ trên thanh trạng thái / hệ thống thu hồi quyền.
                stopSession(Notice(Notice.Level.INFO, "Đã dừng chia sẻ màn hình."))
            }
        }, mainHandler)
        projection = mp

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveSub:session")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        sessionJob = lifecycleScope.launch { runSession(mp) }
    }

    private suspend fun runSession(mp: MediaProjection) {
        settings = container.settings.settings.first()
        val s = settings
        val modelSize = s.asrModel
        if (!container.models.isReady(modelSize)) {
            stopSession(Notice(Notice.Level.ERROR, "Chưa tải model nhận dạng ${modelSize.label}."))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSession(Notice(Notice.Level.ERROR, "Chưa cấp quyền hiển thị trên ứng dụng khác."))
            return
        }

        val ov = SubtitleOverlay(
            context = this,
            onToggleLock = { lifecycleScope.launch(asrDispatcher) { toggleLock() } },
            onClose = { stopSession(Notice(Notice.Level.INFO, "Đã tắt phụ đề.")) },
            onPositionChanged = { bottomPct, horizontalPct ->
                lifecycleScope.launch {
                    container.settings.update {
                        it.copy(bottomOffsetPercent = bottomPct, horizontalOffsetPercent = horizontalPct)
                    }
                }
            },
        )
        overlay = ov
        ov.show(s)
        ov.showNotice("Đang nạp model nhận dạng…")

        val modelLangs = modelSize.languages
        when {
            // Model một ngôn ngữ (ReazonSpeech): ngôn ngữ phiên cố định theo model.
            modelLangs != null && modelLangs.size == 1 -> tracker.lock(modelLangs.single())
            s.sourceLang != AppSettings.AUTO -> {
                tracker.lock(s.sourceLang)
                if (modelLangs != null && s.sourceLang !in modelLangs) {
                    showNotice(Notice(Notice.Level.WARNING,
                        "${modelSize.label} không hiểu ${s.sourceLang.uppercase()}. Hãy chọn model Whisper cho ngôn ngữ này."))
                }
            }
        }
        val langId = TextLanguageIdentifier().also { textLangId = it }
        val mlKit = MlKitTranslator(lifecycleScope, s.wifiOnlyDownloads)
        translator = ServerTranslator.normalizeUrl(s.translationServer)
            ?.let { ServerTranslator(it, fallback = mlKit) }
            ?: mlKit

        val p = try {
            withContext(asrDispatcher) {
                val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
                val files = container.models.files(modelSize)
                SpeechPipeline(files, SpeechEngine.create(modelSize, files, threads), tracker, langId, s.showPartials)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "load model failed", e)
            stopSession(Notice(Notice.Level.ERROR, "Không nạp được model (file hỏng?). Hãy xoá và tải lại model trong Cài đặt."))
            return
        }
        pipeline = p
        ov.showNotice(null)
        publishLanguage()
        SessionState.update { it.copy(phase = SessionPhase.RUNNING) }

        // Mọi coroutine con nằm trong coroutineScope này để Stop (huỷ sessionJob) dừng tất cả.
        coroutineScope {
            // Cập nhật giao diện overlay ngay khi người dùng đổi cài đặt.
            launch {
                container.settings.settings.collect { ns ->
                    val targetChanged = ns.targetLang != settings.targetLang
                    settings = ns
                    ov.applySettings(ns)
                    p.partialsEnabled = ns.showPartials
                    if (targetChanged) tracker.sessionLang?.let { translator?.prepare(it, ns.targetLang) }
                }
            }

            launch { p.segments.collect { onSegment(it) } }

            val audio = Channel<ShortArray>(Channel.UNLIMITED)
            launch(asrDispatcher) {
                for (chunk in audio) {
                    val remaining = queuedChunks.decrementAndGet()
                    try {
                        p.accept(chunk, allowPartial = remaining == 0)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.e(TAG, "pipeline error", e)
                        showNotice(Notice(Notice.Level.WARNING, "Lỗi nhận dạng: ${e.message}"))
                    }
                }
            }

            captureAudio(mp, audio)
        }
    }

    private suspend fun captureAudio(mp: MediaProjection, audio: Channel<ShortArray>) {
        val silence = SilenceMonitor()
        val audioManager = getSystemService(AudioManager::class.java)
        var laggingWarned = false
        try {
            PlaybackAudioCapture(mp).chunks().collect { chunk ->
                if (silence.onChunk(chunk, CHUNK_MS)) onSilenceStateChanged(silence.state, audioManager.isMusicActive)

                // Máy xử lý không kịp: bỏ bớt audio thay vì để độ trễ phụ đề tăng mãi.
                if (queuedChunks.get() >= MAX_QUEUED_CHUNKS) {
                    if (!laggingWarned) {
                        laggingWarned = true
                        showNotice(Notice(Notice.Level.WARNING, "Máy xử lý không kịp. Hãy chọn model nhỏ hơn (tiny) hoặc tắt phụ đề tạm thời (partial)."))
                    }
                    return@collect
                }
                queuedChunks.incrementAndGet()
                audio.send(chunk)
            }
        } catch (e: AudioCaptureException) {
            stopSession(Notice(Notice.Level.ERROR, e.message ?: "Lỗi bắt âm thanh"))
        } finally {
            audio.close()
        }
    }

    private fun onSilenceStateChanged(state: SilenceMonitor.State, musicActive: Boolean) {
        val notice = when {
            state != SilenceMonitor.State.LONG_SILENCE -> null
            // Hệ thống báo đang phát nhạc/video nhưng ta chỉ nhận được số 0 => bị chặn capture.
            musicActive -> Notice(
                Notice.Level.WARNING,
                "Có âm thanh đang phát nhưng không bắt được: ứng dụng/nội dung này chặn ghi âm (DRM). Thử mở bằng Chrome hoặc trình duyệt khác.",
            )
            else -> Notice(Notice.Level.INFO, "Không có âm thanh đang phát (video tạm dừng hoặc tab bị tắt tiếng).")
        }
        showNotice(notice)
    }

    private suspend fun onSegment(seg: RecognizedSegment) {
        publishLanguage()
        if (seg.isFinal && seg.text.isNotEmpty()) checkWrongLanguage(seg.text)
        val type = if (seg.isFinal) SubtitleEvent.Type.FINAL else SubtitleEvent.Type.PARTIAL
        val ov = overlay ?: return
        if (seg.text.isEmpty()) {
            ov.render(SubtitleEvent(seg.id, type, seg.lang, "", null))
            return
        }
        val target = settings.targetLang
        // ML Kit/Google không có tiếng Quảng Đông riêng: dịch qua tiếng Trung (chữ Hán).
        val source = if (seg.lang == "yue") "zh" else seg.lang
        val t0 = SystemClock.elapsedRealtime()
        val result = translator?.translate(seg.text, source, target, seg.isFinal)
            ?: TranslationResult.Failed("Chưa sẵn sàng")
        if (seg.isFinal) {
            val out = (result as? TranslationResult.Success)?.text ?: result.toString()
            Log.d(TAG, "translate #${seg.id} ${seg.lang}->$target ${SystemClock.elapsedRealtime() - t0}ms out=$out")
        }

        // Bản dịch không có chữ nào (ML Kit đôi khi trả về ".") thì hiện câu gốc thay vì dòng trống.
        val translated = (result as? TranslationResult.Success)?.text?.takeIf { out -> out.any { it.isLetter() } }
        ov.render(SubtitleEvent(seg.id, type, seg.lang, seg.text, translated))
        if (!seg.isFinal) return
        // Thông báo trạng thái dịch chỉ cập nhật theo câu final để không nhấp nháy.
        when (result) {
            is TranslationResult.Preparing -> showTranslationNotice(Notice(Notice.Level.INFO,
                "Đang tải model dịch ${result.sourceLang.uppercase()}→${result.targetLang.uppercase()} (~30 MB), tạm hiện câu gốc."))
            is TranslationResult.Unsupported -> showTranslationNotice(Notice(Notice.Level.INFO,
                "Chưa hỗ trợ dịch từ ${result.sourceLang.uppercase()}, hiện câu gốc."))
            is TranslationResult.Failed -> showTranslationNotice(Notice(Notice.Level.WARNING, result.message))
            is TranslationResult.Success ->
                result.note?.let { showTranslationNotice(Notice(Notice.Level.INFO, it)) } ?: clearTranslationNotice()
            TranslationResult.SameLanguage -> clearTranslationNotice()
        }
    }

    /**
     * Model chỉ biết tiếng Nhật (ReazonSpeech) gặp video tiếng khác sẽ phiên âm thành katakana
     * vô nghĩa. Nếu 3/4 câu gần nhất như vậy thì nhắc người dùng đổi model.
     */
    private fun checkWrongLanguage(text: String) {
        if (wrongLanguageWarned || settings.asrModel.languages != setOf("ja")) return
        recentKatakana.addLast(TextCleaner.isMostlyKatakana(text))
        while (recentKatakana.size > 4) recentKatakana.removeFirst()
        if (recentKatakana.size == 4 && recentKatakana.count { it } >= 3) {
            wrongLanguageWarned = true
            showNotice(Notice(Notice.Level.WARNING,
                "Video có vẻ không nói tiếng Nhật. ${settings.asrModel.label} chỉ hiểu tiếng Nhật, hãy đổi sang SenseVoice hoặc Whisper."))
        }
    }

    private fun toggleLock() {
        if (tracker.manuallyLocked) {
            tracker.unlock()
        } else {
            val lang = tracker.sessionLang ?: SessionState.status.value.sourceLang
            if (lang.isNullOrEmpty()) {
                showNotice(Notice(Notice.Level.INFO, "Chưa nhận diện được ngôn ngữ để khoá."))
                return
            }
            tracker.lock(lang)
        }
        publishLanguage()
    }

    private var lastPublished: Triple<String?, Boolean, Boolean>? = null

    /** Đồng bộ ngôn ngữ nguồn hiện tại lên UI, overlay và notification. */
    private fun publishLanguage() {
        val confirmed = tracker.sessionLang != null
        val lang = tracker.sessionLang ?: SessionState.status.value.sourceLang
        val snapshot = Triple(lang, confirmed, tracker.manuallyLocked)
        if (snapshot == lastPublished) return
        lastPublished = snapshot
        SessionState.update { it.copy(sourceLang = lang, sourceConfirmed = confirmed, manuallyLocked = tracker.manuallyLocked) }
        tracker.sessionLang?.let { translator?.prepare(it, settings.targetLang) }
        mainHandler.post {
            overlay?.setLanguage(lang, confirmed, tracker.manuallyLocked)
            updateNotification()
        }
    }

    private fun showNotice(notice: Notice?) {
        if (notice == null || !(notice.text.contains("model dịch") || notice.text.contains("server dịch"))) {
            translationNoticeShown = false
        }
        SessionState.notify(notice)
        mainHandler.post { overlay?.showNotice(notice?.text) }
    }

    private fun showTranslationNotice(notice: Notice) {
        translationNoticeShown = true
        showNotice(notice)
    }

    private fun clearTranslationNotice() {
        if (!translationNoticeShown) return
        translationNoticeShown = false
        showNotice(null)
    }

    private fun stopSession(notice: Notice?) {
        // Có thể bị gọi lại từ MediaProjection.Callback.onStop sau khi chính ta gọi projection.stop().
        if (stopping) return
        stopping = true
        sessionJob?.cancel()
        sessionJob = null
        mainHandler.post {
            overlay?.hide()
            overlay = null
        }
        projection?.let { p ->
            projection = null
            runCatching { p.stop() }
        }
        val p = pipeline
        pipeline = null
        // Giải phóng native model trên đúng luồng ASR, sau khi luồng xử lý xong chunk hiện tại.
        if (p != null) asrExecutor.execute { p.close() }
        translator?.close()
        translator = null
        textLangId?.close()
        textLangId = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        SessionState.reset(notice)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Xoay ngang/dọc khi xem phim: tính lại kích thước và vị trí khung phụ đề.
        overlay?.onScreenChanged()
    }

    override fun onDestroy() {
        if (!stopping) stopSession(null)
        asrExecutor.shutdown()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val status = SessionState.status.value
        val src = when {
            status.sourceLang == null -> "Đang nhận diện…"
            status.manuallyLocked -> "${status.sourceLang.uppercase()} (đã khoá)"
            status.sourceConfirmed -> "${status.sourceLang.uppercase()} (tự động)"
            else -> "${status.sourceLang.uppercase()}?"
        }
        val text = "Nguồn: $src → ${settings.targetLang.uppercase()}"
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, LiveSubApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Phụ đề trực tiếp đang chạy")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
            .addAction(0, "Dừng", servicePendingIntent(ACTION_STOP, 1))
            .addAction(0, if (status.manuallyLocked) "Mở khoá ngôn ngữ" else "Khoá ngôn ngữ", servicePendingIntent(ACTION_TOGGLE_LOCK, 2))
            .build()
    }

    private fun updateNotification() {
        if (sessionJob == null) return
        // Thiếu quyền POST_NOTIFICATIONS thì notify() bị bỏ qua, service vẫn chạy bình thường.
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun servicePendingIntent(action: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode, Intent(this, SubtitleService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "SubtitleService"
        private const val NOTIFICATION_ID = 42
        private const val CHUNK_MS = 100L
        /** ~8 giây audio tồn đọng. */
        private const val MAX_QUEUED_CHUNKS = 80

        const val ACTION_START = "vn.livesub.START"
        const val ACTION_STOP = "vn.livesub.STOP"
        const val ACTION_TOGGLE_LOCK = "vn.livesub.TOGGLE_LOCK"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, SubtitleService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SubtitleService::class.java).setAction(ACTION_STOP))
        }
    }
}
