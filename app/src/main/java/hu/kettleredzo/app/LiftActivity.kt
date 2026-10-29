package hu.kettleredzo.app

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

// Súlyzós edzés közben: bal oldalt a gyakorlatok és az eltelt idő, jobb oldalt az aktuális
// gyakorlat sorozatai. Sorozatonként pipa, vagy előtte a ténylegesen elvégzett ismétlés / súly
// beállítása. Pipálás után pihenő-visszaszámlálás hangjelzéssel. Minden azonnal mentődik.
class LiftActivity : KettlerActivity() {
    private lateinit var ui: Ui
    private lateinit var profile: Profile
    private lateinit var workout: Workout
    private val sets = mutableListOf<LiftSet>()
    private val exercises = mutableMapOf<Long, Exercise>()
    private var current = 0 // az aktuális gyakorlat sorszáma (item_pos)

    private var restEndAt = 0L
    private var lastBeepSec = -1
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var elapsedV: TextView
    private lateinit var kcalV: TextView
    private lateinit var nav: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var pane: LinearLayout
    private lateinit var restBox: LinearLayout
    private lateinit var restV: TextView

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 200)
        }
    }

    private val itemCount get() = (sets.maxOfOrNull { it.itemPos } ?: -1) + 1
    private fun setsOf(item: Int) = sets.filter { it.itemPos == item }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Db.get(this)
        val w = db.workout(intent.getLongExtra(EXTRA_WORKOUT_ID, -1))
        val p = w?.let { db.profile(it.profileId) }
        if (w == null || p == null) {
            finish()
            return
        }
        workout = w
        profile = p
        ui = Ui(this)
        sets += db.liftSets(w.id)
        for (id in sets.map { it.exerciseId }.distinct()) db.exercise(id)?.let { exercises[id] = it }
        // Az első gyakorlat, amelyikben van még hátralévő sorozat
        current = sets.firstOrNull { !it.done }?.itemPos ?: 0
        Beeper.enabled = Prefs.beeps(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        renderNav()
        renderExercise()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---- Felület ----

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG)
        }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.ipx(50), ui.ipx(40), ui.ipx(24), ui.ipx(40))
        }
        left.addView(ui.text("KETTLER", 32, INK, ui.light, 0.35f))
        left.addView(ui.text("${workout.title ?: ""} nap · ${profile.name}", 40, INK, ui.bold), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = ui.ipx(24)
        })
        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        elapsedV = ui.text("0:00", 64, INK, ui.bold)
        stats.addView(elapsedV)
        kcalV = ui.text("", 22, MUTED, ui.regular)
        stats.addView(kcalV, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(20) })
        left.addView(stats, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(12) })

        nav = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(ScrollView(this).apply { addView(nav) }, ui.lp(MATCH_PARENT, 0, 1f).apply { topMargin = ui.ipx(24) })
        left.addView(ui.button("Befejezés", BAR, Color.WHITE) { showSummary() }, ui.lp(MATCH_PARENT, ui.ipx(85)).apply {
            topMargin = ui.ipx(20)
        })
        root.addView(left, ui.lp(0, MATCH_PARENT, 0.33f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.ipx(30), ui.ipx(40), ui.ipx(50), ui.ipx(30))
        }
        // Pihenő-sáv: csak visszaszámlálás közben látszik
        restBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.rounded(YELLOW, 28)
            setPadding(ui.ipx(30), ui.ipx(14), ui.ipx(20), ui.ipx(14))
            visibility = View.GONE
        }
        restBox.addView(ui.label("PIHENŐ"))
        restV = ui.text("0:00", 64, INK, ui.bold)
        restBox.addView(restV, ui.lp(0, WRAP_CONTENT, 1f).apply { leftMargin = ui.ipx(30) })
        restBox.addView(ui.text("+15 mp", 22, INK, ui.medium).apply {
            background = ui.rounded(Color.WHITE, 30)
            setPadding(ui.ipx(24), ui.ipx(14), ui.ipx(24), ui.ipx(14))
            setOnClickListener { restEndAt += 15_000 }
        })
        restBox.addView(ui.text("Kihagyás", 22, INK, ui.medium).apply {
            background = ui.rounded(Color.WHITE, 30)
            setPadding(ui.ipx(24), ui.ipx(14), ui.ipx(24), ui.ipx(14))
            setOnClickListener { stopRest() }
        }, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(14) })
        right.addView(restBox, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = ui.ipx(20) })

        pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(pane) }
        right.addView(scroll, ui.lp(MATCH_PARENT, 0, 1f))
        root.addView(right, ui.lp(0, MATCH_PARENT, 0.67f))
        return root
    }

    // Bal oldali gyakorlatlista: név és a kész / összes sorozat
    private fun renderNav() {
        nav.removeAllViews()
        for (i in 0 until itemCount) {
            val s = setsOf(i)
            val name = exercises[s.first().exerciseId]?.name ?: "?"
            val done = s.count { it.done }
            val b = ui.choice("$name  ${if (done == s.size) "✓" else "$done/${s.size}"}", 22) {
                current = i
                renderNav()
                renderExercise()
            }.apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.ipx(24), 0, ui.ipx(16), 0)
            }
            ui.setChosen(b, i == current)
            nav.addView(b, ui.lp(MATCH_PARENT, ui.ipx(70)).apply { if (i > 0) topMargin = ui.ipx(10) })
        }
    }

    // Jobb oldal: az aktuális gyakorlat képe, neve és sorozatai
    private fun renderExercise() {
        pane.removeAllViews()
        val s = setsOf(current)
        if (s.isEmpty()) return
        val ex = exercises[s.first().exerciseId]
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ui.picture(ex?.image, ex?.name ?: "?", 220))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.text("${current + 1}/$itemCount. gyakorlat", 20, MUTED, ui.medium))
        col.addView(ui.text(ex?.name ?: "?", 44, INK, ui.bold).apply { maxLines = 2 },
            ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(8) })
        val eq = ex?.equipmentId?.let { Db.get(this).equipment(it)?.name }
        col.addView(ui.text(listOfNotNull(eq, "pihenő ${s.first().restSec} mp").joinToString("   ·   "), 22, MUTED, ui.regular),
            ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(8) })
        head.addView(col, ui.lp(0, WRAP_CONTENT, 1f).apply { leftMargin = ui.ipx(30) })
        pane.addView(head)

        for (set in s) pane.addView(setRow(set), ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(14) })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (current > 0) buttons.addView(ui.pill("← ELŐZŐ GYAKORLAT") { go(current - 1) })
        buttons.addView(View(this), ui.lp(0, 1, 1f))
        if (current < itemCount - 1) buttons.addView(ui.pill("KÖVETKEZŐ GYAKORLAT →") { go(current + 1) })
        pane.addView(buttons, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) })
        pane.addView(ui.note("A pipa a sorozat elvégzése. Ha mást csináltál, mint a terv, előtte állítsd át az ismétlést vagy a súlyt."),
            ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(20) })
    }

    private fun setRow(set: LiftSet): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.rounded(if (set.done) GREEN else CARD, 24)
            setPadding(ui.ipx(24), ui.ipx(12), ui.ipx(14), ui.ipx(12))
        }
        row.addView(ui.text("${set.setPos + 1}.", 34, INK, ui.bold), ui.lp(ui.ipx(60), WRAP_CONTENT))
        row.addView(ui.stepper(value("${set.reps} ism", 130), { update(set.copy(reps = max(0, set.reps - 1))) }, {
            update(set.copy(reps = set.reps + 1))
        }))
        row.addView(ui.text("×", 30, MUTED, ui.regular), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = ui.ipx(14)
            rightMargin = ui.ipx(14)
        })
        row.addView(ui.stepper(value("${kgText(set.kg)} kg", 140), {
            update(set.copy(kg = max(0.0, set.kg - Lifting.kgStep(set.kg - 0.01))))
        }, {
            update(set.copy(kg = set.kg + Lifting.kgStep(set.kg)))
        }))
        row.addView(View(this), ui.lp(0, 1, 1f))
        val check = ui.text(if (set.done) "✓ Kész" else "✓", 34, if (set.done) Color.WHITE else INK, ui.bold).apply {
            gravity = Gravity.CENTER
            background = ui.rounded(if (set.done) BAR else Color.WHITE, 40)
            setOnClickListener { toggle(set) }
        }
        row.addView(check, ui.lp(ui.ipx(170), ui.ipx(80)))
        return row
    }

    private fun value(s: String, width: Int) = ui.text(s, 30, INK, ui.bold).apply { minWidth = ui.ipx(width) }

    private fun go(item: Int) {
        current = item
        renderNav()
        renderExercise()
        scroll.post { scroll.scrollTo(0, 0) }
    }

    // ---- Sorozatok ----

    private fun update(set: LiftSet) {
        Db.get(this).saveLiftSet(set)
        val i = sets.indexOfFirst { it.id == set.id }
        sets[i] = set.copy(updatedAt = System.currentTimeMillis())
        val y = scroll.scrollY
        renderExercise()
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun toggle(set: LiftSet) {
        val done = !set.done
        update(set.copy(done = done))
        renderNav()
        if (!done) return
        val remaining = sets.count { !it.done }
        if (remaining == 0) {
            stopRest()
            ui.toast("Minden sorozat kész! Nyomd meg a Befejezést.")
            return
        }
        startRest(set.restSec)
        // Ha a gyakorlat minden sorozata kész, a pihenő alatt már a következő látszik.
        if (setsOf(current).all { it.done }) {
            val next = (1..itemCount).map { (current + it) % itemCount }.firstOrNull { i -> setsOf(i).any { !it.done } }
            if (next != null) go(next)
        }
    }

    // ---- Pihenő és idő ----

    private fun startRest(sec: Int) {
        if (sec <= 0) return
        restEndAt = System.currentTimeMillis() + sec * 1000L
        lastBeepSec = -1
        restBox.visibility = View.VISIBLE
        tick()
    }

    private fun stopRest() {
        restEndAt = 0
        restBox.visibility = View.GONE
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val elapsed = now - workout.startedAt
        elapsedV.text = formatTime(elapsed)
        kcalV.text = "~${formatNum(Lifting.kcal(profile.weightKg, elapsed), 0)} kcal (becslés)"
        if (restEndAt == 0L) return
        val left = restEndAt - now
        restV.text = formatTimeUp(max(0L, left))
        val sec = ((left + 999) / 1000).toInt()
        if (sec != lastBeepSec) {
            lastBeepSec = sec
            when {
                sec in 1..3 -> Beeper.short()
                sec <= 0 -> {
                    Beeper.long()
                    stopRest()
                }
            }
        }
    }

    // ---- Befejezés ----

    private fun showSummary() {
        if (sets.none { it.done }) {
            AlertDialog.Builder(this)
                .setTitle("Még nincs elvégzett sorozat")
                .setMessage("Elveted ezt az edzést? Nem kerül be a naplóba.")
                .setPositiveButton("Elvetés") { _, _ ->
                    Db.get(this).discardLift(workout.id)
                    finish()
                }
                .setNegativeButton("Mégse", null)
                .show()
            return
        }
        stopRest()
        val end = endTime()
        val ms = end - workout.startedAt
        val done = sets.filter { it.done }
        pane.removeAllViews()
        pane.addView(ui.text("Szép munka!", 56, INK, ui.bold))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(stat(formatTime(ms), "idő"), ui.lp(0, WRAP_CONTENT, 1f))
        row.addView(stat("${done.size}/${sets.size}", "sorozat"), ui.lp(0, WRAP_CONTENT, 1f))
        row.addView(stat(formatNum(done.sumOf { it.reps * it.kg }, 0), "kg összesen"), ui.lp(0, WRAP_CONTENT, 1f))
        row.addView(stat(formatNum(Lifting.kcal(profile.weightKg, ms), 0), "kcal (becslés)"), ui.lp(0, WRAP_CONTENT, 1f))
        pane.addView(row, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) })

        // Emelési javaslat: ahol minden sorozat megvolt legalább a tervezett ismétléssel
        val raise = mutableMapOf<Long, Boolean>()
        val candidates = (0 until itemCount).map { setsOf(it) }.filter { s ->
            s.all { it.done && it.reps >= it.plannedReps } && s.any { it.plannedKg > 0 } && s.first().planItemId > 0
        }
        if (candidates.isNotEmpty()) {
            pane.addView(ui.label("EMELÉS A KÖVETKEZŐ ALKALOMRA"), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(40) })
            pane.addView(ui.note("Ezeknél minden sorozat meglett. Emeljük a súlyt a tervben?"), ui.fieldLp())
            for (s in candidates) {
                val planItemId = s.first().planItemId
                raise[planItemId] = true
                val maxKg = s.maxOf { it.plannedKg }
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                line.addView(ui.text(exercises[s.first().exerciseId]?.name ?: "?", 28, INK, ui.bold), ui.lp(0, WRAP_CONTENT, 1f))
                val yes = ui.choice("+${kgText(Lifting.increase(maxKg))} kg", 24) {}
                val no = ui.choice("Marad", 24) {}
                fun refresh() {
                    ui.setChosen(yes, raise[planItemId] == true)
                    ui.setChosen(no, raise[planItemId] != true)
                }
                yes.setOnClickListener { raise[planItemId] = true; refresh() }
                no.setOnClickListener { raise[planItemId] = false; refresh() }
                refresh()
                line.addView(yes, ui.lp(ui.ipx(170), ui.ipx(70)))
                line.addView(no, ui.lp(ui.ipx(170), ui.ipx(70)).apply { leftMargin = ui.ipx(14) })
                pane.addView(line, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(14) })
            }
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(ui.button("Vissza az edzéshez", PILL, INK) { renderExercise() }, ui.lp(ui.ipx(360), ui.ipx(85)))
        buttons.addView(View(this), ui.lp(0, 1, 1f))
        buttons.addView(ui.button("Mentés a naplóba", BAR, Color.WHITE) { finishWorkout(ms, raise) }, ui.lp(ui.ipx(360), ui.ipx(85)))
        pane.addView(buttons, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(40) })
        scroll.post { scroll.scrollTo(0, 0) }
    }

    // Ha jóval az utolsó pipa után zárják le (pl. elfelejtették), nem számoljuk bele az üresjáratot.
    private fun endTime(): Long {
        val now = System.currentTimeMillis()
        val lastTick = sets.filter { it.done }.maxOfOrNull { it.updatedAt } ?: now
        return min(now, lastTick + Lifting.MAX_TAIL_MS)
    }

    private fun stat(value: String, caption: String): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.text(value, 44, INK, ui.bold))
        col.addView(ui.text(caption, 17, MUTED, ui.regular), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(6) })
        return col
    }

    private fun finishWorkout(ms: Long, raise: Map<Long, Boolean>) {
        val db = Db.get(this)
        db.updateWorkout(
            workout.copy(
                movingMs = ms,
                kcal = Lifting.kcal(profile.weightKg, ms),
                completed = sets.all { it.done },
            ),
        )
        raise.filterValues { it }.keys.forEach { db.raisePlanItem(it) }
        ui.toast("Edzés elmentve a naplóba.")
        finish()
    }

    companion object {
        const val EXTRA_WORKOUT_ID = "workoutId"
    }
}
