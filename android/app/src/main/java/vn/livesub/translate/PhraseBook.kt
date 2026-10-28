package vn.livesub.translate

/**
 * Bản dịch cố định cho các câu ngắn rất hay gặp trong phim.
 *
 * ML Kit dịch Nhật → Việt qua tiếng Anh trung gian nên hay hỏng đúng những câu này
 * (いいえ → "No" → "Số", どうも → "."), trong khi chúng chiếm phần lớn số câu thoại.
 * Chỉ khớp nguyên câu (bỏ dấu câu), không đụng tới câu dài.
 */
object PhraseBook {

    private val jaVi: Map<String, String> = mapOf(
        "はい" to "Vâng",
        "ええ" to "Vâng",
        "うん" to "Ừ",
        "いいえ" to "Không",
        "いや" to "Không",
        "どうも" to "Cảm ơn",
        "どうもありがとう" to "Cảm ơn nhiều",
        "ありがとう" to "Cảm ơn",
        "ありがとうございます" to "Cảm ơn",
        "ありがとうございました" to "Cảm ơn",
        "どういたしまして" to "Không có gì",
        // ASR hay mất "どう" ở đầu câu.
        "いたしまして" to "Không có gì",
        "すみません" to "Xin lỗi",
        "ごめん" to "Xin lỗi",
        "ごめんなさい" to "Xin lỗi",
        "そう" to "Vậy à",
        "そうですか" to "Vậy à",
        "そうですね" to "Đúng vậy nhỉ",
        "そうです" to "Đúng vậy",
        "本当" to "Thật à",
        "本当ですか" to "Thật vậy sao?",
        "大丈夫" to "Không sao",
        "大丈夫ですか" to "Có sao không?",
        "お願いします" to "Nhờ anh/chị",
        "よろしくお願いします" to "Rất mong được giúp đỡ",
        "はじめまして" to "Rất vui được gặp",
        "おはよう" to "Chào buổi sáng",
        "おはようございます" to "Chào buổi sáng",
        "こんにちは" to "Xin chào",
        "こんばんは" to "Chào buổi tối",
        "さようなら" to "Tạm biệt",
        "じゃあね" to "Hẹn gặp lại",
        "おやすみ" to "Chúc ngủ ngon",
        "おやすみなさい" to "Chúc ngủ ngon",
        "いただきます" to "Mời mọi người ăn",
        "ごちそうさま" to "Cảm ơn vì bữa ăn",
        "ごちそうさまでした" to "Cảm ơn vì bữa ăn",
        "お疲れ様です" to "Vất vả rồi",
        "お疲れ様でした" to "Vất vả rồi",
        "なるほど" to "Ra vậy",
        "え" to "Hả?",
        "えっ" to "Hả?",
        "何" to "Cái gì?",
        "何ですか" to "Gì vậy?",
        "もちろん" to "Tất nhiên",
        "頑張って" to "Cố lên",
        "頑張ってください" to "Cố lên nhé",
        "頑張ります" to "Tôi sẽ cố gắng",
        "行ってきます" to "Tôi đi đây",
        "行ってらっしゃい" to "Đi cẩn thận nhé",
        "ただいま" to "Tôi về rồi",
        "おかえり" to "Mừng về nhà",
        "おかえりなさい" to "Mừng về nhà",
        "待って" to "Đợi đã",
        "ちょっと待って" to "Đợi chút",
        "やめて" to "Dừng lại",
        "助けて" to "Cứu với",
        "分かった" to "Hiểu rồi",
        "分かりました" to "Tôi hiểu rồi",
        "すごい" to "Tuyệt quá",
        "すごいですね" to "Tuyệt thật",
    )

    private const val PUNCTUATION = "。、！？!?…・ 　,."

    fun lookup(text: String, sourceLang: String, targetLang: String): String? {
        if (sourceLang != "ja" || targetLang != "vi") return null
        val key = text.trim().trim { it in PUNCTUATION }
        val hit = jaVi[key] ?: return null
        // Giữ dấu hỏi của câu gốc nếu bản dịch chưa có.
        val asked = text.trimEnd().lastOrNull()?.let { it in "？?" } == true
        return if (asked && !hit.endsWith("?")) "$hit?" else hit
    }
}
