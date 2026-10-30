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

// Egy profil napi és heti céljai teljes képernyőn: fent a meglévők a haladással (koppintásra
// szerkeszthető), alatta az új / szerkesztett cél: időszak, sport, mérték és a célérték.
class GoalsActivity : KettlerActivity() {
    private lateinit var ui: Ui
    private lateinit var profile: Profile
    private lateinit var pane: LinearLayout

    // A szerkesztett cél (id = 0: új)
    private var editId = 0L
    private var period = GoalPeriod.WEEK
    private var sport = GoalSport.ELLIPTICAL
    private var metric = GoalMetric.COUNT
    private var target = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Db.get(this).profile(intent.getLongExtra(EXTRA_PROFILE_ID, -1))
        if (p == null) {
            finish()
            return
        }
        profile = p
        ui = Ui(this).apply { settingsMode() }
        val root = ui.splitRoot()
        val left = ui.sidePanel("Célok")
        left.addView(ui.text(p.name, 34, INK, ui.bold), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(20) })
        if (!ui.portrait) {
            left.addView(ui.note(
                "Napi vagy heti célok sportonként, vagy az összes sport kalóriája együtt. A hét hétfőn kezdődik. " +
                    "A haladás a sportválasztón és a ULC-ben is látszik.",
                6,
            ), ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(30) })
        }
        left.addView(View(this), ui.lp(0, 0, 1f))
        left.addView(ui.button("← Vissza", PILL, INK) { finish() }, ui.lp(MATCH_PARENT, ui.ipx(85)))
        root.addView(left, ui.sideLp(0.38f))
        pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.paneLayout(pane)
        root.addView(ScrollView(this).apply { addView(pane) }, ui.mainLp(0.62f))
        setContentView(root)
        render()
    }

    private fun render() {
        pane.removeAllViews()
        val db = Db.get(this)
        val goals = db.goals(profile.id)
        pane.addView(ui.label("CÉLJAIM"))
        if (goals.isEmpty()) pane.addView(ui.note("Még nincs cél."), ui.fieldLp())
        for (g in goals) pane.addView(goalCard(g, db.goalValue(g)), ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(14) })
        buildForm()
    }

    private fun goalCard(g: Goal, value: Double): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(if (g.id == editId) PILL else CARD, 24)
            setPadding(ui.ipx(28), ui.ipx(18), ui.ipx(28), ui.ipx(18))
            setOnClickListener { edit(g) }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(ui.text("${g.title}: ${goalText(g.target)} ${g.metric.unit}", 28, INK, ui.bold).apply { maxLines = 2 },
            ui.lp(0, WRAP_CONTENT, 1f))
        top.addView(ui.pill("TÖRLÉS") {
            Db.get(this).deleteGoal(g.id)
            if (editId == g.id) reset()
            Sync.runAsync(this)
            render()
        })
        card.addView(top)
        // Haladás: sáv és szám
        val p = if (g.target > 0) (value / g.target).toFloat().coerceIn(0f, 1f) else 0f
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ui.rounded(TRACK, 7)
            clipToOutline = true
        }
        bar.addView(View(this).apply { background = ui.rounded(if (p >= 1f) GREEN else YELLOW, 7) }, ui.lp(0, MATCH_PARENT, p))
        bar.addView(View(this), ui.lp(0, MATCH_PARENT, 1f - p))
        card.addView(bar, ui.lp(MATCH_PARENT, ui.ipx(14)).apply { topMargin = ui.ipx(14) })
        val now = if (g.period == GoalPeriod.DAY) "Ma" else "Ezen a héten"
        card.addView(ui.text("$now: ${formatNum(value, g.metric.decimals)} / ${goalText(g.target)} ${g.metric.unit}" +
            if (value >= g.target) "  ✓" else "", 20, MUTED, ui.regular), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(8) })
        return card
    }

    // ---- Új vagy szerkesztett cél ----

    private fun buildForm() {
        pane.addView(ui.label(if (editId == 0L) "ÚJ CÉL" else "CÉL SZERKESZTÉSE"), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(50) })

        pane.addView(choiceRow(GoalPeriod.entries.map { it.title }, GoalPeriod.entries.indexOf(period)) {
            period = GoalPeriod.entries[it]
            rebuild()
        }, ui.fieldLp())
        pane.addView(choiceRow(GoalSport.entries.map { it.title }, GoalSport.entries.indexOf(sport)) {
            sport = GoalSport.entries[it]
            if (metric !in sport.metrics) metric = sport.metrics.first()
            rebuild()
        }, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(20) })
        pane.addView(choiceRow(sport.metrics.map { it.title }, sport.metrics.indexOf(metric)) {
            metric = sport.metrics[it]
            rebuild()
        }, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(20) })

        pane.addView(ui.label("CÉLÉRTÉK (${metric.unit.uppercase()})"), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(30) })
        val field: EditText = ui.field(target, example(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, true).apply {
            id = ID_TARGET
        }
        pane.addView(field, ui.fieldLp())
        val error = ui.text("", 22, DANGER, ui.medium)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (editId != 0L) {
            buttons.addView(ui.button("Mégse", PILL, INK) {
                reset()
                render()
            }, ui.lp(ui.ipx(220), ui.ipx(85)).apply { rightMargin = ui.ipx(20) })
        }
        buttons.addView(ui.button(if (editId == 0L) "Hozzáadás" else "Mentés", BAR, Color.WHITE) {
            val v = field.text.toString().trim().replace(',', '.').toDoubleOrNull()
            if (v == null || v <= 0) {
                error.text = "Adj meg egy pozitív számot."
                return@button
            }
            Db.get(this).saveGoal(Goal(editId, profile.id, sport, metric, period, v))
            Sync.runAsync(this)
            reset()
            render()
        }, ui.lp(ui.ipx(280), ui.ipx(85)))
        pane.addView(buttons, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(30) })
        pane.addView(error, ui.fieldLp())
    }

    // A választók átállításakor a beírt értéket megtartjuk
    private fun rebuild() {
        target = pane.findViewById<EditText>(ID_TARGET)?.text?.toString() ?: target
        render()
    }

    private fun edit(g: Goal) {
        editId = g.id
        period = g.period
        sport = g.sport
        metric = g.metric
        target = goalText(g.target)
        render()
    }

    private fun reset() {
        editId = 0
        target = ""
    }

    private fun example() = "pl. " + when (metric) {
        GoalMetric.KM -> if (period == GoalPeriod.DAY) "5" else "20"
        GoalMetric.MIN -> if (period == GoalPeriod.DAY) "30" else "150"
        GoalMetric.KCAL -> if (period == GoalPeriod.DAY) "500" else "2500"
        GoalMetric.COUNT -> if (period == GoalPeriod.DAY) "1" else "3"
        GoalMetric.SETS -> if (period == GoalPeriod.DAY) "15" else "45"
    }

    // Egymás melletti választógombok; telefonon állóban, ha sok van, két sorban
    private fun choiceRow(titles: List<String>, chosen: Int, onPick: (Int) -> Unit): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val perRow = if (ui.portrait && titles.size > 2) 2 else titles.size
        titles.chunked(perRow).forEachIndexed { r, chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { i, t ->
                val index = r * perRow + i
                val b: TextView = ui.choice(t, 26) { onPick(index) }
                ui.setChosen(b, index == chosen)
                row.addView(b, ui.lp(0, ui.ipx(80), 1f).apply { if (i > 0) leftMargin = ui.ipx(16) })
            }
            // Az utolsó, hiányos sor is ugyanakkora gombokat kapjon
            repeat(perRow - chunk.size) { row.addView(View(this), ui.lp(0, 1, 1f).apply { leftMargin = ui.ipx(16) }) }
            col.addView(row, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { if (r > 0) topMargin = ui.ipx(16) })
        }
        return col
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profileId"
        private const val ID_TARGET = 0x60a101
    }
}
