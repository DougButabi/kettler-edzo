package hu.kettleredzo.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max

// Egyszerű oszlopdiagram: napi értékek (a decimals tizedesjeggyel), alattuk a nap száma, a mai oszlop sárga.
class BarChartView(context: Context, private val ui: Ui) : View(context) {
    var values = DoubleArray(0)
    var labels = emptyList<String>()
    var decimals = 1

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BAR }
    private val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = YELLOW }
    private val axisPaint = Paint().apply {
        color = LINE
        strokeWidth = max(1f, ui.px(2))
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED
        textSize = ui.textPx(16)
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        textSize = ui.textPx(15)
        textAlign = Paint.Align.CENTER
        typeface = ui.bold
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = values.size
        if (n == 0) return
        val labelH = ui.textPx(16) + ui.px(14)
        val valueH = ui.textPx(15) + ui.px(9)
        val bottom = height - labelH
        val maxV = max(values.maxOrNull() ?: 0.0, 0.5)
        val slot = width.toFloat() / n
        val barW = slot * 0.62f

        canvas.drawLine(0f, bottom, width.toFloat(), bottom, axisPaint)
        for (i in 0 until n) {
            val cx = slot * i + slot / 2
            val h = ((values[i] / maxV) * (bottom - valueH)).toFloat()
            if (values[i] > 0) {
                rect.set(cx - barW / 2, bottom - h, cx + barW / 2, bottom)
                canvas.drawRoundRect(rect, ui.px(4), ui.px(4), if (i == n - 1) todayPaint else barPaint)
                canvas.drawText(formatNum(values[i], decimals), cx, bottom - h - ui.px(6), valuePaint)
            }
            labels.getOrNull(i)?.let { canvas.drawText(it, cx, height - ui.px(6), labelPaint) }
        }
    }
}
