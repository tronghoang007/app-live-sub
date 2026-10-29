package vn.livesub.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import vn.livesub.AppLog
import vn.livesub.data.AsrEngineType
import vn.livesub.data.AsrModel
import vn.livesub.data.ModelPart
import java.io.Closeable

/**
 * Một engine nhận dạng giọng nói offline. Không thread-safe: chỉ gọi từ một luồng
 * (SpeechPipeline dùng dispatcher đơn luồng).
 */
interface SpeechEngine : Closeable {

    /**
     * @param text chữ nhận dạng được.
     * @param lang ngôn ngữ model đã dùng (tự đoán hoặc bị ép), "" nếu không rõ.
     * @param nonSpeech model cho biết đoạn này là nhạc/tiếng động chứ không phải lời nói.
     * @param vocalNoise đã bỏ tiếng rên/thở khỏi [text] ([VocalNoiseFilter]): đoạn dài mà ít chữ là do tiếng
     *   rên chiếm chỗ, không phải dấu hiệu chữ bị nhận nhầm từ nhạc.
     */
    data class Result(val text: String, val lang: String, val nonSpeech: Boolean = false, val vocalNoise: Boolean = false)

    /** Chữ nhận dạng có dấu câu hay không; không có thì [SentenceBoundary] chỉ dựa vào khoảng lặng. */
    val hasPunctuation: Boolean get() = true

    /** @param language mã ngôn ngữ ép cho model, hoặc "" để model tự đoán (nếu hỗ trợ). */
    fun transcribe(samples: FloatArray, language: String): Result

    companion object {
        const val SAMPLE_RATE = 16_000

        fun create(model: AsrModel, files: AsrModelFiles, numThreads: Int): SpeechEngine = when (model.engine) {
            AsrEngineType.WHISPER -> WhisperAsr(files, numThreads)
            AsrEngineType.TRANSDUCER -> TransducerAsr(files, numThreads, fixedLanguage = model.languages!!.single())
        }
    }
}

/** Chạy một lần nhận dạng trên recognizer sherpa-onnx và giải phóng stream. */
internal fun OfflineRecognizer.decodeOnce(samples: FloatArray) = createStream().let { stream ->
    try {
        stream.acceptWaveform(samples, SpeechEngine.SAMPLE_RATE)
        decode(stream)
        getResult(stream)
    } finally {
        stream.release()
    }
}

/**
 * Mô hình transducer (Zipformer RNN-T) một ngôn ngữ, ví dụ ReazonSpeech tiếng Nhật
 * học từ hàng chục nghìn giờ thoại truyền hình Nhật.
 */
class TransducerAsr(files: AsrModelFiles, numThreads: Int, private val fixedLanguage: String) : SpeechEngine {

    private val recognizer = OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SpeechEngine.SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = files[ModelPart.ENCODER].absolutePath,
                    decoder = files[ModelPart.DECODER].absolutePath,
                    joiner = files[ModelPart.JOINER].absolutePath,
                ),
                tokens = files[ModelPart.TOKENS].absolutePath,
                modelType = "transducer",
                numThreads = numThreads,
            ),
            decodingMethod = "greedy_search",
        ),
    )

    // ReazonSpeech không xuất dấu câu.
    override val hasPunctuation = false

    override fun transcribe(samples: FloatArray, language: String): SpeechEngine.Result {
        val r = recognizer.decodeOnce(samples)
        // Không có thời điểm từng chữ thì (text, false) như trước.
        val (text, vocalNoise) = if (r.tokens.isEmpty()) r.text to false else VocalNoiseFilter.filter(r.tokens.toList(), r.timestamps)
        // Ghi cả chữ kèm thời điểm để chỉnh ngưỡng khoảng cách theo phim thật.
        if (vocalNoise) AppLog.d("TransducerAsr") { "bỏ tiếng rên/thở: ${r.tokens.indices.joinToString(" ") { "${r.tokens[it]}@${"%.2f".format(r.timestamps[it])}" }} -> $text" }
        return SpeechEngine.Result(text = text.trim(), lang = fixedLanguage, vocalNoise = vocalNoise)
    }

    override fun close() = recognizer.release()
}
