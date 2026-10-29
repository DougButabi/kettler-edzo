package hu.kettleredzo.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date

// A súlyzós modul kezdőképernyője: bal oldalt a menü, jobb oldalt a kiválasztott rész
// (edzés indítása, edzésterv, gyakorlatok, gépek).
class WeightsActivity : KettlerActivity() {
    private enum class Section(val title: String) {
        WORKOUT("Edzés"),
        PLAN("Edzésterv"),
        EXERCISES("Gyakorlatok"),
        EQUIPMENT("Gépek"),
    }

    private lateinit var ui: Ui
    private lateinit var profile: Profile
    private lateinit var pane: LinearLayout
    private val navButtons = mutableMapOf<Section, TextView>()
    private var section = Section.WORKOUT

    private val timeFormat = SimpleDateFormat("MM. dd. HH:mm", HU)

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
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG)
        }
        val left = ui.sidePanel("Súlyzós\nedzés")
        left.addView(ui.text(profile.name, 26, MUTED, ui.medium), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = ui.ipx(14)
        })
        for (s in Section.entries) {
            val b = ui.choice(s.title) { show(s) }
            navButtons[s] = b
            left.addView(b, ui.lp(MATCH_PARENT, ui.ipx(78)).apply { topMargin = ui.ipx(if (s.ordinal == 0) 30 else 14) })
        }
        left.addView(View(this), ui.lp(MATCH_PARENT, 0, 1f))
        left.addView(ui.button("← Vissza", PILL, INK) { finish() }, ui.lp(MATCH_PARENT, ui.ipx(78)))
        root.addView(left, ui.lp(0, MATCH_PARENT, 0.34f))

        pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.ipx(40), ui.ipx(120), ui.ipx(60), ui.ipx(60))
        }
        root.addView(ScrollView(this).apply { addView(pane) }, ui.lp(0, MATCH_PARENT, 0.66f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (::ui.isInitialized) show(section) // a szerkesztő képernyőkről visszatérve frissül
    }

    private fun show(s: Section) {
        section = s
        navButtons.forEach { (k, v) -> ui.setChosen(v, k == s) }
        pane.removeAllViews()
        when (s) {
            Section.WORKOUT -> buildWorkout()
            Section.PLAN -> buildPlan()
            Section.EXERCISES -> buildExercises()
            Section.EQUIPMENT -> buildEquipment()
        }
    }

    // ---- Edzés ----

    private fun buildWorkout() {
        val db = Db.get(this)
        val active = db.activeLift(profile.id)
        if (active != null) {
            pane.addView(ui.label("FOLYAMATBAN LÉVŐ EDZÉS"))
            val card = card()
            card.addView(ui.text("${active.title ?: "?"} nap", 40, INK, ui.bold))
            val s = db.liftSummary(active.id)
            card.addView(
                ui.text("Indítva: ${timeFormat.format(Date(active.startedAt))}   ·   ${s.doneSets}/${s.totalSets} sorozat kész", 22, MUTED, ui.regular),
                ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(8) },
            )
            pane.addView(card, ui.fieldLp())
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(ui.button("Elvetés", DANGER, Color.WHITE) { confirmDiscard(active.id) }, ui.lp(ui.ipx(220), ui.ipx(85)))
            buttons.addView(View(this), ui.lp(0, 1, 1f))
            buttons.addView(ui.button("Folytatás", BAR, Color.WHITE) { openLift(active.id) }, ui.lp(ui.ipx(300), ui.ipx(85)))
            pane.addView(buttons, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(20) })
            pane.addView(ui.note("Új edzést akkor indíthatsz, ha ezt befejezted vagy elvetetted."), sectionLp())
            return
        }

        val days = db.planDays(profile.id)
        if (days.isEmpty()) {
            pane.addView(ui.label("MÉG NINCS EDZÉSTERVED"))
            pane.addView(ui.note("Készíts egyet, vagy indulj egy kész tervből. Utána itt választod ki, melyik napot edzed."), ui.fieldLp())
            pane.addView(ui.button("Edzésterv készítése", BAR, Color.WHITE) { show(Section.PLAN) },
                ui.lp(ui.ipx(380), ui.ipx(85)).apply { topMargin = ui.ipx(24) })
            return
        }

        pane.addView(ui.label("MELYIK NAPOT EDZED?"))
        val recommended = recommendedDay(days, db.lastLiftLetter(profile.id))
        for (d in days) {
            val card = dayCard(d, d.id == recommended?.id)
            card.setOnClickListener { startLift(d) }
            pane.addView(card, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(16) })
        }
        pane.addView(ui.note("Koppints a napra az edzés indításához. Az ajánlott nap a legutóbbi utáni."), sectionLp())
    }

    // A legutóbb edzett nap utáni nap, körbeérve; ha még nem volt edzés, az első.
    private fun recommendedDay(days: List<PlanDay>, last: String?): PlanDay? {
        val i = days.indexOfFirst { it.letter == last }
        return if (i < 0) days.firstOrNull() else days[(i + 1) % days.size]
    }

    private fun startLift(day: PlanDay) {
        if (day.items.isEmpty()) {
            ui.toast("Ebben a napban még nincs gyakorlat. Az Edzéstervben adhatsz hozzá.")
            return
        }
        openLift(Db.get(this).startLift(profile.id, day))
    }

    private fun openLift(workoutId: Long) {
        startActivity(Intent(this, LiftActivity::class.java).putExtra(LiftActivity.EXTRA_WORKOUT_ID, workoutId))
    }

    private fun confirmDiscard(workoutId: Long) {
        AlertDialog.Builder(this)
            .setTitle("Elveted a folyamatban lévő edzést?")
            .setMessage("Nem kerül be a naplóba.")
            .setPositiveButton("Elvetés") { _, _ ->
                Db.get(this).discardLift(workoutId)
                show(section)
            }
            .setNegativeButton("Mégse", null)
            .show()
    }

    // ---- Edzésterv ----

    private fun buildPlan() {
        val db = Db.get(this)
        val days = db.planDays(profile.id)
        if (days.isNotEmpty()) {
            pane.addView(ui.label("${profile.name.uppercase()} EDZÉSTERVE"))
            for (d in days) {
                val card = dayCard(d, false)
                card.setOnClickListener { editDay(d.id) }
                pane.addView(card, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(16) })
            }
            pane.addView(ui.button("+ Új nap (${Lifting.nextLetter(days)})", BAR, Color.WHITE) { addDay(days) },
                ui.lp(ui.ipx(320), ui.ipx(85)).apply { topMargin = ui.ipx(24) })
            pane.addView(ui.note("Koppints egy napra a gyakorlatok, sorozatok és súlyok szerkesztéséhez."), ui.fieldLp())
            pane.addView(ui.label("KÉSZ TERV BETÖLTÉSE"), sectionLp())
            pane.addView(ui.note("A mostani tervedet lecseréli. A naplóban lévő edzések megmaradnak."), ui.fieldLp())
        } else {
            pane.addView(ui.label("KÉSZ TERVEK"))
            pane.addView(ui.note("Válassz egyet kiindulásnak, utána mindent átírhatsz. A súlyok csak becslések, állítsd őket magadra."), ui.fieldLp())
        }
        for (t in Catalog.TEMPLATES) {
            val card = card()
            card.addView(ui.text(t.title, 32, INK, ui.bold))
            card.addView(ui.note(t.description, 2), ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(8) })
            card.setOnClickListener { useTemplate(t, days.isNotEmpty()) }
            pane.addView(card, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(16) })
        }
        if (days.isEmpty()) {
            pane.addView(ui.button("Üres terv (A nap)", PILL, INK) { addDay(days) },
                ui.lp(ui.ipx(340), ui.ipx(85)).apply { topMargin = ui.ipx(24) })
        }
    }

    private fun useTemplate(t: Catalog.Template, replace: Boolean) {
        val load = {
            Catalog.applyTemplate(this, profile.id, t)
            ui.toast("Betöltve: ${t.title}")
            show(section)
        }
        if (!replace) {
            load()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Lecseréled az edzéstervet?")
            .setMessage("A mostani terv helyére ez kerül: ${t.title}.")
            .setPositiveButton("Csere") { _, _ -> load() }
            .setNegativeButton("Mégse", null)
            .show()
    }

    private fun addDay(days: List<PlanDay>) {
        val id = Db.get(this).savePlanDay(PlanDay(0, profile.id, Lifting.nextLetter(days), (days.maxOfOrNull { it.position } ?: -1) + 1))
        editDay(id)
    }

    private fun editDay(id: Long) {
        startActivity(Intent(this, PlanDayActivity::class.java).putExtra(PlanDayActivity.EXTRA_DAY_ID, id))
    }

    // Egy nap kártyája: nagy betű, a gyakorlatok neve és a sorozatok száma
    private fun dayCard(d: PlanDay, recommended: Boolean): LinearLayout {
        val db = Db.get(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.rounded(CARD, 24)
            setPadding(ui.ipx(28), ui.ipx(18), ui.ipx(28), ui.ipx(18))
        }
        card.addView(ui.text(d.letter, 72, INK, ui.bold).apply {
            gravity = Gravity.CENTER
            minWidth = ui.ipx(90)
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sets = d.items.sumOf { it.sets.size }
        head.addView(ui.text("${d.items.size} gyakorlat · $sets sorozat", 26, INK, ui.bold))
        if (recommended) {
            head.addView(ui.text("AJÁNLOTT", 15, INK, ui.medium, 0.1f).apply {
                background = ui.rounded(YELLOW, 20)
                setPadding(ui.ipx(16), ui.ipx(6), ui.ipx(16), ui.ipx(6))
            }, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(20) })
        }
        col.addView(head)
        val names = d.items.mapNotNull { db.exercise(it.exerciseId)?.name }
        col.addView(ui.note(names.joinToString(" · ").ifEmpty { "Még nincs gyakorlat." }, 2),
            ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(8) })
        card.addView(col, ui.lp(0, WRAP_CONTENT, 1f).apply { leftMargin = ui.ipx(20) })
        return card
    }

    // ---- Gyakorlatok és gépek ----

    private fun buildExercises() {
        val db = Db.get(this)
        pane.addView(ui.label("GYAKORLATOK"))
        pane.addView(ui.button("+ Új gyakorlat", BAR, Color.WHITE) { editCatalog(CatalogEditActivity.KIND_EXERCISE, null) },
            ui.lp(ui.ipx(320), ui.ipx(85)).apply { topMargin = ui.ipx(16) })
        for (e in db.exercises()) {
            val eq = e.equipmentId?.let { db.equipment(it)?.name } ?: "Gép nélkül"
            pane.addView(catalogRow(e.image, e.name, eq) { editCatalog(CatalogEditActivity.KIND_EXERCISE, e.id) },
                ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(14) })
        }
    }

    private fun buildEquipment() {
        val db = Db.get(this)
        val exercises = db.exercises()
        pane.addView(ui.label("GÉPEK ÉS ESZKÖZÖK"))
        pane.addView(ui.button("+ Új gép", BAR, Color.WHITE) { editCatalog(CatalogEditActivity.KIND_EQUIPMENT, null) },
            ui.lp(ui.ipx(320), ui.ipx(85)).apply { topMargin = ui.ipx(16) })
        for (e in db.equipmentList()) {
            val count = exercises.count { it.equipmentId == e.id }
            pane.addView(catalogRow(e.image, e.name, "$count gyakorlat") { editCatalog(CatalogEditActivity.KIND_EQUIPMENT, e.id) },
                ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(14) })
        }
    }

    private fun catalogRow(image: String?, name: String, caption: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.rounded(CARD, 24)
            setPadding(ui.ipx(18), ui.ipx(14), ui.ipx(28), ui.ipx(14))
            setOnClickListener { onClick() }
        }
        row.addView(ui.picture(image, name, 110))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.text(name, 30, INK, ui.bold))
        col.addView(ui.text(caption, 20, MUTED, ui.regular), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(6) })
        row.addView(col, ui.lp(0, WRAP_CONTENT, 1f).apply { leftMargin = ui.ipx(24) })
        return row
    }

    private fun editCatalog(kind: String, id: Long?) {
        val intent = Intent(this, CatalogEditActivity::class.java).putExtra(CatalogEditActivity.EXTRA_KIND, kind)
        if (id != null) intent.putExtra(CatalogEditActivity.EXTRA_ID, id)
        startActivity(intent)
    }

    // ---- Építőkockák ----

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ui.rounded(CARD, 24)
        setPadding(ui.ipx(28), ui.ipx(18), ui.ipx(28), ui.ipx(18))
    }

    private fun sectionLp() = ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(40) }
}
