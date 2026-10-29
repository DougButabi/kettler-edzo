package hu.kettleredzo.app

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Edzésmód választása: szabad, táv, kalória, idő, és az elliptikusnál a kézikönyv kezdő programja.
// EXTRA_SPORT = kerékpár esetén a kerékpáros módot állítja (program nélkül).
class ModeActivity : KettlerActivity() {
    private lateinit var ui: Ui
    private lateinit var pane: LinearLayout
    private val modeButtons = mutableMapOf<WorkoutMode, TextView>()

    private var bike = false
    private var mode = WorkoutMode.FREE
    private var week = 1
    private var goalField: EditText? = null
    private var errorV: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bike = intent.getStringExtra(EXTRA_SPORT) == Sport.BIKE.code
        // Edzés közben nem lehet módot váltani.
        if (if (bike) BikeState.running else KettlerState.running) {
            finish()
            return
        }
        ui = Ui(this)
        mode = if (bike) Prefs.bikeMode(this) else Prefs.mode(this)
        week = Prefs.programWeek(this)
        savedInstanceState?.let {
            mode = WorkoutMode.of(it.getString(STATE_MODE))
            week = it.getInt(STATE_WEEK, week)
        }

        val root = ui.splitRoot()
        val left = ui.sidePanel("Edzésmód")
        val nav = ui.nav(left)
        val modes = if (bike) WorkoutMode.entries.filter { it != WorkoutMode.PROGRAM } else WorkoutMode.entries
        modes.forEachIndexed { i, m ->
            val b = ui.navButton(m.title) { select(m) }
            modeButtons[m] = b
            nav.addView(b, ui.navLp(i == 0))
        }
        root.addView(if (ui.portrait) left else ScrollView(this).apply { addView(left) }, ui.sideLp(0.36f))

        pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.paneLayout(pane)
        root.addView(ScrollView(this).apply { addView(pane) }, ui.mainLp(0.64f))
        setContentView(root)
        select(mode)
        savedInstanceState?.getString(STATE_GOAL)?.let { goalField?.setText(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_MODE, mode.code)
        outState.putInt(STATE_WEEK, week)
        goalField?.let { outState.putString(STATE_GOAL, it.text.toString()) }
    }

