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

// A súlyzós edzés menüje (a Beállítások mintájára): bal oldalt a menüpontok,
// jobb oldalt az edzésterv, a gyakorlatok vagy a gépek.
class WeightsMenuActivity : KettlerActivity() {
    private enum class Section(val title: String) {
        PLAN("Edzésterv"),
        EXERCISES("Gyakorlatok"),
        EQUIPMENT("Gépek"),
    }

    private lateinit var ui: Ui
    private lateinit var profile: Profile
    private lateinit var pane: LinearLayout
    private val navButtons = mutableMapOf<Section, TextView>()
    private var section = Section.PLAN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Db.get(this).profile(Prefs.currentProfileId(this))
        if (p == null) {
            finish()
            return
        }
        profile = p
        ui = Ui(this)
        savedInstanceState?.getString(STATE_SECTION)?.let { name -> Section.entries.firstOrNull { it.name == name }?.let { section = it } }

        val root = ui.splitRoot()
        val left = ui.sidePanel("Súlyzós\nmenü")
        val nav = ui.nav(left)
        if (ui.portrait) nav.addView(ui.navButton("← Vissza") { finish() }, ui.navLp(true))
        for (s in Section.entries) {
            val b = ui.navButton(s.title) { show(s) }
            navButtons[s] = b
            nav.addView(b, ui.navLp(s.ordinal == 0 && !ui.portrait))
        }
        if (!ui.portrait) {
            left.addView(View(this), ui.lp(MATCH_PARENT, 0, 1f))
            left.addView(ui.button("← Vissza", PILL, INK) { finish() }, ui.lp(MATCH_PARENT, ui.ipx(78)))
        }
        root.addView(left, ui.sideLp(0.34f))

        pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.paneLayout(pane)
        root.addView(ScrollView(this).apply { addView(pane) }, ui.mainLp(0.66f))
        setContentView(root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SECTION, section.name)
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
            Section.PLAN -> buildPlan()
            Section.EXERCISES -> buildExercises()
            Section.EQUIPMENT -> buildEquipment()
        }
    }

    // ---- Edzésterv ----

    private fun buildPlan() {
        val db = Db.get(this)
        val days = db.planDays(profile.id)
        if (days.isNotEmpty()) {
            pane.addView(ui.label("${profile.name.uppercase()} EDZÉSTERVE"))
            for (d in days) {
                val card = dayCard(d)
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
    private fun dayCard(d: PlanDay): LinearLayout {
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
        val sets = d.items.sumOf { it.sets.size }
        col.addView(ui.text("${d.items.size} gyakorlat · $sets sorozat", 26, INK, ui.bold))
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

    companion object {
        private const val STATE_SECTION = "section"
    }
}
