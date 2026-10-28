package vn.livesub.asr

import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import vn.livesub.translate.TextLanguageIdentifier
import java.io.Closeable

/** Một câu (hoặc nửa câu) đã nhận dạng, chưa dịch. */
data class RecognizedSegment(
    val id: Long,
    val isFinal: Boolean,
    val lang: String,
    val text: String,
)

/**
 * VAD (Silero) -> engine nhận dạng (Whisper/SenseVoice/ReazonSpeech) -> theo dõi ngôn ngữ -> làm sạch câu.
 *
 * - Final: khi VAD chốt hết câu (im lặng >= minSilenceDuration hoặc câu quá dài).
 * - Partial: trong lúc người nói chưa dứt, cứ ~[partialIntervalMs] giải mã lại
 *   phần âm thanh từ đầu câu (các engine dùng ở đây không phải mô hình streaming).
 *
 * Mọi hàm gọi từ một dispatcher đơn luồng vì VAD/engine không thread-safe.
 */
class SpeechPipeline(
    files: AsrModelFiles,
    private val asr: SpeechEngine,
    private val tracker: LanguageTracker,
    private val textLangId: TextLanguageIdentifier,
    var partialsEnabled: Boolean,
    private val partialIntervalMs: Long = 1_000,
) : Closeable {

    private val vad = Vad(
        assetManager = null,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = files.vad.absolutePath,
                threshold = 0.5f,
                // Khoảng lặng ~0,45 s coi như hết câu: đủ ngắn cho phụ đề, đủ dài để không cắt giữa câu.
                minSilenceDuration = 0.45f,
                minSpeechDuration = 0.25f,
                windowSize = VAD_WINDOW,
                // Câu dài hơn bị cắt để phụ đề không trễ quá và vừa khung chữ.
                maxSpeechDuration = 6f,
            ),
            sampleRate = SpeechEngine.SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    private val _segments = MutableSharedFlow<RecognizedSegment>(extraBufferCapacity = 32)
    val segments: SharedFlow<RecognizedSegment> = _segments

    private val window = FloatArray(VAD_WINDOW)
    private var windowFill = 0
    private var speech = FloatArray(SpeechEngine.SAMPLE_RATE * 10)
    private var speechLen = 0
    private var segmentId = 0L
    private var lastPartialAt = 0L

    /** Thời gian ASR / thời lượng audio (trung bình trượt), đo trên chính máy này. */
    private var realTimeFactor = 0.3f

    /**
     * Đưa một chunk PCM16 vào pipeline.
     * @param allowPartial false khi đang tồn đọng audio (máy xử lý không kịp) để ưu tiên câu final.
     */
    suspend fun accept(chunk: ShortArray, allowPartial: Boolean) {
        for (s in chunk) {
            window[windowFill++] = s / 32768f
            if (windowFill == VAD_WINDOW) {
                vad.acceptWaveform(window.copyOf())
                windowFill = 0
                if (vad.isSpeechDetected()) appendSpeech(window)
            }
        }
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            speechLen = 0
            emitFinal(segment.samples)
        }
        val now = SystemClock.elapsedRealtime()
        // Partial phải giải mã lại cả đoạn từ đầu câu: chỉ làm khi ước tính xong đủ nhanh,
        // nếu không nó chặn luồng ASR và làm câu final ra trễ.
        val partialCostMs = realTimeFactor * speechLen * 1000f / SpeechEngine.SAMPLE_RATE
        if (partialsEnabled && allowPartial && vad.isSpeechDetected() &&
            speechLen >= SpeechEngine.SAMPLE_RATE * 4 / 5 && now - lastPartialAt >= partialIntervalMs &&
            partialCostMs <= MAX_PARTIAL_COST_MS
        ) {
            lastPartialAt = now
            emitPartial(speech.copyOf(speechLen))
        }
    }

    private suspend fun emitFinal(samples: FloatArray) {
        val sec = samples.size.toFloat() / SpeechEngine.SAMPLE_RATE
        val id = ++segmentId
        if (sec < 0.3f) return

        val t0 = SystemClock.elapsedRealtime()
        val manual = tracker.manuallyLocked
        // Câu final luôn giải mã tự đoán (trừ khi khoá tay) để có tín hiệu theo dõi ngôn ngữ;
        // chỉ giải mã lại khi Whisper đoán lệch ngôn ngữ phiên.
        var result = asr.transcribe(samples, if (manual) tracker.forcedLanguage() else "")
        // Model báo là nhạc/tiếng động và chữ rất ngắn: gần như chắc chắn là rác.
        if (HallucinationFilter.isLikelyHallucination(result.text) ||
            HallucinationFilter.isImplausible(result.text, sec) ||
            (result.nonSpeech && result.text.length <= 8)
        ) {
            _segments.emit(RecognizedSegment(id, true, result.lang, ""))
            return
        }
        var lang = result.lang
        val t1 = SystemClock.elapsedRealtime()
        updateRealTimeFactor(t1 - t0, samples.size)
        if (!manual) {
            val guess = textLangId.identify(result.text)
            when (val d = tracker.observe(result.lang, sec, voteable = guess != null && !result.nonSpeech)) {
                is LanguageTracker.Decision.Accept -> lang = d.lang
                is LanguageTracker.Decision.Redecode -> {
                    result = asr.transcribe(samples, d.lang)
                    lang = d.lang
                }
            }
        }
        val t2 = SystemClock.elapsedRealtime()
        Log.d(TAG, "final #$id ${"%.1f".format(sec)}s asr=${t1 - t0}ms lid+redecode=${t2 - t1}ms " +
            "rtf=${"%.2f".format(realTimeFactor)} lang=$lang conf=${"%.2f".format(tracker.confidence)} text=${result.text}")
        val text = if (HallucinationFilter.isLikelyHallucination(result.text)) "" else TextCleaner.clean(result.text, lang, isFinal = true)
        _segments.emit(RecognizedSegment(id, true, lang, text))
    }

    private suspend fun emitPartial(samples: FloatArray) {
        val t0 = SystemClock.elapsedRealtime()
        val result = asr.transcribe(samples, tracker.forcedLanguage())
        val elapsed = SystemClock.elapsedRealtime() - t0
        updateRealTimeFactor(elapsed, samples.size)
        Log.d(TAG, "partial #${segmentId + 1} ${samples.size / 16}ms asr=${elapsed}ms")
        if (HallucinationFilter.isLikelyHallucination(result.text) || result.nonSpeech) return
        val lang = tracker.sessionLang ?: result.lang
        val text = TextCleaner.clean(result.text, lang, isFinal = false)
        if (text.isNotEmpty()) _segments.emit(RecognizedSegment(segmentId + 1, false, lang, text))
    }

    private fun updateRealTimeFactor(elapsedMs: Long, numSamples: Int) {
        val audioMs = numSamples * 1000f / SpeechEngine.SAMPLE_RATE
        if (audioMs < 500f) return
        realTimeFactor = 0.7f * realTimeFactor + 0.3f * (elapsedMs / audioMs)
    }

    private fun appendSpeech(samples: FloatArray) {
        // Engine xử lý tối đa ~30 s; câu bị VAD cắt ở 6 s nên 10 s là dư.
        if (speechLen + samples.size > speech.size) return
        samples.copyInto(speech, speechLen)
        speechLen += samples.size
    }

    override fun close() {
        vad.release()
        asr.close()
    }

    companion object {
        private const val TAG = "SpeechPipeline"
        private const val VAD_WINDOW = 512
        private const val MAX_PARTIAL_COST_MS = 700f
    }
}
