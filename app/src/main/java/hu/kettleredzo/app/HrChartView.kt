package hu.kettleredzo.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import org.json.JSONArray
import kotlin.math.max

// Pulzusgörbe: 5 másodpercenkénti bpm-ek vonalként, bal oldalt a skála, alul az eltelt percek.
class HrChartView(context: Context, private val ui: Ui) : View(context) {
    var samples = IntArray(0)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DANGER
        style = Paint.Style.STROKE
        strokeWidth = ui.px(4)
        strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint().apply {
        color = LINE
        strokeWidth = max(1f, ui.px(2))
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED
        textSize = ui.textPx(16)
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.size < 2) return
        val left = labelPaint.measureText("200") + ui.px(14)
        val bottom = height - ui.textPx(16) - ui.px(14)
        val top = ui.px(10)
        // A skála 10-re kerekítve, a mért tartomány köré
        val lo = (samples.min() / 10 - 1) * 10
        val hi = (samples.max() / 10 + 1) * 10
        val y = { bpm: Int -> bottom - (bpm - lo).toFloat() / (hi - lo) * (bottom - top) }
        val step = if (hi - lo > 60) 20 else 10
        var v = lo
        while (v <= hi) {
            canvas.drawLine(left, y(v), width.toFloat(), y(v), gridPaint)
            canvas.drawText(v.toString(), 0f, y(v) + labelPaint.textSize / 3, labelPaint)
            v += step
        }
        val w = width - left
        path.reset()
        samples.forEachIndexed { i, bpm ->
            val x = left + w * i / (samples.size - 1)
            if (i == 0) path.moveTo(x, y(bpm)) else path.lineTo(x, y(bpm))
        }
        canvas.drawPath(path, linePaint)
        // Alul percek: az elején, közepén és a végén
        val minutes = samples.size * 5 / 60.0
        labelPaint.textAlign = Paint.Align.CENTER
        for (f in listOf(0.0, 0.5, 1.0)) {
            val x = (left + w * f).toFloat().coerceIn(left + ui.px(20), width - ui.px(30))
            canvas.drawText("${(minutes * f).toInt()}′", x, height - ui.px(4), labelPaint)
        }
        labelPaint.textAlign = Paint.Align.LEFT
    }

    companion object {
        fun parse(json: String?): IntArray = try {
            val a = JSONArray(json ?: return IntArray(0))
            IntArray(a.length()) { a.getInt(it) }
        } catch (e: Exception) {
            IntArray(0)
        }
    }
}
