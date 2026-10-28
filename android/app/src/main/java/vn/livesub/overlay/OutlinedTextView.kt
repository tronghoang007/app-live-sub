package vn.livesub.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.widget.TextView

/**
 * TextView vẽ chữ có viền (mặc định trắng viền đen) để đọc được trên mọi nền video.
 *
 * Vẽ hai lượt: lượt viền (STROKE, màu viền) rồi lượt chữ (FILL, màu chữ).
 * setTextColor() bên trong onDraw sẽ gọi invalidate(), nên chặn invalidate
 * trong lúc vẽ để tránh vòng lặp vẽ lại vô hạn.
 */
class OutlinedTextView(context: Context) : TextView(context) {

    var strokeColor: Int = Color.BLACK
    var strokeWidthPx: Float = 2f * resources.displayMetrics.density

    private var drawingOutline = false

    override fun onDraw(canvas: Canvas) {
        val fillColors = textColors
        drawingOutline = true
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = strokeWidthPx
        setTextColor(strokeColor)
        super.onDraw(canvas)

        paint.style = Paint.Style.FILL
        setTextColor(fillColors)
        super.onDraw(canvas)
        drawingOutline = false
    }

    /**
     * Đặt chữ sao cho vừa [maxLines] dòng trong [maxWidthPx]: giữ cỡ gốc nếu vừa,
     * không thì thu nhỏ dần tới [minScale] cỡ gốc; vẫn quá dài mới cắt bằng "…".
     */
    fun setFittedText(text: CharSequence, baseSizePx: Float, maxLines: Int, maxWidthPx: Int, minScale: Float = 0.7f) {
        val stepPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
        val minSize = baseSizePx * minScale
        val measure = TextPaint(paint)
        var size = baseSizePx
        while (size > minSize && maxWidthPx > 0) {
            measure.textSize = size
            val lines = StaticLayout.Builder.obtain(text, 0, text.length, measure, maxWidthPx)
                .setIncludePad(includeFontPadding)
                .build()
                .lineCount
            if (lines <= maxLines) break
            size = maxOf(minSize, size - stepPx)
        }
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        this.maxLines = maxLines
        maxWidth = maxWidthPx
        setText(text)
    }

    override fun invalidate() {
        if (!drawingOutline) super.invalidate()
    }
}
