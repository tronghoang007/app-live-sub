package vn.livesub.asr

/**
 * Xác định chỗ ngắt câu cho phụ đề.
 *
 * VAD chỉ nghe khoảng lặng, nên nghỉ lấy hơi giữa câu (>= 0,45 s) hay bị cắt ở độ dài
 * tối đa cũng thành một "câu" riêng, được dịch riêng và hiện thành dòng riêng: phụ đề đứt
 * giữa chừng, bản dịch cụt chủ/vị. Dấu kết câu trong chữ nhận dạng cho biết người nói đã
 * dứt câu thật chưa. Chưa dứt thì SpeechPipeline giữ lại, gộp với đoạn kế tiếp rồi nhận
 * dạng lại cả câu.
 * Thuần Kotlin để unit test được.
 */
object SentenceBoundary {
    /** Câu gộp dài hơn mức này không vừa khung phụ đề (tối đa 3 dòng) nên không gộp thêm. */
    const val MAX_SENTENCE_SEC = 8f

    private const val TERMINATORS = "。！？!?.…｡"

    // Dấu dùng để tách câu ([split]); "." xét riêng, "…" hay là ngập ngừng giữa câu nên không tách.
    private const val SPLITTERS = "。！？!?｡"

    // Ngoặc/trích dẫn có thể đứng sau dấu kết câu: 「行こう。」
    private const val CLOSERS = "」』）)”’\"'】］] "

    // Trợ từ tiếng Nhật không thể đứng cuối câu: "私は" | "学校の学生です", "さんも" | "会社員ですか".
    // Chỉ liệt kê loại chắc chắn; "の", "で", "から", "けど" hay đứng cuối câu nói nên không đưa vào.
    private val openJapaneseEnding = Regex("(は|が|を|に|へ|と|も|や)$")

    // Cũng kết thúc bằng trợ từ nhưng là câu chào trọn vẹn.
    private val closedJapaneseEnding = Regex("(こんにちは|こんばんは|どうも)$")

    fun isComplete(text: String): Boolean {
        val last = text.trimEnd { it in CLOSERS }.lastOrNull() ?: return false
        return last in TERMINATORS
    }

    /** Engine không có dấu câu: chữ dừng ở trợ từ thì câu chắc chắn chưa dứt. */
    private fun endsMidSentence(text: String): Boolean {
        val t = text.trimEnd()
        return openJapaneseEnding.containsMatchIn(t) && !closedJapaneseEnding.containsMatchIn(t)
    }

    /**
     * Đoạn VAD vừa chốt đã là hết câu chưa.
     * @param text chữ nhận dạng của cả câu tính tới giờ (đã gộp các đoạn trước).
     * @param hasPunctuation engine có tự thêm dấu câu không; không có thì khoảng lặng cho biết
     *   hết câu, trừ khi chữ dừng ở trợ từ.
     */
    fun hasEnded(text: String, hasPunctuation: Boolean): Boolean = when {
        hasPunctuation -> isComplete(text)
        else -> !endsMidSentence(text)
    }

    /** Đoạn ngắn hơn mức này (1 s) đứng riêng thường là câu đáp trọn vẹn: はい, うん, え？, 何？ */
    const val SHORT_REPLY_SEC = 1f

    /**
     * Câu đáp ngắn đứng riêng: chốt luôn dù không có dấu kết câu, trừ khi chữ dừng ở dấu phẩy
     * ("あのね、") hoặc trợ từ ("私は") tức người nói còn tiếp.
     */
    fun isShortReply(text: String, speechSec: Float): Boolean {
        val t = text.trimEnd()
        val last = t.lastOrNull() ?: return false
        return speechSec < SHORT_REPLY_SEC && last !in "、,，" && !endsMidSentence(t)
    }

    /**
     * Tách chữ của một đoạn thành từng câu. Nhiều người nói nối nhau không có chỗ lặng nên VAD gộp cả
     * tràng (tới 7 s) thành một đoạn, Whisper ra "はい。そうです。じゃあ行こう。": mỗi câu nên là một dòng
     * phụ đề và được dịch riêng. "…" và "." giữa số ("3.5") không tách; mẩu không có chữ gộp vào câu trước.
     */
    fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        val sentence = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            sentence.append(c)
            val ends = c in SPLITTERS || (c == '.' && (i == text.length || text[i].isWhitespace()))
            if (!ends) continue
            // Dấu kết câu liền nhau ("?!") và ngoặc đóng ("。」") thuộc câu này.
            while (i < text.length && (text[i] in TERMINATORS || text[i] in CLOSERS) && !text[i].isWhitespace()) sentence.append(text[i++])
            add(out, sentence)
        }
        add(out, sentence)
        return out
    }

    private fun add(out: MutableList<String>, sentence: StringBuilder) {
        val s = sentence.toString().trim()
        sentence.clear()
        if (s.isEmpty()) return
        if (s.none { it.isLetterOrDigit() } && out.isNotEmpty()) out[out.lastIndex] += s else out += s
    }

    /**
     * Chữ tạm mới [current] có thêm chữ so với [previous] không. Tiếng rên quanh lời nói làm mỗi lần nhận dạng ra hơi
     * khác (log trên máy thật: "ごめんね" → "ごめ" → "ごめんね"), nên chữ ngắn đi hoặc mất hẳn không tính là thêm chữ.
     */
    fun addsWords(previous: String, current: String): Boolean = current.isNotEmpty() && !previous.startsWith(current)

    /**
     * Câu chốt có bỏ mất chữ tạm gần nhất không. Đoạn dài có hai người nói gần như cùng lúc thì mỗi lần nhận dạng
     * chỉ giữ một câu: log trên máy thật, chữ tạm "そこに出してください" (4,5 s) nhưng câu chốt cả đoạn 5,2 s chỉ còn
     * "つぐなんだろ". Chỉ xét chữ tạm phủ gần hết đoạn dài (>= 4 s) và khác hẳn câu chốt; [final] rỗng là câu chốt bị lọc.
     */
    fun lostInFinal(partial: String, partialSec: Float, final: String, finalSec: Float): Boolean {
        if (partial.length < 4 || finalSec < 4f || partialSec < 0.7f * finalSec) return false
        if (final.contains(partial) || (final.isNotEmpty() && partial.contains(final))) return false
        val p = partial.filter { it.isLetter() }.toSet()
        val f = final.filter { it.isLetter() }.toSet()
        return p.isNotEmpty() && p.count { it in f }.toFloat() / p.size < 0.5f
    }

    /** Thêm [extraSec] giây giọng nói vào câu đang giữ [sentenceSec] giây thì còn vừa một khung phụ đề không. */
    fun canMerge(sentenceSec: Float, extraSec: Float): Boolean = sentenceSec + extraSec <= MAX_SENTENCE_SEC
}
