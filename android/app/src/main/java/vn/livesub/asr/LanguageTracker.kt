package vn.livesub.asr

/**
 * Giữ ngôn ngữ nguồn ổn định cho cả phiên để phụ đề không "nhảy" ngôn ngữ.
 *
 * Whisper trên máy chỉ trả ngôn ngữ có xác suất cao nhất cho từng câu, không
 * trả xác suất. Vì vậy độ tin cậy ở đây là tỉ lệ thời lượng giọng nói mà
 * Whisper gán cho cùng một ngôn ngữ:
 *
 * 1. Chưa chốt: gom phiếu từ các câu có giọng nói (đoạn nhạc/ồn đã bị VAD và
 *    bộ lọc loại trước). Khi đủ [minSpeechSec] giây và một ngôn ngữ chiếm
 *    >= [acceptConfidence] thì chốt. Chưa đạt thì gom tiếp, chỉ giữ cửa sổ
 *    [windowSpeechSec] giây gần nhất.
 * 2. Đã chốt: câu nào Whisper đoán khác thì giải mã lại với ngôn ngữ phiên.
 *    Chỉ đổi khi [switchAfterSegments] câu liên tiếp cùng ra một ngôn ngữ
 *    mới, hoặc nhận diện lại từ đầu khi tỉ lệ đồng thuận trong
 *    [recentWindow] câu gần nhất xuống dưới [minAgreementRate].
 *
 * Lớp này thuần Kotlin để unit test được.
 */
class LanguageTracker(
    private val minSpeechSec: Float = 5f,
    private val windowSpeechSec: Float = 10f,
    private val acceptConfidence: Float = 0.6f,
    private val switchAfterSegments: Int = 3,
    private val minSwitchSegmentSec: Float = 1f,
    private val recentWindow: Int = 6,
    private val minAgreementRate: Float = 0.5f,
) {
    sealed interface Decision {
        /** Dùng luôn kết quả vừa giải mã, gắn nhãn [lang]. */
        data class Accept(val lang: String) : Decision

        /** Whisper đoán lệch: giải mã lại câu này với [lang]. */
        data class Redecode(val lang: String) : Decision
    }

    private data class Vote(val lang: String, val sec: Float)

    private val votes = ArrayDeque<Vote>()
    private val recentAgreement = ArrayDeque<Boolean>()
    private var switchCandidate: String? = null
    private var switchCount = 0

    /** Ngôn ngữ đã chốt cho phiên, null khi đang nhận diện. */
    var sessionLang: String? = null
        private set

    var manuallyLocked = false
        private set

    /** Độ tin cậy hiện tại (0..1) để hiển thị/debug. */
    var confidence = 0f
        private set

    /** Ngôn ngữ truyền cho Whisper; chuỗi rỗng = để Whisper tự đoán. */
    fun forcedLanguage(): String = sessionLang ?: ""

    fun lock(lang: String) {
        sessionLang = lang
        manuallyLocked = true
        confidence = 1f
        clearCounters()
    }

    /** Mở khoá thủ công; vẫn giữ ngôn ngữ hiện tại làm điểm xuất phát. */
    fun unlock() {
        manuallyLocked = false
        clearCounters()
    }

    /**
     * Ghi nhận một câu đã giải mã ở chế độ tự đoán.
     * @param detectedLang ngôn ngữ Whisper đoán cho câu.
     * @param speechSec thời lượng giọng nói của câu.
     * @param voteable false nếu câu không đủ tin cậy để bỏ phiếu (chữ quá ngắn,
     *   ML Kit không xác định được ngôn ngữ của chữ...).
     */
    fun observe(detectedLang: String, speechSec: Float, voteable: Boolean): Decision {
        val current = sessionLang
        if (manuallyLocked && current != null) return Decision.Accept(current)
        if (detectedLang.isEmpty()) return current?.let { Decision.Redecode(it) } ?: Decision.Accept("")

        if (current == null) {
            if (voteable) addVote(detectedLang, speechSec)
            return Decision.Accept(detectedLang)
        }

        if (detectedLang == current) {
            pushAgreement(true)
            switchCandidate = null
            switchCount = 0
            return Decision.Accept(current)
        }

        // Câu ngắn hoặc không đáng tin thì không được tính là bằng chứng đổi ngôn ngữ.
        if (!voteable || speechSec < minSwitchSegmentSec) return Decision.Redecode(current)

        pushAgreement(false)
        if (detectedLang == switchCandidate) switchCount++ else {
            switchCandidate = detectedLang
            switchCount = 1
        }
        if (switchCount >= switchAfterSegments) {
            sessionLang = detectedLang
            confidence = 1f
            clearCounters()
            return Decision.Accept(detectedLang)
        }
        if (recentAgreement.size >= recentWindow && confidence < minAgreementRate) {
            // Độ tin cậy giảm liên tục: bỏ chốt, nhận diện lại từ đầu.
            sessionLang = null
            clearCounters()
            addVote(detectedLang, speechSec)
            return Decision.Accept(detectedLang)
        }
        return Decision.Redecode(current)
    }

    private fun addVote(lang: String, sec: Float) {
        votes.addLast(Vote(lang, sec))
        // Giữ cửa sổ trượt ~windowSpeechSec giây giọng nói gần nhất.
        while (votes.size > 1 && votes.sumOf { it.sec.toDouble() } - votes.first().sec >= windowSpeechSec) {
            votes.removeFirst()
        }
        val total = votes.sumOf { it.sec.toDouble() }.toFloat()
        val (bestLang, bestSec) = votes.groupBy { it.lang }
            .mapValues { (_, v) -> v.sumOf { it.sec.toDouble() }.toFloat() }
            .maxBy { it.value }
        confidence = if (total > 0f) bestSec / total else 0f
        if (total >= minSpeechSec && confidence >= acceptConfidence) {
            sessionLang = bestLang
            votes.clear()
        }
    }

    private fun pushAgreement(agree: Boolean) {
        recentAgreement.addLast(agree)
        while (recentAgreement.size > recentWindow) recentAgreement.removeFirst()
        confidence = recentAgreement.count { it }.toFloat() / recentAgreement.size
    }

    private fun clearCounters() {
        votes.clear()
        recentAgreement.clear()
        switchCandidate = null
        switchCount = 0
    }
}
