package vn.livesub.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import vn.livesub.data.ModelPart

/** Whisper đa ngôn ngữ chạy trên máy qua sherpa-onnx. */
class WhisperAsr(files: AsrModelFiles, numThreads: Int) : SpeechEngine {

    private val baseConfig = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SpeechEngine.SAMPLE_RATE, featureDim = 80),
        modelConfig = OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = files[ModelPart.ENCODER].absolutePath,
                decoder = files[ModelPart.DECODER].absolutePath,
                // Rỗng = Whisper tự nhận diện ngôn ngữ từ âm thanh.
                language = "",
                task = "transcribe",
                // Số khung lặng đệm cuối câu để Whisper nhận ra điểm kết thúc. Mặc định của
                // sherpa-onnx là 1000 (~10 s) khiến câu 0,5 s cũng phải mã hoá ~10,5 s âm thanh;
                // sherpa-onnx khuyến nghị 300 cho model đa ngôn ngữ.
                tailPaddings = 300,
            ),
            tokens = files[ModelPart.TOKENS].absolutePath,
            modelType = "whisper",
            numThreads = numThreads,
        ),
    )

    private val recognizer = OfflineRecognizer(assetManager = null, config = baseConfig)
    private var currentLanguage = ""

    override fun transcribe(samples: FloatArray, language: String): SpeechEngine.Result {
        if (language != currentLanguage) {
            // setConfig chỉ sao chép cấu hình whisper trong native, gần như không tốn chi phí.
            recognizer.setConfig(
                baseConfig.copy(
                    modelConfig = baseConfig.modelConfig.copy(
                        whisper = baseConfig.modelConfig.whisper.copy(language = language),
                    ),
                ),
            )
            currentLanguage = language
        }
        val r = recognizer.decodeOnce(samples)
        return SpeechEngine.Result(text = r.text.trim(), lang = r.lang.ifEmpty { language })
    }

    override fun close() = recognizer.release()
}
