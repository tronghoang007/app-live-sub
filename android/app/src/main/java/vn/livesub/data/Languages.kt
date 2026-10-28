package vn.livesub.data

data class Language(val code: String, val name: String)

/**
 * Ngôn ngữ vừa có trong Whisper vừa có trong ML Kit Translation. Mã ở đây
 * trùng nhau giữa hai thư viện nên dùng chung được.
 */
object Languages {
    val all = listOf(
        Language("vi", "Tiếng Việt"),
        Language("en", "Tiếng Anh"),
        Language("ja", "Tiếng Nhật"),
        Language("ko", "Tiếng Hàn"),
        Language("zh", "Tiếng Trung"),
        Language("th", "Tiếng Thái"),
        Language("id", "Tiếng Indonesia"),
        Language("ms", "Tiếng Mã Lai"),
        Language("tl", "Tiếng Philippines"),
        Language("hi", "Tiếng Hindi"),
        Language("fr", "Tiếng Pháp"),
        Language("de", "Tiếng Đức"),
        Language("es", "Tiếng Tây Ban Nha"),
        Language("it", "Tiếng Ý"),
        Language("pt", "Tiếng Bồ Đào Nha"),
        Language("ru", "Tiếng Nga"),
        Language("tr", "Tiếng Thổ Nhĩ Kỳ"),
        Language("ar", "Tiếng Ả Rập"),
    )

    /** Nguồn hay gặp khi xem phim, dùng cho nút "tải trước model dịch". */
    val commonSources = listOf("en", "ja", "ko", "zh", "th")

    fun nameOf(code: String): String =
        if (code == AppSettings.AUTO) "Tự động" else all.firstOrNull { it.code == code }?.name ?: code.uppercase()
}
