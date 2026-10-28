package vn.livesub.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import vn.livesub.data.AppSettings

/** Sự kiện phụ đề hiển thị trên overlay. */
data class SubtitleEvent(
    val id: Long,
    val type: Type,
    val detectedLang: String,
    val srcText: String,
    /** null khi chưa có bản dịch (đang tải model, không hỗ trợ, cùng ngôn ngữ...). */
    val translatedText: String?,
) {
    enum class Type { PARTIAL, FINAL }
}

/**
 * Overlay phụ đề nổi trên mọi app (kể cả Chrome toàn màn hình) và hiện cả khi
 * mirror lên TV vì nó là một phần nội dung màn hình.
 *
 * Phụ đề xếp chồng kiểu chat: câu mới nhất ở dưới cùng, các câu trước đẩy lên
 * trên và mờ đi; giữ tối đa [AppSettings.stackSize] câu.
 *
 * Gồm hai cửa sổ:
 * - Cửa sổ phụ đề: không nhận focus; kéo được để đổi vị trí.
 * - Tay nắm nhỏ phía trên: nhãn ngôn ngữ, nút khoá ngôn ngữ, cũng kéo được.
 *   Tách riêng để vẫn kéo/khoá được khi khung phụ đề đang trống hoặc bật chạm xuyên qua.
 */
