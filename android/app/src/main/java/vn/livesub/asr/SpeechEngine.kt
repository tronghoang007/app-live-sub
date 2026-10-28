package vn.livesub.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
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
     */
    data class Result(val text: String, val lang: String, val nonSpeech: Boolean = false)

    /** @param language mã ngôn ngữ ép cho model, hoặc "" để model tự đoán (nếu hỗ trợ). */
    fun transcribe(samples: FloatArray, language: String): Result

    companion object {
        const val SAMPLE_RATE = 16_000

        fun create(model: AsrModel, files: AsrModelFiles, numThreads: Int): SpeechEngine = when (model.engine) {
            AsrEngineType.WHISPER -> WhisperAsr(files, numThreads)
            AsrEngineType.SENSE_VOICE -> SenseVoiceAsr(files, numThreads)
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
 * SenseVoice (Alibaba): mô hình không tự hồi quy nên nhanh hơn Whisper nhiều;
 * hiểu Trung, Anh, Nhật, Hàn, Quảng Đông; tự trả kèm ngôn ngữ và loại âm thanh (lời nói/nhạc...).
 */
class SenseVoiceAsr(files: AsrModelFiles, numThreads: Int) : SpeechEngine {

    private val baseConfig = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SpeechEngine.SAMPLE_RATE, featureDim = 80),
        modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(
                model = files[ModelPart.MODEL].absolutePath,
                // Rỗng = tự nhận diện ngôn ngữ.
                language = "",
                // Đổi số đọc thành chữ số ("にじゅう" -> "20") và thêm dấu câu.
                useInverseTextNormalization = true,
            ),
            tokens = files[ModelPart.TOKENS].absolutePath,
            numThreads = numThreads,
        ),
    )
    private val recognizer = OfflineRecognizer(assetManager = null, config = baseConfig)
    private var currentLanguage = ""

    override fun transcribe(samples: FloatArray, language: String): SpeechEngine.Result {
        // SenseVoice chỉ biết vài ngôn ngữ; ngôn ngữ khác thì để nó tự đoán.
        val lang = language.takeIf { it in SUPPORTED } ?: ""
        if (lang != currentLanguage) {
            recognizer.setConfig(
                baseConfig.copy(
                    modelConfig = baseConfig.modelConfig.copy(
                        senseVoice = baseConfig.modelConfig.senseVoice.copy(language = lang),
                    ),
                ),
            )
            currentLanguage = lang
        }
        val r = recognizer.decodeOnce(samples)
        // Nhãn trả về dạng "<|ja|>", "<|Speech|>", "<|BGM|>".
        val detected = r.lang.removePrefix("<|").removeSuffix("|>").lowercase()
        val event = r.event.removePrefix("<|").removeSuffix("|>")
        return SpeechEngine.Result(
            text = r.text.trim(),
            lang = detected.ifEmpty { lang },
            nonSpeech = event.isNotEmpty() && event != "Speech",
        )
    }

    override fun close() = recognizer.release()

    companion object {
        private val SUPPORTED = setOf("zh", "en", "ja", "ko", "yue")
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

    override fun transcribe(samples: FloatArray, language: String): SpeechEngine.Result =
        SpeechEngine.Result(text = recognizer.decodeOnce(samples).text.trim(), lang = fixedLanguage)

    override fun close() = recognizer.release()
}
