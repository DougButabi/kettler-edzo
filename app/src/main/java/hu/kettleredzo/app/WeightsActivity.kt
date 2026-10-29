package hu.kettleredzo.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// A súlyzós edzés kezdőképernyője, az elliptikus edzésképernyő mintájára: bal oldalt a fotó,
// jobb oldalt a kiválasztott nap gyakorlatai és sorozatai, alul a napválasztó és az Indítás.
// Az edzésterv, a gyakorlatok és a gépek a MENÜ gombról érhetők el.
class WeightsActivity : KettlerActivity() {
    private lateinit var ui: Ui
    private lateinit var profile: Profile
    private var days = emptyList<PlanDay>()
    private var active: Workout? = null
    private var selectedDayId = -1L
    private var recommendedId = -1L

    private lateinit var nameV: TextView
    private lateinit var headV: TextView
    private lateinit var titleV: TextView
    private lateinit var list: LinearLayout
    private lateinit var dayRow: LinearLayout
    private lateinit var statusV: TextView
    private lateinit var discardBtn: View
    private lateinit var mainBtn: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Db.get(this).profile(Prefs.currentProfileId(this))
        if (p == null) {
            finish()
            return
        }
        profile = p
        ui = Ui(this)
        selectedDayId = savedInstanceState?.getLong(STATE_DAY, -1L) ?: -1L
        setContentView(buildUi())
    }

    // Forgatáskor (telefonon) a kiválasztott nap megmarad.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_DAY, selectedDayId)
    }

    override fun onResume() {
        super.onResume()
        if (!::ui.isInitialized) return
        // A menüből vagy edzésből visszatérve a terv és az állapot megváltozhatott.
        val db = Db.get(this)
        days = db.planDays(profile.id)
        active = db.activeLift(profile.id)
        recommendedId = recommendedDay(days, db.lastLiftLetter(profile.id))?.id ?: -1
        if (days.none { it.id == selectedDayId }) selectedDayId = recommendedId
        render()
    }

    // A legutóbb edzett nap utáni nap, körbeérve; ha még nem volt edzés, az első.
    private fun recommendedDay(days: List<PlanDay>, last: String?): PlanDay? {
        val i = days.indexOfFirst { it.letter == last }
        return if (i < 0) days.firstOrNull() else days[(i + 1) % days.size]
    }

    // ---- Kirajzolás ----

    private fun render() {
        list.removeAllViews()
        val a = active
        if (a != null) renderActive(a) else renderPlan()
        renderDays()
    }

    private fun renderPlan() {
        val day = days.firstOrNull { it.id == selectedDayId }
        discardBtn.visibility = View.GONE
        mainButton("Indítás")
        if (day == null) {
            headV.text = "MÉG NINCS EDZÉSTERVED"
            titleV.text = "Kezdjük!"
            list.addView(ui.note("A MENÜ gombbal készíthetsz edzéstervet, vagy betölthetsz egy kész tervet."), lineLp())
            statusV.text = ""
            return
        }
        headV.text = if (day.id == recommendedId) "${day.letter} NAP   ·   AJÁNLOTT" else "${day.letter} NAP"
        val sets = day.items.sumOf { it.sets.size }
        titleV.text = "${day.items.size} gyakorlat · $sets sorozat"
        if (day.items.isEmpty()) list.addView(ui.note("Ebben a napban még nincs gyakorlat. A MENÜ → Edzésterv alatt adhatsz hozzá."), lineLp())
        val db = Db.get(this)
        day.items.forEachIndexed { i, item ->
            val ex = db.exercise(item.exerciseId)
            list.addView(exerciseRow(i, ex, setsText(item.sets) + "   ·   pihenő ${item.restSec} mp", null), lineLp())
        }
        statusV.text = ""
    }

    // Folyamatban lévő edzés: a sorozatok állása gyakorlatonként, Folytatás / Elvetés
    private fun renderActive(a: Workout) {
        val db = Db.get(this)
        val sets = db.liftSets(a.id)
        headV.text = "${a.title ?: "?"} NAP   ·   FOLYAMATBAN"
        titleV.text = "${sets.count { it.done }}/${sets.size} sorozat kész"
        for ((i, s) in sets.groupBy { it.itemPos }.toSortedMap().values.withIndex()) {
            val ex = db.exercise(s.first().exerciseId)
            val done = s.count { it.done }
            val text = s.joinToString(", ") { "${it.reps} × ${kgText(it.kg)} kg" }
            list.addView(exerciseRow(i, ex, text, if (done == s.size) "✓" else "$done/${s.size}"), lineLp())
        }
        discardBtn.visibility = View.VISIBLE
        mainButton("Folytatás")
        statusV.text = "Edzés közben nem lehet napot váltani."
    }

    private fun exerciseRow(index: Int, ex: Exercise?, details: String, badge: String?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(ui.picture(ex?.image, ex?.name ?: "?", 84))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.text("${index + 1}. ${ex?.name ?: "?"}", 28, INK, ui.bold))
        col.addView(ui.text(details, 19, MUTED, ui.regular), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(6) })
        row.addView(col, ui.lp(0, WRAP_CONTENT, 1f).apply { leftMargin = ui.ipx(22) })
        if (badge != null) {
            row.addView(ui.text(badge, 22, INK, ui.bold).apply {
                gravity = Gravity.CENTER
                background = ui.rounded(if (badge == "✓") GREEN else PILL, 24)
                setPadding(ui.ipx(18), ui.ipx(8), ui.ipx(18), ui.ipx(8))
            })
        }
        return row
    }

    // Alsó sáv: a terv napjai (A, B, C…) kerek gombokként; edzés közben csak az aktuális nap látszik.
    private fun renderDays() {
        dayRow.removeAllViews()
        val a = active
        val shown = if (a != null) days.filter { it.letter == a.title } else days
        for (d in shown) {
            val chosen = if (a != null) true else d.id == selectedDayId
            val b = ui.text(d.letter, 34, if (chosen) INK else Color.WHITE, ui.bold).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (chosen) YELLOW else BAR_LINE)
                }
                setOnClickListener {
                    if (active == null) {
                        selectedDayId = d.id
                        render()
                    }
                }
            }
            dayRow.addView(b, ui.lp(ui.ipx(72), ui.ipx(72)).apply { rightMargin = ui.ipx(14) })
        }
    }

    private fun mainButton(text: String) {
        (mainBtn.getChildAt(1) as TextView).text = text
    }

    private fun lineLp() = ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = ui.ipx(18) }

    // ---- Gombok ----

    private fun onMainButton() {
        val a = active
        if (a != null) {
            openLift(a.id)
            return
        }
        val day = days.firstOrNull { it.id == selectedDayId }
        if (day == null) {
            openMenu()
            return
        }
        if (day.items.isEmpty()) {
            ui.toast("Ebben a napban még nincs gyakorlat. A MENÜ → Edzésterv alatt adhatsz hozzá.")
            return
        }
        openLift(Db.get(this).startLift(profile.id, day))
    }

    private fun openLift(workoutId: Long) {
        startActivity(Intent(this, LiftActivity::class.java).putExtra(LiftActivity.EXTRA_WORKOUT_ID, workoutId))
    }

    private fun openMenu() {
        startActivity(Intent(this, WeightsMenuActivity::class.java))
    }

    private fun confirmDiscard() {
        val a = active ?: return
        AlertDialog.Builder(this)
            .setTitle("Elveted a folyamatban lévő edzést?")
            .setMessage("Nem kerül be a naplóba.")
            .setPositiveButton("Elvetés") { _, _ ->
                Db.get(this).discardLift(a.id)
                active = null
                render()
            }
            .setNegativeButton("Mégse", null)
            .show()
    }

    private fun openProfiles() {
        startActivity(Intent(this, ProfileActivity::class.java))
        finishAffinity()
    }

    // ---- Felület felépítése ----

    private fun buildUi(): View =
        ui.homeScreen(R.drawable.sulyzos, PHOTO_LEFT, PHOTO_RIGHT, buildTopBar(), buildPanel(), buildBottomBar(), BAR_H)

    private fun buildTopBar(): View {
        // A profil neve nagyban; koppintásra profilváltás
        nameV = ui.text(profile.name, 46, INK, ui.bold).apply { setOnClickListener { openProfiles() } }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(ui.pill("NAPLÓ") { startActivity(Intent(this, LogActivity::class.java)) })
        buttons.addView(ui.pill("MENÜ") { openMenu() }, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(14) })
        return ui.topBar(ui.logo("SÚLYZÓS EDZÉS", nameV), null, buttons)
    }

    private fun buildPanel(): View {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headV = ui.label("")
        panel.addView(headV)
        titleV = ui.text("", 56, INK, ui.bold)
        panel.addView(titleV, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(10) })
        panel.addView(ui.line(), ui.lp(MATCH_PARENT, 1).apply {
            topMargin = ui.ipx(20)
            bottomMargin = ui.ipx(20)
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(ScrollView(this).apply { addView(list) }, ui.lp(MATCH_PARENT, 0, 1f))
        return panel
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dayCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dayCol.addView(ui.text("NAP", 17, BAR_MUTED, ui.medium, 0.12f).apply {
            ellipsize = null
            setPadding(0, 0, ui.ipx(10), 0)
        })
        dayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dayCol.addView(dayRow, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(6) })
        bar.addView(dayCol)

        statusV = ui.text("", 20, BAR_MUTED, ui.medium).apply { gravity = Gravity.END }
        bar.addView(statusV, ui.lp(0, WRAP_CONTENT, 1f).apply {
            leftMargin = ui.ipx(30)
            rightMargin = ui.ipx(30)
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val discard = ui.pillButton(R.drawable.ic_stop, "Elvetés", BAR_LINE, Color.WHITE) { confirmDiscard() }
        discardBtn = discard
        buttons.addView(discard, ui.lp(ui.ipx(250), ui.ipx(85)).apply { rightMargin = ui.ipx(20) })

        mainBtn = ui.pillButton(R.drawable.ic_play, "Indítás", BUTTON, INK) { onMainButton() }
        buttons.addView(mainBtn, ui.lp(ui.ipx(290), ui.ipx(85)))
        return ui.bottomBar(bar, buttons)
    }

    companion object {
        private const val BAR_H = 145
        private const val STATE_DAY = "day"

        // A súlyzós fotón a pad és az állvány vízszintes helye (a kép pixeleiben, 1200 px széles kép)
        private const val PHOTO_LEFT = 260f
        private const val PHOTO_RIGHT = 970f
    }
}
