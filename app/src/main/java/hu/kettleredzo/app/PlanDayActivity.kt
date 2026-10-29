package hu.kettleredzo.app

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

// Az edzésterv egy napjának szerkesztése: gyakorlatok sorrendje, gyakorlatonként a pihenőidő
// és a sorozatok egyenként (ismétlés + súly). A változások csak a Mentés gombra kerülnek el.
class PlanDayActivity : KettlerActivity() {
    private class Item(val id: Long, val exercise: Exercise, var restSec: Int, val sets: MutableList<PlannedSet>)

    private lateinit var ui: Ui
    private lateinit var day: PlanDay
    private val items = mutableListOf<Item>()

    private lateinit var scroll: ScrollView
    private lateinit var pane: LinearLayout
    private var picking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Db.get(this)
        val d = db.planDay(intent.getLongExtra(EXTRA_DAY_ID, -1))
        if (d == null) {
            finish()
            return
        }
        day = d
        ui = Ui(this)
        for (pi in d.items) {
            val ex = db.exercise(pi.exerciseId) ?: continue
            items += Item(pi.id, ex, pi.restSec, pi.sets.toMutableList())
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG)
        }
        val left = ui.sidePanel("${day.letter} nap")
        left.addView(ui.note("Gyakorlatonként a sorozatok egyenként állíthatók, pl. 1 × 8 × 15 kg bemelegítés, aztán 3 × 8 × 40 kg."),
            ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) })
        left.addView(ui.button("+ Gyakorlat", BAR, Color.WHITE) { showPicker() }, ui.lp(MATCH_PARENT, ui.ipx(78)).apply {
            topMargin = ui.ipx(30)
        })
        left.addView(ui.button("Nap törlése", DANGER, Color.WHITE) { confirmDelete() }, ui.lp(MATCH_PARENT, ui.ipx(78)).apply {
            topMargin = ui.ipx(14)
        })
        left.addView(View(this), ui.lp(MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(ui.button("Mégse", PILL, INK) { finish() }, ui.lp(0, ui.ipx(78), 1f))
        buttons.addView(ui.button("Mentés", BAR, Color.WHITE) { save() }, ui.lp(0, ui.ipx(78), 1f).apply {
            leftMargin = ui.ipx(16)
        })
        left.addView(buttons, ui.lp(MATCH_PARENT, WRAP_CONTENT))
        root.addView(left, ui.lp(0, MATCH_PARENT, 0.34f))

        pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.ipx(40), ui.ipx(60), ui.ipx(60), ui.ipx(60))
        }
        scroll = ScrollView(this).apply { addView(pane) }
        root.addView(scroll, ui.lp(0, MATCH_PARENT, 0.66f))
        setContentView(root)
        render()
    }

    // ---- Lista ----

    // Minden változás után újrarajzol; a görgetési helyzet megmarad.
    private fun render(scrollToEnd: Boolean = false) {
        val y = scroll.scrollY
        picking = false
        pane.removeAllViews()
        if (items.isEmpty()) {
            pane.addView(ui.label("MÉG NINCS GYAKORLAT"))
            pane.addView(ui.note("Adj hozzá egyet a bal oldali „+ Gyakorlat” gombbal."), ui.fieldLp())
        }
        items.forEachIndexed { i, item ->
            pane.addView(itemCard(i, item), ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { if (i > 0) topMargin = ui.ipx(20) })
        }
        scroll.post { scroll.scrollTo(0, if (scrollToEnd) pane.height else y) }
    }

    private fun itemCard(index: Int, item: Item): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(CARD, 24)
            setPadding(ui.ipx(22), ui.ipx(18), ui.ipx(22), ui.ipx(18))
        }
        // Fejléc: kép, név, sorrend és törlés
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ui.picture(item.exercise.image, item.exercise.name, 90))
        head.addView(ui.text("${index + 1}. ${item.exercise.name}", 30, INK, ui.bold), ui.lp(0, WRAP_CONTENT, 1f).apply {
            leftMargin = ui.ipx(20)
        })
        head.addView(small("↑") { move(index, -1) }, smallLp())
        head.addView(small("↓") { move(index, 1) }, smallLp())
        head.addView(small("✕") {
            items.removeAt(index)
            render()
        }, smallLp())
        card.addView(head)

        // Pihenő
        val restRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        restRow.addView(ui.label("PIHENŐ"), ui.lp(ui.ipx(150), WRAP_CONTENT))
        restRow.addView(ui.stepper(value("${item.restSec} mp", 130), {
            item.restSec = max(0, item.restSec - Lifting.REST_STEP_SEC)
            render()
        }, {
            item.restSec += Lifting.REST_STEP_SEC
            render()
        }))
        card.addView(restRow, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(14) })

        // Sorozatok
        item.sets.forEachIndexed { j, s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(ui.text("${j + 1}. sorozat", 24, MUTED, ui.medium), ui.lp(ui.ipx(150), WRAP_CONTENT))
            row.addView(ui.stepper(value("${s.reps} ism", 130), {
                item.sets[j] = s.copy(reps = max(1, s.reps - 1))
                render()
            }, {
                item.sets[j] = s.copy(reps = s.reps + 1)
                render()
            }))
            row.addView(ui.text("×", 30, MUTED, ui.regular), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
                leftMargin = ui.ipx(18)
                rightMargin = ui.ipx(18)
            })
            row.addView(ui.stepper(value("${kgText(s.kg)} kg", 140), {
                item.sets[j] = s.copy(kg = max(0.0, s.kg - Lifting.kgStep(s.kg - 0.01)))
                render()
            }, {
                item.sets[j] = s.copy(kg = s.kg + Lifting.kgStep(s.kg))
                render()
            }))
            row.addView(View(this), ui.lp(0, 1, 1f))
            if (item.sets.size > 1) {
                row.addView(small("✕") {
                    item.sets.removeAt(j)
                    render()
                }, smallLp())
            }
            card.addView(row, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(10) })
        }
        card.addView(ui.pill("+ SOROZAT") {
            item.sets += item.sets.lastOrNull() ?: PlannedSet(10, 10.0)
            render()
        }, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(14) })
        return card
    }

    private fun move(index: Int, delta: Int) {
        val to = index + delta
        if (to !in items.indices) return
        items.add(to, items.removeAt(index))
        render()
    }

    private fun value(s: String, width: Int) = ui.text(s, 30, INK, ui.bold).apply { minWidth = ui.ipx(width) }

    private fun small(caption: String, onClick: () -> Unit) = ui.choice(caption, 28) { onClick() }

    private fun smallLp() = ui.lp(ui.ipx(70), ui.ipx(70)).apply { leftMargin = ui.ipx(10) }

    // ---- Gyakorlat választása ----

    private fun showPicker() {
        if (picking) return
        picking = true
        pane.removeAllViews()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ui.label("VÁLASSZ GYAKORLATOT"), ui.lp(0, WRAP_CONTENT, 1f))
        head.addView(ui.pill("MÉGSE") { render() })
        pane.addView(head)
        val db = Db.get(this)
        for (e in db.exercises()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ui.rounded(CARD, 24)
                setPadding(ui.ipx(18), ui.ipx(12), ui.ipx(28), ui.ipx(12))
                setOnClickListener { add(e) }
            }
            row.addView(ui.picture(e.image, e.name, 90))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(ui.text(e.name, 28, INK, ui.bold))
            col.addView(ui.text(e.equipmentId?.let { db.equipment(it)?.name } ?: "Gép nélkül", 19, MUTED, ui.regular),
                ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(4) })
            row.addView(col, ui.lp(0, WRAP_CONTENT, 1f).apply { leftMargin = ui.ipx(20) })
            pane.addView(row, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(12) })
        }
        pane.addView(ui.note("Új gyakorlatot a Súlyzós edzés → Gyakorlatok menüben vehetsz fel."), ui.lp(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = ui.ipx(24)
        })
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun add(e: Exercise) {
        items += Item(0, e, Lifting.DEFAULT_REST_SEC, MutableList(3) { PlannedSet(10, 10.0) })
        render(scrollToEnd = true)
    }

    // ---- Mentés és törlés ----

    private fun save() {
        val planItems = items.map { PlanItem(it.id, it.exercise.id, it.restSec, it.sets.toList()) }
        Db.get(this).savePlanDay(day.copy(items = planItems))
        ui.toast("${day.letter} nap elmentve.")
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Törlöd a(z) ${day.letter} napot?")
            .setMessage("A gyakorlatai is törlődnek a tervből. A naplóban lévő edzések megmaradnak.")
            .setPositiveButton("Törlés") { _, _ ->
                Db.get(this).deletePlanDay(day.id)
                finish()
            }
            .setNegativeButton("Mégse", null)
            .show()
    }

    companion object {
        const val EXTRA_DAY_ID = "dayId"
    }
}
