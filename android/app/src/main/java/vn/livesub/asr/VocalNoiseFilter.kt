package vn.livesub.asr

/**
 * ReazonSpeech gặp tiếng rên, tiếng thở hay nhạc thì ra vài chữ kana lẻ ("あ", "ん", "は", "リ") rải rác
 * trong đoạn. Chữ lẻ đứng riêng thì [HallucinationFilter] bỏ được, nhưng lẫn vào câu thật thì làm câu
 * sai ("あんやめて"), còn lời thật ngắn giữa đoạn rên dài ("だめ" trong 3 s) thì bị coi là rác và mất.
 *
 * Engine nhận dạng theo từng chữ và cho biết thời điểm của từng chữ. Gom chữ thành cụm theo khoảng cách
 * thời gian rồi chỉ bỏ cụm toàn âm rên/thở ("あっ", "んっ", "はぁ").
 *
 * Đo trên máy thật: chữ đầu tiên luôn được báo ở ~0,00 s dù người nói bắt đầu muộn hơn, nên khoảng cách
 * giữa chữ đầu và chữ thứ hai không dùng được ("そ@0,00 う@1,72 ね@1,96" là "そうね"); chữ đầu luôn ghép
 * với chữ thứ hai. Chữ kana đứng lẻ không bỏ: khoảng cách chữ trong lời nói thật cũng có lúc > 0,5 s
 * ("は@0,00 い@0,88"); chữ lẻ từ nhạc đã có [HallucinationFilter.isImplausible] lọc theo cả đoạn.
 * Thuần Kotlin để unit test được.
 */
object VocalNoiseFilter {
    /** Hai chữ cách nhau quá mức này (giây) thì thuộc hai cụm khác nhau. */
    const val MAX_GAP_SEC = 0.5f

    // Âm rên/thở/kêu. Không có "う" để giữ "うん", không có "い" để giữ "はい".
    private const val VOCAL = "あぁアァんンっッはハおぉオォー〜"

    /**
     * @param tokens các chữ engine nhận ra, theo thứ tự.
     * @param timestamps thời điểm (giây) của từng chữ; thiếu hoặc lệch số lượng thì trả nguyên chữ.
     * @return chữ còn lại sau khi bỏ cụm tiếng rên/thở, và có bỏ cụm nào không.
     */
    fun filter(tokens: List<String>, timestamps: FloatArray): Pair<String, Boolean> {
        if (tokens.isEmpty() || tokens.size != timestamps.size) return tokens.joinToString("") to false
        val kept = StringBuilder()
        var dropped = false
        var start = 0
        for (i in 1..tokens.size) {
            if (i < tokens.size && (i == 1 || timestamps[i] - timestamps[i - 1] <= MAX_GAP_SEC)) continue
            val cluster = tokens.subList(start, i).joinToString("")
            if (isNoise(cluster)) dropped = true else kept.append(cluster)
            start = i
        }
        return kept.toString() to dropped
    }

    private fun isNoise(cluster: String): Boolean = cluster.filter { it.isLetter() }.all { it in VOCAL }
}
