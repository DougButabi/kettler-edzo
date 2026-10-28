package hu.kettleredzo.app

import android.app.Activity
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

// Edzésmód választása: szabad, táv, kalória, idő vagy a kézikönyv kezdő programja.
class ModeActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var right: LinearLayout
    private val modeButtons = mutableMapOf<WorkoutMode, TextView>()

    private var mode = WorkoutMode.FREE
    private var week = 1
    private var goalField: EditText? = null
    private var errorV: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edzés közben nem lehet módot váltani.
        if (KettlerState.running) {
            finish()
            return
        }
        ui = Ui(this)
        mode = Prefs.mode(this)
        week = Prefs.programWeek(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG)
        }

        val left = ui.sidePanel("Edzésmód")
        for (m in WorkoutMode.entries) {
            val b = ui.choice(m.title) { select(m) }
            modeButtons[m] = b
            left.addView(b, ui.lp(MATCH_PARENT, ui.ipx(78)).apply { topMargin = ui.ipx(if (m.ordinal == 0) 40 else 14) })
        }
        root.addView(ScrollView(this).apply { addView(left) }, ui.lp(0, MATCH_PARENT, 0.36f))

        right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.ipx(40), ui.ipx(120), ui.ipx(60), ui.ipx(60))
        }
        root.addView(ScrollView(this).apply { addView(right) }, ui.lp(0, MATCH_PARENT, 0.64f))
        setContentView(root)
        select(mode)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun select(m: WorkoutMode) {
        mode = m
        modeButtons.forEach { (k, v) -> ui.setChosen(v, k == m) }
        right.removeAllViews()
        goalField = null
        errorV = null
        when (m) {
            WorkoutMode.FREE -> buildFree()
            WorkoutMode.DISTANCE -> buildGoal("CÉL (KM)", listOf(3.0, 5.0, 10.0, 15.0), "km",
                "Visszaszámol a hátralévő távval.", decimal = true)
            WorkoutMode.KCAL -> buildGoal("CÉL (KCAL)", listOf(300.0, 500.0, 1000.0), "kcal",
                "Pulzuspánt nélkül ez becslés: a kézikönyv szerint 1 km kb. 84 kcal.", decimal = false)
            WorkoutMode.TIME -> buildGoal("CÉL (PERC)", listOf(15.0, 30.0, 45.0, 60.0), "perc",
                "Az idő csak tekerés közben fogy.", decimal = false)
            WorkoutMode.PROGRAM -> buildProgram()
        }
        errorV = ui.text("", 22, DANGER, ui.medium)
        right.addView(errorV, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(View(this), ui.lp(0, 1, 1f))
        buttons.addView(ui.button("Mégse", PILL, INK) { finish() }, ui.lp(ui.ipx(200), ui.ipx(85)))
        buttons.addView(ui.button("Kiválasztom", BAR, Color.WHITE) { choose() }, ui.lp(ui.ipx(300), ui.ipx(85)).apply {
            leftMargin = ui.ipx(20)
        })
        right.addView(buttons, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) })
    }

    private fun note(s: String) = ui.text(s, 22, MUTED, ui.regular).apply {
        maxLines = 4
        ellipsize = null
    }

    private fun buildFree() {
        right.addView(ui.label("SZABAD EDZÉS"))
        right.addView(note("Kötetlen edzés, cél nélkül. Az idő csak tekerés közben megy."), ui.fieldLp())
    }

    private fun buildGoal(title: String, presets: List<Double>, unit: String, hint: String, decimal: Boolean) {
        right.addView(ui.label(title))
        val type = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_NUMBER
        val field = ui.field(goalText(Prefs.goal(this, mode)), "", type, true)
        goalField = field
        right.addView(field, ui.fieldLp())

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEachIndexed { i, v ->
            val b = ui.choice("${goalText(v)} $unit", 26) { field.setText(goalText(v)) }
            row.addView(b, ui.lp(0, ui.ipx(70), 1f).apply { if (i > 0) leftMargin = ui.ipx(16) })
        }
        right.addView(row, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(20) })
        right.addView(note(hint), ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) })
    }

    private fun buildProgram() {
        val done = Db.get(this).completedProgramCount(Prefs.currentProfileId(this))
        val recommended = Programs.recommendedWeek(done)
        right.addView(ui.label("A KÉZIKÖNYV KEZDŐ PROGRAMJA"))
        right.addView(note(
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
            right.addView(card, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(16) })
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
                WorkoutMode.DISTANCE -> 0.1..200.0
                WorkoutMode.KCAL -> 10.0..10_000.0
                else -> 1.0..600.0
            }
            if (v == null || v !in range) {
                errorV?.text = "Adj meg egy számot ${goalText(range.start)} és ${goalText(range.endInclusive)} között."
                return
            }
            goal = v
            Prefs.setGoal(this, mode, v)
        }
        Prefs.setMode(this, mode)
        Prefs.setProgramWeek(this, week)
        KettlerState.configure(mode, goal, Programs.week(week))
        KettlerState.resetCounters()
        finish()
    }
}
