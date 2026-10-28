package hu.kettleredzo.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

// Az aktuális profil edzésnaplója: összesítő, 14 napos diagram és az edzések listája.
class LogActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var profile: Profile
    private lateinit var body: LinearLayout

    private val dateFormat = SimpleDateFormat("yyyy. MM. dd. HH:mm", HU)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Db.get(this).profile(Prefs.currentProfileId(this))
        if (p == null) {
            finish()
            return
        }
        profile = p
        ui = Ui(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(ui.ipx(45), ui.ipx(30), ui.ipx(45), 0)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(ui.pill("← VISSZA") { finish() })
        top.addView(ui.text("${profile.name} naplója", 44, INK, ui.bold), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = ui.ipx(36)
        })
        root.addView(top)

        body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(body, ui.lp(MATCH_PARENT, 0, 1f).apply { topMargin = ui.ipx(30) })
        setContentView(root)
        build()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun build() {
        body.removeAllViews()
        val db = Db.get(this)

        // Bal oldal: összesítők és diagram
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(totalsCard("EZEN A HÉTEN", db.totals(profile.id, startOfWeek())))
        left.addView(totalsCard("ÖSSZESEN", db.totals(profile.id)), ui.lp(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = ui.ipx(24)
        })

        val chartCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(CARD, 24)
            setPadding(ui.ipx(28), ui.ipx(24), ui.ipx(28), ui.ipx(16))
        }
        chartCard.addView(ui.label("AZ ELMÚLT 14 NAP (km)"))
        val starts = dayStarts(DAYS)
        val cal = Calendar.getInstance()
        val chart = BarChartView(this, ui).apply {
            values = db.dailyKm(profile.id, DAYS)
            labels = (0 until DAYS).map {
                cal.timeInMillis = starts[it]
                cal.get(Calendar.DAY_OF_MONTH).toString()
            }
        }
        chartCard.addView(chart, ui.lp(MATCH_PARENT, 0, 1f).apply { topMargin = ui.ipx(12) })
        left.addView(chartCard, ui.lp(MATCH_PARENT, 0, 1f).apply {
            topMargin = ui.ipx(24)
            bottomMargin = ui.ipx(30)
        })
        body.addView(left, ui.lp(0, MATCH_PARENT, 0.44f))

        // Jobb oldal: az edzések listája, a legfrissebb elöl
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, ui.ipx(30))
        }
        val workouts = db.workouts(profile.id)
        if (workouts.isEmpty()) {
            list.addView(ui.text("Még nincs mentett edzés.", 24, MUTED, ui.regular))
        }
        for (w in workouts) {
            list.addView(workoutRow(w), ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = ui.ipx(16) })
        }
        body.addView(ScrollView(this).apply { addView(list) }, ui.lp(0, MATCH_PARENT, 0.56f).apply {
            leftMargin = ui.ipx(36)
        })
    }

    private fun totalsCard(title: String, t: Totals): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(CARD, 24)
            setPadding(ui.ipx(28), ui.ipx(22), ui.ipx(28), ui.ipx(22))
        }
        card.addView(ui.label(title))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(stat(t.count.toString(), "alkalom"), ui.lp(0, WRAP_CONTENT, 1f))
        row.addView(stat(formatDuration(t.movingMs), "idő"), ui.lp(0, WRAP_CONTENT, 1f))
        row.addView(stat(formatNum(t.distanceKm, 1), "km"), ui.lp(0, WRAP_CONTENT, 1f))
        row.addView(stat(formatNum(t.kcal, 0), "kcal"), ui.lp(0, WRAP_CONTENT, 1f))
        card.addView(row, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(14) })
        return card
    }

    private fun stat(value: String, caption: String): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.text(value, 44, INK, ui.bold))
        col.addView(ui.text(caption, 17, MUTED, ui.regular), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = ui.ipx(6)
        })
        return col
    }

    private fun workoutRow(w: Workout): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(CARD, 20)
            setPadding(ui.ipx(26), ui.ipx(18), ui.ipx(26), ui.ipx(18))
        }
        card.addView(ui.text(dateFormat.format(Date(w.startedAt)) + "   ·   " + w.label, 20, MUTED, ui.medium))
        card.addView(
            ui.text(
                "${formatTime(w.movingMs)}   ·   ${formatNum(w.distanceKm, 2)} km   ·   ${formatNum(w.kcal, 0)} kcal",
                34, INK, ui.bold,
            ),
            ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(8) },
        )
        card.addView(
            ui.text(
                "átlag ${formatNum(w.avgSpeed, 1)} km/h   ·   max ${formatNum(w.maxSpeed, 1)} km/h   ·   fokozat ${w.level}",
                19, MUTED, ui.regular,
            ),
            ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(8) },
        )
        card.setOnLongClickListener {
            confirmDelete(w)
            true
        }
        return card
    }

    private fun confirmDelete(w: Workout) {
        AlertDialog.Builder(this)
            .setTitle("Törlöd ezt az edzést?")
            .setMessage(dateFormat.format(Date(w.startedAt)) + ", " + formatNum(w.distanceKm, 2) + " km")
            .setPositiveButton("Törlés") { _, _ ->
                Db.get(this).deleteWorkout(w.id)
                build()
            }
            .setNegativeButton("Mégse", null)
            .show()
    }

    // Összesített idő: "3 ó 25 p" vagy "25 p"
    private fun formatDuration(ms: Long): String {
        val min = ms / 60_000
        return if (min >= 60) "${min / 60} ó ${min % 60} p" else "$min p"
    }

    companion object {
        private const val DAYS = 14
    }
}
