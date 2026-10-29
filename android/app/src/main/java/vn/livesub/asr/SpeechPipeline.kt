package vn.livesub.asr

import android.os.SystemClock
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import vn.livesub.AppLog
import vn.livesub.audio.MusicDetector
import vn.livesub.audio.QuietBoost
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
    /** Bắt cả nói thầm: VAD nhạy hơn và khuếch đại đoạn nhỏ ([QuietBoost]); dễ bắt nhầm nhạc/tiếng ồn hơn. */
    quietSpeech: Boolean = false,
    private val partialIntervalMs: Long = 1_000,
) : Closeable {

    private val vad = createVad(files, if (quietSpeech) QUIET_VAD_THRESHOLD else NORMAL_VAD_THRESHOLD)

    private val boost = if (quietSpeech) QuietBoost() else null

    // Chế độ nói thầm hay bắt nhầm nhạc thành chữ. VAD thứ hai ở ngưỡng thường nghe âm thanh gốc (chưa khuếch đại);
    // khi có nhạc ([MusicDetector]) chỉ nhận đoạn mà VAD này cũng nghe ra tiếng nói, tức là chạy như khi tắt chế độ nói thầm.
    private val strictVad = if (quietSpeech) createVad(files, NORMAL_VAD_THRESHOLD) else null
    private val strictMarks = SpeechMarks(VAD_WINDOW, HISTORY_SAMPLES / VAD_WINDOW)
    private val music = if (quietSpeech) MusicDetector(SpeechEngine.SAMPLE_RATE) else null
    private val rawWindow = FloatArray(VAD_WINDOW)
    private var musicLogged = false

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

    /** Chữ tạm gần nhất và vị trí trong [speech] lúc nó xuất hiện, để biết chữ tạm đã đứng yên bao lâu. */
    private var stableText = ""
    private var stableSince = 0

    /** Chữ tạm có nghĩa gần nhất của câu đang nói và độ dài âm thanh nó đã nghe, để không bỏ mất khi chốt. */
    private var lastPartial = ""
    private var lastPartialLen = 0

    /**
     * Chữ tạm có nghĩa bị chữ tạm sau thay hẳn bằng câu khác. Hai câu nói liền trong một đoạn thì nhận dạng cả đoạn
     * chỉ giữ câu sau (log trên máy thật: "あの外に出して外に出してくれ" rồi "腹つぐなんだろ", chốt chỉ còn câu sau).
     */
    private var skippedPartial = ""
    private var skippedPartialLen = 0

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
        val raw = FloatArray(chunk.size) { chunk[it] / 32768f }
        val musicOn = music?.onChunk(raw) == true
        if (music != null && musicOn != musicLogged) {
            musicLogged = musicOn
            AppLog.d(TAG) { "${if (musicOn) "có nhạc" else "hết nhạc"} (tỉ lệ khung âm thấp ${"%.2f".format(music.lowEnergyRatio)})" }
        }
        // Có nhạc thì không khuếch đại: nhạc nhỏ được khuếch đại sẽ bị VAD nhạy bắt thành tiếng nói.
        val samples = if (musicOn) raw else boost?.process(raw) ?: raw
        for (i in samples.indices) {
            window[windowFill] = samples[i]
            rawWindow[windowFill] = raw[i]
            windowFill++
            if (windowFill == VAD_WINDOW) {
                history.append(window, VAD_WINDOW)
                vad.acceptWaveform(window.copyOf())
                strictVad?.let { v ->
                    v.acceptWaveform(rawWindow.copyOf())
                    strictMarks.add(v.isSpeechDetected())
                    // Chỉ cần biết từng lúc có tiếng nói không; đoạn nó chốt thì bỏ.
                    while (!v.empty()) v.pop()
                }
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
            stableText = ""
            onVadSegment(segment.start, segment.samples, forced = false)
        }
        // Đoạn nói liền dài: tự cắt ở chỗ lặng nhất gần cuối, trước khi VAD cắt cứng. VAD cắt đúng mốc thời gian, hay
        // giữa chữ (log trên máy thật: "償って" thành "償っ" rồi bị dịch "Chết đi"), và cũng không luôn cắt đúng mốc
        // (đo được 7–15 s dù cấu hình 6 s).
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
        if (partialsEnabled && allowPartial && vad.isSpeechDetected() && (!musicOn || strictVad?.isSpeechDetected() == true) &&
            speechLen - speechPreRoll >= MIN_PARTIAL_SAMPLES && now - lastPartialAt >= interval &&
            partialCostMs <= MAX_PARTIAL_COST_MS
        ) {
            lastPartialAt = now
            val heard = emitPartial(pending + speech.copyOf(speechLen))
            // Chữ tạm không có chữ mới dù âm thanh vẫn tiếp tục [STABLE_SAMPLES]: người nói đã dứt, phần sau chỉ là tiếng
            // rên, thở, nhạc giữ VAD bật. Log trên máy thật: cảnh có tiếng rên thì VAD không bao giờ chốt, câu 1 s như
            // "やめてください" phải chờ đủ 6 s mới tự cắt. Chốt luôn để phụ đề không trễ.
            // Chữ tạm chỉ là tiếng rên ("うん", "あ") không tính là lời: log trên máy thật thấy chốt sớm theo "うん" rồi
            // bỏ cả đoạn, có lần cắt ngang lời thật ("すいません" thành "すいませ").
            val words = if (HallucinationFilter.isImplausible(heard, Float.MAX_VALUE, strict = false)) "" else heard
            if (SentenceBoundary.addsWords(stableText, words)) {
                stableText = words
                stableSince = speechLen
            } else if (stableText.isNotEmpty() && speechLen - stableSince >= STABLE_SAMPLES) {
                AppLog.d(TAG) { "chữ tạm không thêm chữ ${"%.1f".format(seconds(speechLen - stableSince))}s: chốt câu" }
                cutSpeech(speechLen)
            }
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
        if (chunkSec < MIN_CHUNK_SEC) {
            // Câu đang giữ (nếu có) vẫn chờ tiếp; đoạn quá ngắn này chỉ là tiếng động.
            AppLog.d(TAG) { "bỏ đoạn quá ngắn ${"%.2f".format(chunkSec)}s" }
            if (pending.isEmpty()) segmentId++
            return
        }
        if (music?.isMusic == true && strictMarks.fraction(start, start + raw.size) < MIN_STRICT_SPEECH) {
            AppLog.d(TAG) { "bỏ đoạn ${"%.1f".format(chunkSec)}s: đang có nhạc, VAD ngưỡng thường không nghe ra tiếng nói" }
            if (pending.isNotEmpty()) flushPending() else _segments.emit(RecognizedSegment(++segmentId, true, "", ""))
            return
        }

        val samples = if (pending.isEmpty()) chunk else pending + chunk
        val sec = seconds(samples.size)
        val t0 = SystemClock.elapsedRealtime()
        val manual = tracker.manuallyLocked
        // Luôn giải mã tự đoán (trừ khi khoá tay) để có tín hiệu theo dõi ngôn ngữ;
        // chỉ giải mã lại khi Whisper đoán lệch ngôn ngữ phiên.
        var result = asr.transcribe(samples, if (manual) tracker.forcedLanguage() else "")
        val heard = result.text
        result = result.copy(text = HallucinationFilter.salvage(heard))
        // Model báo là nhạc/tiếng động và chữ rất ngắn: gần như chắc chắn là rác.
        if (result.text.isEmpty() ||
            // Ít chữ thường so với độ dài đoạn chỉ đáng ngờ khi có nhạc (chữ bịa từ nhạc nền). Đoạn có tiếng rên/thở thì
            // bình thường ("だめ" giữa 3 s tiếng rên); log trên máy thật: "よし" cho cả đoạn 6,3 s tiếng rên bị bỏ oan.
            // Không bật bắt nói thầm thì không có MusicDetector, giữ cách cũ.
            HallucinationFilter.isImplausible(result.text, sec, strict = !(result.vocalNoise || music?.isMusic == false)) ||
            (result.nonSpeech && result.text.length <= 8)
        ) {
            AppLog.d(TAG) { "bỏ đoạn ${"%.1f".format(sec)}s (mới ${"%.1f".format(chunkSec)}s) là rác: $heard" }
            // Đoạn mới là rác: chốt phần câu đang giữ (nếu có) bằng chữ đã nhận dạng, bỏ đoạn này.
            if (pending.isNotEmpty()) {
                flushPending()
            } else if (!emitLostPartials("", sec, tracker.sessionLang ?: result.lang)) {
                _segments.emit(RecognizedSegment(++segmentId, true, result.lang, ""))
            }
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
                    result = asr.transcribe(samples, d.lang).let { it.copy(text = HallucinationFilter.salvage(it.text)) }
                    lang = d.lang
                }
            }
        }
        val t2 = SystemClock.elapsedRealtime()
        // Câu đáp ngắn đứng riêng (はい, え？) thường đã trọn: giữ lại chờ ghép thì người kia nói tiếp ngay,
        // nhận dạng lại cả đoạn dài và Whisper hay bỏ mất câu ngắn này.
        val ended = cutByLimit || SentenceBoundary.hasEnded(result.text, asr.hasPunctuation) ||
            (pending.isEmpty() && SentenceBoundary.isShortReply(result.text, chunkSec))
        AppLog.d(TAG) {
            "chunk #${segmentId + 1} ${"%.1f".format(sec)}s (mới ${"%.1f".format(chunkSec)}s) asr=${t1 - t0}ms " +
                "lid+redecode=${t2 - t1}ms rtf=${"%.2f".format(realTimeFactor)} lang=$lang conf=${"%.2f".format(tracker.confidence)} " +
                "cut=$cutByLimit ${if (ended) "final" else "giữ"} text=${result.text}"
        }

        if (ended) {
            resetPending()
            emitLostPartials(result.text, sec, lang)
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
    private suspend fun cutSpeech(cut: Int = SpeechCutter.cutPoint(speech, speechLen, SpeechEngine.SAMPLE_RATE)) {
        stableText = ""
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

    /**
     * Hiện các chữ tạm mà câu chốt [final] (rỗng = bị lọc) đã bỏ mất, trước câu chốt.
     * @return có hiện chữ tạm nào không.
     */
    private suspend fun emitLostPartials(final: String, finalSec: Float, lang: String): Boolean {
        val lost = listOf(skippedPartial to skippedPartialLen, lastPartial to lastPartialLen)
            .distinctBy { it.first }
            .filter { (text, len) -> SentenceBoundary.lostInFinal(text, seconds(len), final, finalSec) }
        for ((text, _) in lost) {
            AppLog.d(TAG) { "câu chốt \"$final\" bỏ mất chữ tạm \"$text\": hiện cả chữ tạm" }
            emitFinal(text, lang)
        }
        return lost.isNotEmpty()
    }

    /** Mỗi câu trong chữ nhận dạng thành một final riêng; câu đầu thay partial đang hiện (cùng id). */
    private suspend fun emitFinal(rawText: String, lang: String) {
        lastPartial = ""
        skippedPartial = ""
        val sentences = SentenceBoundary.split(HallucinationFilter.salvage(rawText))
            .map { TextCleaner.clean(it, lang, isFinal = true) }
            .filter { it.isNotEmpty() }
        if (sentences.isEmpty()) {
            // Final rỗng để overlay bỏ partial của câu này.
            _segments.emit(RecognizedSegment(++segmentId, true, lang, ""))
            return
        }
        for (text in sentences) _segments.emit(RecognizedSegment(++segmentId, true, lang, text))
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

    /** @return chữ tạm vừa nhận dạng (đã lọc), "" nếu không có. */
    private suspend fun emitPartial(samples: FloatArray): String {
        val t0 = SystemClock.elapsedRealtime()
        val result = asr.transcribe(samples, tracker.forcedLanguage())
        val elapsed = SystemClock.elapsedRealtime() - t0
        updateRealTimeFactor(elapsed, samples.size)
        val heard = HallucinationFilter.salvage(result.text)
        AppLog.d(TAG) { "partial #${segmentId + 1} ${samples.size / 16}ms asr=${elapsed}ms text=$heard" }
        if (heard.isEmpty() || result.nonSpeech) return ""
        val lang = tracker.sessionLang ?: result.lang
        val text = TextCleaner.clean(heard, lang, isFinal = false)
        if (text.isNotEmpty()) _segments.emit(RecognizedSegment(segmentId + 1, false, lang, text))
        if (!HallucinationFilter.isImplausible(heard, Float.MAX_VALUE, strict = false)) {
            // Chữ tạm mới là câu khác hẳn chữ tạm trước (không phải bản nghe rõ hơn của nó): nhớ câu trước lại.
            if (SentenceBoundary.lostInFinal(lastPartial, seconds(lastPartialLen), heard, seconds(samples.size))) {
                skippedPartial = lastPartial
                skippedPartialLen = lastPartialLen
            }
            lastPartial = heard
            lastPartialLen = samples.size
        }
        return heard
    }

    private fun updateRealTimeFactor(elapsedMs: Long, numSamples: Int) {
        val audioMs = numSamples * 1000f / SpeechEngine.SAMPLE_RATE
        if (audioMs < 500f) return
        realTimeFactor = 0.7f * realTimeFactor + 0.3f * (elapsedMs / audioMs)
    }

    private fun appendSpeech(samples: FloatArray) {
        // Engine xử lý tối đa ~30 s; pipeline tự cắt đoạn nói liền ở 5,5 s nên 10 s là dư.
        if (speechLen + samples.size > speech.size) return
        samples.copyInto(speech, speechLen)
        speechLen += samples.size
    }

    override fun close() {
        vad.release()
        strictVad?.release()
        asr.close()
    }

    companion object {
        private const val TAG = "SpeechPipeline"
        private const val VAD_WINDOW = 512
        // Đoạn nói liền dài thì mỗi lần giải mã lại từ đầu câu tốn hơn 1 s: vẫn cho hiện, nếu không phụ đề đứng hình.
        private const val MAX_PARTIAL_COST_MS = 1100f
        private const val MIN_PARTIAL_INTERVAL_MS = 500L
        private const val PARTIAL_DUTY_FACTOR = 3f

        /** Lịch sử âm thanh giữ 12 s: đủ cho đoạn VAD dài nhất (10 s) cùng đệm đầu/cuối và độ trễ chốt đoạn. */
        private const val HISTORY_SAMPLES = SpeechEngine.SAMPLE_RATE * 12

        /** Đoạn nói liền dài tới mức này (5,5 s, kể cả đệm đầu) mà VAD chưa chốt thì tự cắt ở chỗ lặng nhất. */
        private const val MAX_SPEECH_SAMPLES = SpeechEngine.SAMPLE_RATE * 11 / 2

        /** Giọng nói tối thiểu (0,6 s) trước khi hiện partial đầu tiên. */
        private const val MIN_PARTIAL_SAMPLES = SpeechEngine.SAMPLE_RATE * 3 / 5

        /**
         * Độ dài tối đa một đoạn VAD, truyền cho Silero và dùng để nhận ra đoạn bị cắt cứng. Dài hơn
         * [MAX_SPEECH_SAMPLES] để pipeline cắt trước ở chỗ lặng; VAD chỉ còn là chốt chặn cuối.
         */
        private const val VAD_MAX_SPEECH_SEC = 10f

        /** Đoạn bị cắt ở giới hạn có độ dài xấp xỉ [VAD_MAX_SPEECH_SEC] (sai số theo cửa sổ VAD). */
        private const val CUT_MARGIN_SEC = 0.5f

        /**
         * Đoạn VAD ngắn hơn mức này (0,2 s) chỉ là tiếng động. Trước là 0,3 s, nhưng VAD đã cần 0,25 s giọng nói
         * mới nhận ra, nên các câu đáp như "え？", "うん" bị bỏ; tiếng động lọt qua thì [HallucinationFilter] lọc.
         */
        private const val MIN_CHUNK_SEC = 0.2f

        private const val NORMAL_VAD_THRESHOLD = 0.5f

        /** Ngưỡng VAD khi bật bắt nói thầm: giọng thì thầm không có thanh nên xác suất thấp. */
        private const val QUIET_VAD_THRESHOLD = 0.35f

        /** Khi có nhạc, đoạn phải có ít nhất 30% thời gian VAD ngưỡng thường nghe ra tiếng nói mới được nhận dạng. */
        private const val MIN_STRICT_SPEECH = 0.3f

        private fun createVad(files: AsrModelFiles, threshold: Float) = Vad(
            assetManager = null,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = files.vad.absolutePath,
                    threshold = threshold,
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

        /** Chữ tạm không đổi trong chừng này giọng nói mới (1,2 s) thì chốt câu luôn. */
        private const val STABLE_SAMPLES = SpeechEngine.SAMPLE_RATE * 6 / 5

        /** Chờ thêm sau khi VAD chốt (đã lặng 0,45 s) rồi mới coi câu chưa có dấu kết là dứt: 0,7 s. */
        private const val HOLD_SAMPLES = SpeechEngine.SAMPLE_RATE * 7 / 10
    }
}