    private fun select(m: WorkoutMode) {
        mode = m
        modeButtons.forEach { (k, v) -> ui.setChosen(v, k == m) }
        pane.removeAllViews()
        goalField = null
        errorV = null
        when {
            m == WorkoutMode.FREE -> buildFree()
            bike && m == WorkoutMode.DISTANCE -> buildGoal("CÉL (KM)", listOf(10.0, 20.0, 30.0, 50.0), "km",
                "Visszaszámol a hátralévő távval. A cél elérésekor hangjelzés, a tekerés megy tovább.", decimal = true)
            bike && m == WorkoutMode.KCAL -> buildGoal("CÉL (KCAL)", listOf(300.0, 500.0, 1000.0), "kcal",
                "Ez becslés a sebességből és a testsúlyból. A szél és az emelkedő nincs benne.", decimal = false)
            bike && m == WorkoutMode.TIME -> buildGoal("CÉL (PERC)", listOf(30.0, 60.0, 90.0, 120.0), "perc",
                "Az idő csak mozgás közben fogy: megálláskor automatikus szünet.", decimal = false)
            m == WorkoutMode.DISTANCE -> buildGoal("CÉL (KM)", listOf(3.0, 5.0, 10.0, 15.0), "km",
                "Visszaszámol a hátralévő távval.", decimal = true)
            m == WorkoutMode.KCAL -> buildGoal("CÉL (KCAL)", listOf(300.0, 500.0, 1000.0), "kcal",
                "Pulzuspánt nélkül ez becslés: a kézikönyv szerint 1 km kb. 84 kcal.", decimal = false)
            m == WorkoutMode.TIME -> buildGoal("CÉL (PERC)", listOf(15.0, 30.0, 45.0, 60.0), "perc",
                "Az idő csak tekerés közben fogy.", decimal = false)
            else -> buildProgram()
        }
        errorV = ui.text("", 22, DANGER, ui.medium)
        pane.addView(errorV, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(View(this), ui.lp(0, 1, 1f))
        buttons.addView(ui.button("Mégse", PILL, INK) { finish() }, ui.lp(ui.ipx(200), ui.ipx(85)))
        buttons.addView(ui.button("Kiválasztom", BAR, Color.WHITE) { choose() }, ui.lp(ui.ipx(300), ui.ipx(85)).apply {
            leftMargin = ui.ipx(20)
        })
        pane.addView(buttons, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) })
    }

    private fun note(s: String) = ui.note(s)

    private fun buildFree() {
        pane.addView(ui.label(if (bike) "SZABAD TEKERÉS" else "SZABAD EDZÉS"))
        val text = if (bike) {
            "Kötetlen tekerés, cél nélkül. Megálláskor (pl. piros lámpa) az idő magától megáll."
        } else {
            "Kötetlen edzés, cél nélkül. Az idő csak tekerés közben megy."
        }
        pane.addView(note(text), ui.fieldLp())
    }

    private fun buildGoal(title: String, presets: List<Double>, unit: String, hint: String, decimal: Boolean) {
        pane.addView(ui.label(title))
        val type = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_NUMBER
        val current = if (bike) Prefs.bikeGoal(this, mode) else Prefs.goal(this, mode)
        val field = ui.field(goalText(current), "", type, true)
        goalField = field
        pane.addView(field, ui.fieldLp())

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEachIndexed { i, v ->
            val b = ui.choice("${goalText(v)} $unit", 26) { field.setText(goalText(v)) }
            row.addView(b, ui.lp(0, ui.ipx(70), 1f).apply { if (i > 0) leftMargin = ui.ipx(16) })
        }
        pane.addView(row, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(20) })
        pane.addView(note(hint), ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) })
    }

    private fun buildProgram() {
        val done = Db.get(this).completedProgramCount(Prefs.currentProfileId(this))
        val recommended = Programs.recommendedWeek(done)
        pane.addView(ui.label("A KÉZIKÖNYV KEZDŐ PROGRAMJA"))
        pane.addView(note(
            "Heti ${Programs.SESSIONS_PER_WEEK} alkalom. Előtte és utána kb. 5 perc gimnasztika " +
                "bemelegítésnek és levezetésnek. Eddig $done programot csináltál végig.",
        ), ui.fieldLp())

        val cards = mutableListOf<Pair<Int, LinearLayout>>()
        fun refreshCards() = cards.forEach { (w, card) -> styleCard(card, w == week) }
        for (p in Programs.BEGINNER) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.ipx(28), ui.ipx(18), ui.ipx(28), ui.ipx(18))
            }
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(ui.text("${p.week}. hét", 34, INK, ui.bold))
            head.addView(ui.text("   ·   összesen ${p.totalMs / 60_000} perc", 20, MUTED, ui.regular))
            if (p.week == recommended) {
                head.addView(ui.text("AJÁNLOTT", 15, INK, ui.medium, 0.1f).apply {
                    background = ui.rounded(YELLOW, 20)
                    setPadding(ui.ipx(16), ui.ipx(6), ui.ipx(16), ui.ipx(6))
                }, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(20) })
            }
            card.addView(head)
            card.addView(ui.text(p.summary, 22, MUTED, ui.regular), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = ui.ipx(8)
            })
            card.setOnClickListener {
                week = p.week
                refreshCards()
            }
            cards += p.week to card
            pane.addView(card, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(16) })
        }
        refreshCards()
    }

    // A kiválasztott kártya sötét, fehér betűkkel (a sárga AJÁNLOTT címke marad).
    private fun styleCard(card: LinearLayout, chosen: Boolean) {
        card.background = ui.rounded(if (chosen) BAR else CARD, 24)
        fun recolor(v: View) {
            when (v) {
                is LinearLayout -> for (i in 0 until v.childCount) recolor(v.getChildAt(i))
                is TextView -> if (v.text.toString() != "AJÁNLOTT") v.setTextColor(if (chosen) Color.WHITE else INK)
            }
        }
        recolor(card)
    }

    private fun choose() {
        var goal = 0.0
        val field = goalField
        if (field != null) {
            val v = field.text.toString().trim().replace(',', '.').toDoubleOrNull()
            val range = when (mode) {
                WorkoutMode.DISTANCE -> if (bike) 0.5..1000.0 else 0.1..200.0
                WorkoutMode.KCAL -> 10.0..10_000.0
                else -> 1.0..600.0
            }
            if (v == null || v !in range) {
                errorV?.text = "Adj meg egy számot ${goalText(range.start)} és ${goalText(range.endInclusive)} között."
                return
            }
            goal = v
            if (bike) Prefs.setBikeGoal(this, mode, v) else Prefs.setGoal(this, mode, v)
        }
        if (bike) {
            Prefs.setBikeMode(this, mode)
            BikeState.configure(mode, goal)
        } else {
            Prefs.setMode(this, mode)
            Prefs.setProgramWeek(this, week)
            KettlerState.configure(mode, goal, Programs.week(week))
            KettlerState.resetCounters()
        }
        finish()
    }

    companion object {
        const val EXTRA_SPORT = "sport"
        private const val STATE_MODE = "mode"
        private const val STATE_WEEK = "week"
        private const val STATE_GOAL = "goal"
    }
}
