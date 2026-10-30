package hu.kettleredzo.app

import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date

// Egy edzés pulzusa a naplóból: átlag, maximum, és a pulzusgörbe teljes képernyőn.
class HrActivity : KettlerActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val w = Db.get(this).workout(intent.getLongExtra(EXTRA_WORKOUT_ID, -1))
        if (w == null) {
            finish()
            return
        }
        val ui = Ui(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            val e = ui.edge
            setPadding(ui.ipx(50 + e), ui.ipx(40 + e), ui.ipx(50 + e), ui.ipx(40 + e))
        }
        root.addView(ui.pill("← VISSZA") { finish() }, ui.lp(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(ui.text(SimpleDateFormat("yyyy. MM. dd. HH:mm", HU).format(Date(w.startedAt)) + "   ·   " + w.label, 22, MUTED, ui.medium)
            .apply { maxLines = 2 }, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(28) })
        root.addView(ui.text("Pulzus", 56, INK, ui.bold), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(8) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((value, caption) in listOf(
            (w.avgHr?.toString() ?: "–") to "átlag bpm",
            (w.maxHr?.toString() ?: "–") to "max bpm",
            formatTime(w.movingMs) to "idő",
            formatNum(w.kcal, 0) to "kcal",
        )) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(ui.text(value, 48, INK, ui.bold))
            col.addView(ui.text(caption, 18, MUTED, ui.regular), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(4) })
            row.addView(col, ui.lp(0, WRAP_CONTENT, 1f))
        }
        root.addView(row, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) })

        val chart = HrChartView(this, ui).apply { samples = HrChartView.parse(w.hrSamples) }
        root.addView(chart, ui.lp(MATCH_PARENT, 0, 1f).apply { topMargin = ui.ipx(30) })
        if (chart.samples.size < 2) root.addView(ui.note("Ehhez az edzéshez nincs pulzusgörbe."), ui.fieldLp())
        setContentView(root)
    }

    companion object {
        const val EXTRA_WORKOUT_ID = "workoutId"
    }
}
