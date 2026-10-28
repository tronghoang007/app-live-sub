package vn.livesub.data

enum class AsrEngineType { WHISPER, SENSE_VOICE, TRANSDUCER }

/** Vai trò của từng file trong một model nhận dạng. */
enum class ModelPart { ENCODER, DECODER, JOINER, MODEL, TOKENS }

/**
 * Các model nhận dạng giọng nói chạy trên máy (sherpa-onnx), tải từng file từ HuggingFace.
 * @param languages ngôn ngữ model hiểu; null = đa ngôn ngữ (Whisper).
 */
enum class AsrModel(
    val label: String,
    val note: String,
    val approxMb: Int,
    val engine: AsrEngineType,
    val repo: String,
    val files: Map<ModelPart, String>,
    val languages: Set<String>?,
    /** Thư mục lưu trên máy; giữ tên cũ "whisper-*" để không phải tải lại model đã có. */
    val dirName: String,
) {
    TINY(
        "Whisper tiny", "Nhanh nhất, kém chính xác · ~100 ngôn ngữ", 103, AsrEngineType.WHISPER,
        "csukuangfj/sherpa-onnx-whisper-tiny",
        mapOf(ModelPart.ENCODER to "tiny-encoder.int8.onnx", ModelPart.DECODER to "tiny-decoder.int8.onnx", ModelPart.TOKENS to "tiny-tokens.txt"),
        null, "whisper-tiny",
    ),
    BASE(
        "Whisper base", "Cân bằng · ~100 ngôn ngữ", 160, AsrEngineType.WHISPER,
        "csukuangfj/sherpa-onnx-whisper-base",
        mapOf(ModelPart.ENCODER to "base-encoder.int8.onnx", ModelPart.DECODER to "base-decoder.int8.onnx", ModelPart.TOKENS to "base-tokens.txt"),
        null, "whisper-base",
    ),
    SMALL(
        "Whisper small", "Chính xác hơn nhưng chậm trên máy tầm trung · ~100 ngôn ngữ", 375, AsrEngineType.WHISPER,
        "csukuangfj/sherpa-onnx-whisper-small",
        mapOf(ModelPart.ENCODER to "small-encoder.int8.onnx", ModelPart.DECODER to "small-decoder.int8.onnx", ModelPart.TOKENS to "small-tokens.txt"),
        null, "whisper-small",
    ),
    SENSE_VOICE(
        "SenseVoice", "Rất nhanh · Nhật, Hàn, Trung, Anh, Quảng Đông", 237, AsrEngineType.SENSE_VOICE,
        "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
        mapOf(ModelPart.MODEL to "model.int8.onnx", ModelPart.TOKENS to "tokens.txt"),
        setOf("ja", "ko", "zh", "en", "yue"), "sense-voice-2025-09-09",
    ),
    REAZON_JA(
        "ReazonSpeech", "Chuyên tiếng Nhật (học từ thoại truyền hình Nhật) · chỉ tiếng Nhật", 170, AsrEngineType.TRANSDUCER,
        "csukuangfj/reazonspeech-k2-v2",
        mapOf(
            ModelPart.ENCODER to "encoder-epoch-99-avg-1.int8.onnx",
            ModelPart.DECODER to "decoder-epoch-99-avg-1.onnx",
            ModelPart.JOINER to "joiner-epoch-99-avg-1.int8.onnx",
            ModelPart.TOKENS to "tokens.txt",
        ),
        setOf("ja"), "reazonspeech-k2-v2",
    ),
}

data class AppSettings(
    /** Mã ngôn ngữ đích (ML Kit), mặc định tiếng Việt. */
    val targetLang: String = "vi",
    /** [AUTO] hoặc mã Whisper để khoá cứng ngôn ngữ nguồn. */
    val sourceLang: String = AUTO,
    val asrModel: AsrModel = AsrModel.BASE,
    val textSizeSp: Float = 22f,
    /** Độ mờ nền phụ đề, 0 = trong suốt, 1 = đen đặc. */
    val backgroundAlpha: Float = 1f,
    /** Khoảng cách từ mép dưới màn hình, tính theo % chiều cao. */
    val bottomOffsetPercent: Float = 8f,
    /** Lệch ngang so với giữa màn hình, tính theo % chiều rộng (âm = sang trái). */
    val horizontalOffsetPercent: Float = 0f,
    val showSourceText: Boolean = true,
    /** Số câu hiển thị cùng lúc, xếp chồng kiểu chat (1 = kiểu phụ đề phim). */
    val stackSize: Int = 3,
    val showPartials: Boolean = true,
    /**
     * Cho chạm xuyên qua khung phụ đề xuống video. Android 12+ chỉ cho phép khi
     * cửa sổ overlay trong suốt tối thiểu 20%, nên khi bật chữ sẽ hơi mờ.
     */
    val touchThrough: Boolean = false,
    /** Luôn hiện khung và giữ các câu gần nhất; false = tự xoá sau [clearAfterMs] im lặng. */
    val alwaysShowBox: Boolean = true,
    val clearAfterMs: Long = 3500,
    val wifiOnlyDownloads: Boolean = false,
    /** Địa chỉ server dịch LLM trên máy tính (vd. "192.168.1.5:8765"); rỗng = chỉ dùng ML Kit. */
    val translationServer: String = "",
) {
    companion object {
        const val AUTO = "auto"
    }
}