class SubtitleOverlay(
    private val context: Context,
    private val onToggleLock: () -> Unit,
    /** Bấm ✕ trên tay nắm: đóng khung và dừng phiên phụ đề. */
    private val onClose: () -> Unit,
    private val onPositionChanged: (bottomOffsetPercent: Float, horizontalOffsetPercent: Float) -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    /** Một câu trong chồng phụ đề. */
    private data class Entry(val id: Long, val src: String, val translated: String?, val partial: Boolean)

    /** Cặp view hiển thị một câu: câu gốc (nhỏ) phía trên, bản dịch phía dưới. */
    private inner class EntryView {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        val source = OutlinedTextView(context).apply { style() }
        val main = OutlinedTextView(context).apply { style() }

        init {
            root.addView(source, fill())
            root.addView(main, fill())
        }
    }

    // Cửa sổ chỉ bao đúng khung chữ; khung có độ rộng cố định để không co giãn/nhảy khi đổi câu.
    private val subtitleWindow = FrameLayout(context)
    private val subtitleRoot = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(10)
        setPadding(pad, dp(4), pad, dp(6))
    }
    private val entriesContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    /** Hiện khi khung luôn bật nhưng chưa có câu nào. */
    private val placeholderView = TextView(context).apply {
        setTextColor(Color.argb(160, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        text = "Đang nghe…"
        visibility = View.GONE
    }
    private val noticeView = TextView(context).apply {
        setTextColor(Color.parseColor("#FFD54F"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
        visibility = View.GONE
    }

    private val handleRoot = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundedBackground(Color.argb(170, 0, 0, 0))
        setPadding(dp(8), dp(2), dp(4), dp(2))
    }
    private val langBadge = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        text = "⋮⋮ …"
    }
    private val lockButton = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(10), dp(6), dp(8), dp(6))
        text = LOCK_LABEL
        setOnClickListener { onToggleLock() }
    }
    private val closeButton = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        // Vùng chạm rộng hơn chữ để dễ bấm trúng khi đang xem phim.
        setPadding(dp(12), dp(6), dp(10), dp(6))
        text = "✕"
        contentDescription = "Đóng phụ đề"
        setOnClickListener { onClose() }
    }

    private val subtitleParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        BASE_FLAGS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private val handleParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        BASE_FLAGS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.END
        x = dp(12)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private var settings = AppSettings()
    private var attached = false
    private var finalizedId = 0L
    private val entries = ArrayList<Entry>()
    private val entryViews = ArrayList<EntryView>()
    private val clearRunnable = Runnable { clearSubtitle() }

    init {
        subtitleWindow.addView(
            subtitleRoot,
            FrameLayout.LayoutParams(boxWidth(), FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        subtitleRoot.addView(placeholderView, wrap())
        subtitleRoot.addView(entriesContainer, fill())
        subtitleRoot.addView(noticeView, fill())
        handleRoot.addView(langBadge)
        handleRoot.addView(lockButton)
        handleRoot.addView(closeButton)
        setupDrag()
        // Khung đổi chiều cao (thêm/bớt câu): giữ khung trong màn hình và tay nắm bám theo.
        subtitleWindow.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (bottom - top != oldBottom - oldTop || right - left != oldRight - oldLeft) {
                clampPosition()
                if (attached) wm.updateViewLayout(subtitleWindow, subtitleParams)
                updateHandlePosition()
            }
        }
    }

    fun show(initial: AppSettings) {
        if (attached) return
        applySettings(initial)
        wm.addView(subtitleWindow, subtitleParams)
        wm.addView(handleRoot, handleParams)
        attached = true
        clearSubtitle()
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        if (!attached) return
        runCatching { wm.removeView(subtitleWindow) }
        runCatching { wm.removeView(handleRoot) }
        attached = false
    }

    fun applySettings(s: AppSettings) {
        settings = s
        if (s.alwaysShowBox) handler.removeCallbacks(clearRunnable)
        trimEntries()
        refreshEntries()
        if (s.touchThrough) {
            // Android 12+ chặn chạm đi xuyên qua cửa sổ overlay của app khác nếu
            // độ đục của cửa sổ > 0.8. Đặt đúng 0.8 để chạm vẫn tới được video bên dưới.
            subtitleParams.flags = BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            subtitleParams.alpha = 0.8f
        } else {
            // Rõ nét 100%; chạm vào đúng khung chữ sẽ không tới được video.
            subtitleParams.flags = BASE_FLAGS
            subtitleParams.alpha = 1f
        }
        val dm = context.resources.displayMetrics
        subtitleRoot.layoutParams = subtitleRoot.layoutParams.apply { width = boxWidth() }
        subtitleParams.y = (dm.heightPixels * s.bottomOffsetPercent / 100f).toInt()
        subtitleParams.x = (dm.widthPixels * s.horizontalOffsetPercent / 100f).toInt()
        clampPosition()
        if (attached) {
            wm.updateViewLayout(subtitleWindow, subtitleParams)
            updateHandlePosition()
        }
    }

    fun setLanguage(code: String?, confirmed: Boolean, manuallyLocked: Boolean) {
        val label = code?.uppercase() ?: "…"
        langBadge.text = "⋮⋮ $label${if (confirmed) "" else "?"}"
        lockButton.text = if (manuallyLocked) UNLOCK_LABEL else LOCK_LABEL
    }

    fun showNotice(text: String?) {
        noticeView.text = text
        noticeView.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        updateBackground()
    }

    fun render(event: SubtitleEvent) {
        val partial = event.type == SubtitleEvent.Type.PARTIAL
        // Partial đến muộn của câu đã có final thì bỏ qua.
        if (partial && event.id <= finalizedId) return
        if (!partial) finalizedId = event.id

        val index = entries.indexOfFirst { it.id == event.id }
        if (event.srcText.isEmpty()) {
            // Final rỗng = câu bị lọc (nhạc, ồn): bỏ partial của câu đó nếu đang hiện.
            if (index >= 0) entries.removeAt(index)
        } else {
            val entry = Entry(event.id, event.srcText, event.translatedText, partial)
            if (index >= 0) entries[index] = entry else entries.add(entry)
            entries.sortBy { it.id }
            trimEntries()
            // Tự xoá cả chồng sau một khoảng im lặng (không có partial/final mới),
            // trừ khi người dùng chọn luôn hiện khung.
            handler.removeCallbacks(clearRunnable)
            if (!settings.alwaysShowBox) handler.postDelayed(clearRunnable, settings.clearAfterMs)
        }
        refreshEntries()
    }

    private fun trimEntries() {
        val max = settings.stackSize.coerceAtLeast(1)
        while (entries.size > max) entries.removeAt(0)
    }

    private fun refreshEntries() {
        while (entryViews.size < entries.size) {
            entryViews += EntryView().also { entriesContainer.addView(it.root, wrap()) }
        }
        val dm = context.resources.displayMetrics
        // Lấy kích thước hiện tại mỗi lần vì màn hình có thể xoay ngang khi xem phim.
        val maxWidth = boxWidth() - subtitleRoot.paddingLeft - subtitleRoot.paddingRight
        val basePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, settings.textSizeSp, dm)
        // Một câu thì căn giữa như phụ đề phim; nhiều câu thì căn trái như khung chat.
        val textGravity = if (settings.stackSize > 1) Gravity.START else Gravity.CENTER_HORIZONTAL

        entriesContainer.gravity = textGravity
        entryViews.forEachIndexed { i, view ->
            val entry = entries.getOrNull(i)
            view.root.visibility = if (entry == null) View.GONE else View.VISIBLE
            if (entry == null) return@forEachIndexed
            val latest = i == entries.lastIndex
            val main = entry.translated ?: entry.src
            val showSource = settings.showSourceText && entry.translated != null && entry.translated != entry.src

            view.root.gravity = textGravity
            // Câu cũ mờ hơn để mắt tập trung vào câu mới nhất.
            view.root.alpha = if (latest) 1f else 0.6f
            view.main.gravity = textGravity
            view.source.gravity = textGravity
            // Bản dịch tối đa 3 dòng, câu gốc 2 dòng; quá dài thì thu nhỏ chữ thay vì cắt "…".
            view.main.setFittedText(main, basePx, maxLines = 3, maxWidthPx = maxWidth)
            view.main.setTextColor(if (entry.partial) PARTIAL_COLOR else Color.WHITE)
            view.source.visibility = if (showSource) View.VISIBLE else View.GONE
            if (showSource) {
                view.source.setFittedText(entry.src, basePx * 0.7f, maxLines = 2, maxWidthPx = maxWidth)
                view.source.setTextColor(if (entry.partial) PARTIAL_COLOR else SOURCE_COLOR)
            }
        }
        updateBackground()
    }

    private fun clearSubtitle() {
        entries.clear()
        refreshEntries()
    }

    private fun updateBackground() {
        val hasText = entries.isNotEmpty() || noticeView.visibility == View.VISIBLE
        val hasContent = hasText || settings.alwaysShowBox
        placeholderView.visibility = if (hasText) View.GONE else View.VISIBLE
        subtitleRoot.background = roundedBackground(Color.argb((settings.backgroundAlpha * 255).toInt(), 0, 0, 0))
        // Không có chữ thì ẩn hẳn khung để không còn vùng vô hình chặn chạm trên video.
        subtitleRoot.visibility = if (hasContent) View.VISIBLE else View.GONE
    }

    /** Độ rộng khung: 92% màn hình, tối đa 720dp để dòng chữ không quá dài khi xoay ngang. */
    private fun boxWidth(): Int = minOf((context.resources.displayMetrics.widthPixels * 0.92f).toInt(), dp(720))

    /** Giữ toàn bộ khung nằm trong màn hình (x lệch so với giữa, y tính từ mép dưới). */
    private fun clampPosition() {
        val dm = context.resources.displayMetrics
        val w = subtitleWindow.width.takeIf { it > 0 } ?: boxWidth()
        val h = subtitleWindow.height
        val maxX = ((dm.widthPixels - w) / 2).coerceAtLeast(0)
        subtitleParams.x = subtitleParams.x.coerceIn(-maxX, maxX)
        subtitleParams.y = subtitleParams.y.coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
    }

    /** Tay nắm bám góc trên-phải của khung; khung sát mép trên thì chuyển xuống dưới khung. */
    private fun updateHandlePosition() {
        val dm = context.resources.displayMetrics
        val w = subtitleWindow.width.takeIf { it > 0 } ?: boxWidth()
        val handleH = handleRoot.height.takeIf { it > 0 } ?: dp(32)
        // Gravity BOTTOM|END: x là khoảng cách từ mép phải màn hình tới mép phải tay nắm.
        handleParams.x = ((dm.widthPixels - w) / 2 - subtitleParams.x).coerceAtLeast(0)
        val above = subtitleParams.y + subtitleWindow.height + dp(4)
        handleParams.y = if (above + handleH <= dm.heightPixels) {
            above
        } else {
            (subtitleParams.y - handleH - dp(4)).coerceAtLeast(0)
        }
        if (attached) wm.updateViewLayout(handleRoot, handleParams)
    }

    /** Gọi khi xoay màn hình: tính lại độ rộng khung và vị trí theo kích thước mới. */
    fun onScreenChanged() = applySettings(settings)

    /**
     * Kéo khung phụ đề (hoặc nhãn ngôn ngữ khi chưa có phụ đề) để đổi vị trí.
     * Khi bật "chạm xuyên qua", khung phụ đề không nhận chạm nên chỉ kéo được nhãn.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        val listener = View.OnTouchListener { _, ev ->
            val dm = context.resources.displayMetrics
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    startX = subtitleParams.x
                    startY = subtitleParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Gravity BOTTOM|CENTER_HORIZONTAL: y là khoảng cách từ mép dưới (kéo lên thì y tăng),
                    // x là độ lệch so với giữa màn hình.
                    subtitleParams.x = (startX + (ev.rawX - startRawX)).toInt()
                    subtitleParams.y = (startY - (ev.rawY - startRawY)).toInt()
                    clampPosition()
                    wm.updateViewLayout(subtitleWindow, subtitleParams)
                    updateHandlePosition()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onPositionChanged(
                        subtitleParams.y * 100f / dm.heightPixels,
                        subtitleParams.x * 100f / dm.widthPixels,
                    )
                    true
                }
                else -> false
            }
        }
        langBadge.setOnTouchListener(listener)
        subtitleWindow.setOnTouchListener(listener)
    }

    private fun OutlinedTextView.style() {
        setTextColor(Color.WHITE)
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun fill() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun wrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun roundedBackground(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(color)
    }

    private fun dp(v: Int) = (v * density).toInt()

    companion object {
        private const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        private const val LOCK_LABEL = "🔓 Khoá ngôn ngữ"
        private const val UNLOCK_LABEL = "🔒 Đã khoá"
        private val PARTIAL_COLOR = Color.argb(200, 220, 220, 220)
        private val SOURCE_COLOR = Color.argb(235, 255, 236, 179)
    }
}
