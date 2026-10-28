package vn.livesub.asr

import android.os.SystemClock
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import vn.livesub.AppLog
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
 * VAD (Silero) -> engine nhận dạng (Whisper/ReazonSpeech) -> theo dõi ngôn ngữ -> làm sạch câu.
 *
 * - Final: khi người nói dứt câu. VAD chốt một đoạn (im lặng >= minSilenceDuration hoặc đoạn quá dài)
 *   chưa chắc là hết câu, nên chữ nhận dạng không có dấu kết câu thì giữ lại, gộp với đoạn kế tiếp
 *   rồi nhận dạng lại cả câu ([SentenceBoundary]). Quá [HOLD_SAMPLES] không có giọng nói mới thì chốt.
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
                // Khoảng lặng ~0,45 s chốt một đoạn để nhận dạng sớm; hết câu thật chưa thì do SentenceBoundary quyết định.
                minSilenceDuration = 0.45f,
                minSpeechDuration = 0.25f,
                windowSize = VAD_WINDOW,
                // Câu dài hơn bị cắt để phụ đề không trễ quá và vừa khung chữ.
                maxSpeechDuration = VAD_MAX_SPEECH_SEC,
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

    /** Phần đầu của [speech] là âm thanh đệm trước lúc VAD nhận ra tiếng nói. */
    private var speechPreRoll = 0

    /** Vị trí tuyệt đối của mẫu đầu tiên trong [speech]. */
    private var speechBase = 0L

    /** Âm thanh trước vị trí này đã được tự cắt và nhận dạng ([cutSpeech]); VAD chốt sau đó thì bỏ phần này. */
    private var consumedEnd = 0L
    private var segmentId = 0L
    private var lastPartialAt = 0L

    // Âm thanh gần nhất theo vị trí tuyệt đối, để lấy thêm đoạn đệm quanh đoạn VAD ([SpeechPadding]).
    private val history = AudioHistory(HISTORY_SAMPLES)

    /** Vị trí tuyệt đối nơi âm thanh đã lấy cho đoạn VAD trước kết thúc. */
    private var lastEnd = 0L
    private var paddingWarned = false

    // Câu đã nhận dạng nhưng chưa dứt: âm thanh (các đoạn VAD đã gộp), chữ và ngôn ngữ lần gần nhất.
    private var pending = FloatArray(0)
    private var pendingText = ""
    private var pendingLang = ""
    /** Số mẫu im lặng kể từ lần câu được giữ lại hoặc có giọng nói gần nhất. */
    private var pendingSilence = 0

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
                history.append(window, VAD_WINDOW)
                vad.acceptWaveform(window.copyOf())
                windowFill = 0
                if (vad.isSpeechDetected()) {
                    if (speechLen == 0) seedPreRoll()
                    appendSpeech(window)
                }
            }
        }
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            speechLen = 0
            speechPreRoll = 0
            onVadSegment(segment.start, segment.samples, forced = false)
        }
        // VAD không luôn cắt đoạn nói liền ở độ dài tối đa (đo được 7–15 s dù cấu hình 6 s), phụ đề sẽ đứng hình
        // cả chục giây: tự cắt ở chỗ lặng nhất gần cuối.
        if (vad.isSpeechDetected() && speechLen >= MAX_SPEECH_SAMPLES) cutSpeech()
        if (vad.isSpeechDetected()) {
            pendingSilence = 0
        } else if (pending.isNotEmpty()) {
            pendingSilence += chunk.size
            if (pendingSilence >= HOLD_SAMPLES) flushPending()
        }
        // Giọng nói đang tiếp tục đã không còn chỗ trong khung phụ đề của câu đang giữ: chốt câu cũ
        // ngay để phần đang nói hiện partial như câu mới, thay vì im lặng chờ tới lúc VAD chốt.
        if (pending.isNotEmpty() && !SentenceBoundary.canMerge(seconds(pending.size), seconds(speechLen))) flushPending()

        val now = SystemClock.elapsedRealtime()
        // Partial phải giải mã lại cả câu từ đầu: chỉ làm khi ước tính xong đủ nhanh,
        // nếu không nó chặn luồng ASR và làm câu final ra trễ.
        val partialCostMs = realTimeFactor * (pending.size + speechLen) * 1000f / SpeechEngine.SAMPLE_RATE
        // Engine nhanh (partial chỉ vài trăm ms) thì cập nhật dày hơn để chữ hiện sát lời nói; engine chậm giữ
        // nhịp [partialIntervalMs]. Giữ partial dưới 1/3 thời gian của luồng ASR để câu final không phải chờ lâu.
        val interval = (PARTIAL_DUTY_FACTOR * partialCostMs).toLong().coerceAtLeast(MIN_PARTIAL_INTERVAL_MS).coerceAtMost(partialIntervalMs)
        if (partialsEnabled && allowPartial && vad.isSpeechDetected() &&
            speechLen - speechPreRoll >= MIN_PARTIAL_SAMPLES && now - lastPartialAt >= interval &&
            partialCostMs <= MAX_PARTIAL_COST_MS
        ) {
            lastPartialAt = now
            emitPartial(pending + speech.copyOf(speechLen))
        }
    }

    /**
     * VAD vừa chốt một đoạn giọng nói: gộp với câu đang giữ (nếu có), nhận dạng cả câu,
     * rồi chốt thành final nếu người nói đã dứt câu, không thì giữ lại chờ đoạn kế tiếp.
     */
    private suspend fun onVadSegment(vadStart: Long, vadSamples: FloatArray, forced: Boolean) {
        // Phần đầu đã được tự cắt và nhận dạng rồi thì bỏ.
        val skip = SpeechCutter.skip(vadStart, vadSamples.size, consumedEnd)
        if (skip >= vadSamples.size) return
        val start = vadStart + skip
        val raw = if (skip == 0) vadSamples else vadSamples.copyOfRange(skip, vadSamples.size)
        // chunkSec là thời lượng giọng nói đoạn này; chunk là âm thanh đưa cho engine, đã đệm đầu/cuối.
        val chunkSec = seconds(raw.size)
        // Đoạn bị cắt ở độ dài tối đa (không có chỗ nghỉ): hiện ngay, không giữ chờ ghép, kẻo đứng hình.
        val cutByLimit = forced || chunkSec >= VAD_MAX_SPEECH_SEC - CUT_MARGIN_SEC
        val chunk = padded(start, raw, cutByLimit)
        // Thêm đoạn này sẽ vượt khung phụ đề: chốt câu cũ trước, đoạn này mở câu mới.
        if (pending.isNotEmpty() && !SentenceBoundary.canMerge(seconds(pending.size), chunkSec)) flushPending()
        if (chunkSec < 0.3f) {
            // Câu đang giữ (nếu có) vẫn chờ tiếp; đoạn quá ngắn này chỉ là tiếng động.
            if (pending.isEmpty()) segmentId++
            return
        }

        val samples = if (pending.isEmpty()) chunk else pending + chunk
        val sec = seconds(samples.size)
        val t0 = SystemClock.elapsedRealtime()
        val manual = tracker.manuallyLocked
        // Luôn giải mã tự đoán (trừ khi khoá tay) để có tín hiệu theo dõi ngôn ngữ;
        // chỉ giải mã lại khi Whisper đoán lệch ngôn ngữ phiên.
        var result = asr.transcribe(samples, if (manual) tracker.forcedLanguage() else "")
        // Model báo là nhạc/tiếng động và chữ rất ngắn: gần như chắc chắn là rác.
        if (HallucinationFilter.isLikelyHallucination(result.text) ||
            HallucinationFilter.isImplausible(result.text, sec) ||
            (result.nonSpeech && result.text.length <= 8)
        ) {
            // Đoạn mới là rác: chốt phần câu đang giữ (nếu có) bằng chữ đã nhận dạng, bỏ đoạn này.
            if (pending.isNotEmpty()) flushPending() else _segments.emit(RecognizedSegment(++segmentId, true, result.lang, ""))
            return
        }
        var lang = result.lang
        val t1 = SystemClock.elapsedRealtime()
        updateRealTimeFactor(t1 - t0, samples.size)
        if (!manual) {
            val guess = textLangId.identify(result.text)
            // Chỉ tính phần giọng nói mới vào phiếu, tránh đếm trùng phần đã gộp từ trước.
            when (val d = tracker.observe(result.lang, chunkSec, voteable = guess != null && !result.nonSpeech)) {
                is LanguageTracker.Decision.Accept -> lang = d.lang
                is LanguageTracker.Decision.Redecode -> {
                    result = asr.transcribe(samples, d.lang)
                    lang = d.lang
                }
            }
        }
        val t2 = SystemClock.elapsedRealtime()
        val ended = cutByLimit || SentenceBoundary.hasEnded(result.text, asr.hasPunctuation)
        AppLog.d(TAG) {
            "chunk #${segmentId + 1} ${"%.1f".format(sec)}s (mới ${"%.1f".format(chunkSec)}s) asr=${t1 - t0}ms " +
                "lid+redecode=${t2 - t1}ms rtf=${"%.2f".format(realTimeFactor)} lang=$lang conf=${"%.2f".format(tracker.confidence)} " +
                "cut=$cutByLimit ${if (ended) "final" else "giữ"} text=${result.text}"
        }

        if (ended) {
            resetPending()
            emitFinal(result.text, lang)
        } else {
            pending = samples
            pendingText = result.text
            pendingLang = lang
            pendingSilence = 0
            // Cho người xem thấy chữ ngay (màu xám) trong lúc chờ biết câu dứt chưa.
            val text = TextCleaner.clean(result.text, lang, isFinal = false)
            if (text.isNotEmpty()) _segments.emit(RecognizedSegment(segmentId + 1, false, lang, text))
        }
    }

    /** Tự cắt đoạn nói liền quá dài: nhận dạng phần đã nói, phần còn lại tiếp tục làm câu mới. */
    private suspend fun cutSpeech() {
        val cut = SpeechCutter.cutPoint(speech, speechLen, SpeechEngine.SAMPLE_RATE)
        val start = speechBase + speechPreRoll
        val raw = speech.copyOfRange(speechPreRoll, cut)
        AppLog.d(TAG) { "tự cắt đoạn nói liền: lấy ${"%.1f".format(seconds(raw.size))}s, còn ${"%.1f".format(seconds(speechLen - cut))}s" }
        speech.copyInto(speech, 0, cut, speechLen)
        speechLen -= cut
        speechBase += cut
        speechPreRoll = 0
        onVadSegment(start, raw, forced = true)
        consumedEnd = start + raw.size
    }

    private suspend fun emitFinal(rawText: String, lang: String) {
        val id = ++segmentId
        val text = if (HallucinationFilter.isLikelyHallucination(rawText)) "" else TextCleaner.clean(rawText, lang, isFinal = true)
        _segments.emit(RecognizedSegment(id, true, lang, text))
    }

    /** Chốt câu đang giữ bằng chữ nhận dạng lần gần nhất (hết chờ, hoặc không còn chỗ gộp thêm). */
    private suspend fun flushPending() {
        val text = pendingText
        val lang = pendingLang
        resetPending()
        emitFinal(text, lang)
    }

    private fun resetPending() {
        pending = FloatArray(0)
        pendingText = ""
        pendingLang = ""
        pendingSilence = 0
    }

    private fun seconds(samples: Int) = samples.toFloat() / SpeechEngine.SAMPLE_RATE

    /**
     * Đoạn VAD kèm âm thanh đệm trước và sau lấy từ lịch sử. `start` phải trỏ đúng vào âm thanh
     * VAD trả về; không khớp thì dùng nguyên đoạn VAD, để lỡ lệch cũng không làm hỏng câu.
     */
    private fun padded(start: Long, raw: FloatArray, cutByLimit: Boolean): FloatArray {
        val end = start + raw.size
        val (from, to) = SpeechPadding.bounds(start, raw.size, lastEnd, history.total, cutByLimit, SpeechEngine.SAMPLE_RATE)
        val audio = history.slice(from, to)
        val offset = (start - from).toInt()
        if (audio == null || !audio.hasAt(offset, raw)) {
            if (!paddingWarned) {
                paddingWarned = true
                AppLog.w(TAG) { "start=$start không khớp lịch sử âm thanh (đã nhận ${history.total}), bỏ đệm" }
            }
            lastEnd = end
            return raw
        }
        lastEnd = to
        AppLog.d(TAG) { "đệm #${segmentId + 1} trước=${offset / 16}ms sau=${(to - end) / 16}ms" }
        return audio
    }

    private fun FloatArray.hasAt(offset: Int, expected: FloatArray): Boolean {
        for (i in expected.indices) if (this[offset + i] != expected[i]) return false
        return true
    }

    /** Partial cũng cần đoạn đệm đầu câu, nếu không chữ đầu bị mất như ở câu final. */
    private fun seedPreRoll() {
        val windowStart = history.total - VAD_WINDOW
        speechBase = windowStart
        val from = SpeechPadding.preRollStart(windowStart, lastEnd, SpeechEngine.SAMPLE_RATE)
        val pre = history.slice(from, windowStart) ?: return
        appendSpeech(pre)
        speechPreRoll = pre.size
        speechBase = from
    }

    private suspend fun emitPartial(samples: FloatArray) {
        val t0 = SystemClock.elapsedRealtime()
        val result = asr.transcribe(samples, tracker.forcedLanguage())
        val elapsed = SystemClock.elapsedRealtime() - t0
        updateRealTimeFactor(elapsed, samples.size)
        AppLog.d(TAG) { "partial #${segmentId + 1} ${samples.size / 16}ms asr=${elapsed}ms" }
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
        // Đoạn nói liền dài thì mỗi lần giải mã lại từ đầu câu tốn hơn 1 s: vẫn cho hiện, nếu không phụ đề đứng hình.
        private const val MAX_PARTIAL_COST_MS = 1100f
        private const val MIN_PARTIAL_INTERVAL_MS = 500L
        private const val PARTIAL_DUTY_FACTOR = 3f

        /** Lịch sử âm thanh giữ 12 s: đủ cho đoạn dài nhất (6 s) cùng đệm đầu/cuối và độ trễ chốt đoạn. */
        private const val HISTORY_SAMPLES = SpeechEngine.SAMPLE_RATE * 12

        /** Đoạn nói liền dài tới mức này (7 s, hơn [VAD_MAX_SPEECH_SEC] 1 s) mà VAD chưa chốt thì tự cắt. */
        private const val MAX_SPEECH_SAMPLES = SpeechEngine.SAMPLE_RATE * 7

        /** Giọng nói tối thiểu (0,6 s) trước khi hiện partial đầu tiên. */
        private const val MIN_PARTIAL_SAMPLES = SpeechEngine.SAMPLE_RATE * 3 / 5

        /** Độ dài tối đa một đoạn VAD, truyền cho Silero và dùng để nhận ra đoạn bị cắt cứng. */
        private const val VAD_MAX_SPEECH_SEC = 6f

        /** Đoạn bị cắt ở giới hạn có độ dài xấp xỉ [VAD_MAX_SPEECH_SEC] (sai số theo cửa sổ VAD). */
        private const val CUT_MARGIN_SEC = 0.5f

        /** Chờ thêm sau khi VAD chốt (đã lặng 0,45 s) rồi mới coi câu chưa có dấu kết là dứt: 0,7 s. */
        private const val HOLD_SAMPLES = SpeechEngine.SAMPLE_RATE * 7 / 10
    }
}
