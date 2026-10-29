package hu.kettleredzo.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Közös beállítások minden sporthoz: az Általános csoport és annak a sportnak a pontjai,
// ahonnan megnyitották (EXTRA_SPORT). Bal oldalt (állóban fent) a menüpontok, jobb oldalt a tartalom.
// A „Kilépés az appból” az elliptikusnál RESULT_EXIT eredménnyel tér vissza, a kilépést
// (edzés mentése, szolgáltatás leállítása) az edzésképernyő végzi el.
class SettingsActivity : KettlerActivity() {
    private enum class Group(val title: String, val sport: Sport?) {
        ELLIPTICAL("ELLIPTIKUS", Sport.ELLIPTICAL),
        WEIGHTS("SÚLYZÓS", Sport.WEIGHTS),
        BIKE("KERÉKPÁR", Sport.BIKE),
        GENERAL("ÁLTALÁNOS", null),
    }

    private enum class Section(val title: String, val group: Group) {
        CONNECTION("Kapcsolat", Group.ELLIPTICAL),
        DISPLAY("Kijelző", Group.ELLIPTICAL),
        LEVELS("Fékfokozatok", Group.ELLIPTICAL),
        PLAN("Edzésterv", Group.WEIGHTS),
        EXERCISES("Gyakorlatok", Group.WEIGHTS),
        EQUIPMENT("Gépek", Group.WEIGHTS),
        AUTOPAUSE("Automatikus szünet", Group.BIKE),
        MAP("Térkép", Group.BIKE),
        SIGNALS("Jelzések", Group.BIKE),
        SCREEN("Kijelző", Group.BIKE),
        USERS("Felhasználók", Group.GENERAL),
        SOUNDS("Hangjelzések", Group.GENERAL),
        ABOUT("Névjegy", Group.GENERAL),
    }

    private lateinit var ui: Ui
    private lateinit var sport: Sport
    private lateinit var pane: LinearLayout
    private val navButtons = mutableMapOf<Section, TextView>()
    private lateinit var section: Section
    private var statusV: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val listener: () -> Unit = { renderStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        sport = Sport.of(intent.getStringExtra(EXTRA_SPORT))
        val sections = Section.entries.filter { it.group.sport == sport || it.group == Group.GENERAL }
        section = sections.first()
        savedInstanceState?.getString(STATE_SECTION)?.let { name -> sections.firstOrNull { it.name == name }?.let { section = it } }

        val root = ui.splitRoot()
        val left = ui.sidePanel("Beállítások")
        val nav = ui.nav(left)
        if (ui.portrait) nav.addView(ui.navButton("← Vissza") { finish() }, ui.navLp(true))
        var lastGroup: Group? = null
        for (s in sections) {
            // Fekvőben csoportcím a menüpontok fölött
            if (!ui.portrait && s.group != lastGroup) {
                nav.addView(ui.label(s.group.title, MUTED), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
                    topMargin = ui.ipx(if (lastGroup == null) 34 else 26)
                })
            }
            val b = ui.navButton(s.title) { show(s) }
            navButtons[s] = b
            nav.addView(b, if (ui.portrait) ui.navLp(false) else ui.lp(MATCH_PARENT, ui.ipx(70)).apply { topMargin = ui.ipx(10) })
            lastGroup = s.group
        }
        if (!ui.portrait) {
            left.addView(View(this), ui.lp(MATCH_PARENT, 0, 1f))
            left.addView(ui.button("← Vissza", PILL, INK) { finish() }, ui.lp(MATCH_PARENT, ui.ipx(78)).apply { topMargin = ui.ipx(20) })
        }
        root.addView(if (ui.portrait) left else ScrollView(this).apply {
            isFillViewport = true
            addView(left)
        }, ui.sideLp(0.34f))

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
        KettlerState.addListener(listener)
        show(section) // pl. az overlay-engedély, egy profil vagy az edzésterv közben megváltozhatott
    }

    override fun onPause() {
        KettlerState.removeListener(listener)
        super.onPause()
    }

    private fun show(s: Section) {
        section = s
        navButtons.forEach { (k, v) -> ui.setChosen(v, k == s) }
        pane.removeAllViews()
        statusV = null
        when (s) {
            Section.CONNECTION -> buildConnection()
            Section.DISPLAY -> buildDisplay()
            Section.LEVELS -> buildLevels()
            Section.PLAN -> buildPlan()
            Section.EXERCISES -> buildExercises()
            Section.EQUIPMENT -> buildEquipment()
            Section.AUTOPAUSE -> buildAutoPause()
            Section.MAP -> buildMap()
            Section.SIGNALS -> buildSignals()
            Section.SCREEN -> buildRideScreen()
            Section.USERS -> buildUsers()
            Section.SOUNDS -> buildSounds()
            Section.ABOUT -> buildAbout()
        }
    }

    // ==== Elliptikus ====

    private fun buildConnection() {
        pane.addView(ui.label("KÜLSŐ ESZKÖZ IP-CÍME"))
        val host = ui.field(Prefs.host(this), "pl. 192.168.1.50", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, true)
        pane.addView(host, ui.fieldLp())
        pane.addView(ui.button("Mentés", BAR, Color.WHITE) {
            Prefs.setHost(this, host.text.toString())
            startForegroundService(Intent(this, WorkoutService::class.java).setAction(WorkoutService.ACTION_RECONNECT))
            ui.toast("Elmentve, újracsatlakozás…")
        }, ui.lp(ui.ipx(260), ui.ipx(85)).apply { topMargin = ui.ipx(20) })

        pane.addView(ui.label("ÁLLAPOT"), sectionLp())
        val sv = note("")
        statusV = sv
        pane.addView(sv, ui.fieldLp())
        pane.addView(note("A külső eszköz (ESP32) küldi a fordulatszámot, a sebességet és a távot."), sectionLp())
        renderStatus()
    }

    private fun renderStatus() {
        val v = statusV ?: return
        val s = KettlerState
        val host = Prefs.host(this)
        val (text, color) = when {
            host.isBlank() -> "Nincs megadva cím." to DANGER
            s.connected -> "● Kapcsolódva ($host)" to Color.parseColor("#2E7D32")
            else -> "Nincs kapcsolat: $host" + (s.lastError?.let { "\n$it" } ?: "") to DANGER
        }
        v.text = text
        v.setTextColor(color)
    }

    private fun buildDisplay() {
        pane.addView(ui.label("OVERLAY-SÁV"))
        pane.addView(onOff(Prefs.overlay(this)) { Prefs.setOverlay(this, it) }, ui.fieldLp())

        if (Prefs.overlay(this) && !Settings.canDrawOverlays(this)) {
            pane.addView(note("Az overlay-hez engedély kell („Megjelenítés más alkalmazások felett”)."), sectionLp())
            pane.addView(ui.button("Engedély megadása", PILL, INK) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }, ui.lp(ui.ipx(340), ui.ipx(85)).apply { topMargin = ui.ipx(16) })
        }
        pane.addView(note(
            "Edzés közben más appok (pl. videólejátszó) fölött, a képernyő alján mutatja az adatokat. " +
                "Az appon belül rejtve marad.",
        ), sectionLp())
    }

    private fun buildLevels() {
        pane.addView(ui.label("FÉKFOKOZATOK SZÁMA"))
        val value = ui.text(Prefs.levelCount(this).toString(), 50, INK, ui.bold).apply { minWidth = ui.ipx(120) }
        pane.addView(ui.stepper(value, {
            Prefs.setLevelCount(this, Prefs.levelCount(this) - 1)
            value.text = Prefs.levelCount(this).toString()
        }, {
            Prefs.setLevelCount(this, Prefs.levelCount(this) + 1)
            value.text = Prefs.levelCount(this).toString()
        }), ui.fieldLp())
        pane.addView(note("Ahány fokozat a gép fékerő-állítóján van. Az edzésképernyőn ennyiig lehet állítani."), ui.fieldLp())
    }

    // ==== Súlyzós ====

    private fun currentProfile(): Profile? = Db.get(this).profile(Prefs.currentProfileId(this))

    private fun buildPlan() {
        val profile = currentProfile() ?: return noProfile()
        val db = Db.get(this)
        val days = db.planDays(profile.id)
        if (days.isNotEmpty()) {
            pane.addView(ui.label("${profile.name.uppercase()} EDZÉSTERVE"))
            for (d in days) {
                val card = dayCard(d)
                card.setOnClickListener { editDay(d.id) }
                pane.addView(card, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(16) })
            }
            pane.addView(ui.button("+ Új nap (${Lifting.nextLetter(days)})", BAR, Color.WHITE) { addDay(profile, days) },
                ui.lp(ui.ipx(320), ui.ipx(85)).apply { topMargin = ui.ipx(24) })
            pane.addView(note("Koppints egy napra a gyakorlatok, sorozatok és súlyok szerkesztéséhez."), ui.fieldLp())
            pane.addView(ui.label("KÉSZ TERV BETÖLTÉSE"), sectionLp())
            pane.addView(note("A mostani tervedet lecseréli. A naplóban lévő edzések megmaradnak."), ui.fieldLp())
        } else {
            pane.addView(ui.label("KÉSZ TERVEK"))
            pane.addView(note("Válassz egyet kiindulásnak, utána mindent átírhatsz. A súlyok csak becslések, állítsd őket magadra."), ui.fieldLp())
        }
        for (t in Catalog.TEMPLATES) {
            val card = card()
            card.addView(ui.text(t.title, 32, INK, ui.bold))
            card.addView(ui.note(t.description, 2), ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(8) })
            card.setOnClickListener { useTemplate(profile, t, days.isNotEmpty()) }
            pane.addView(card, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(16) })
        }
        if (days.isEmpty()) {
            pane.addView(ui.button("Üres terv (A nap)", PILL, INK) { addDay(profile, days) },
                ui.lp(ui.ipx(340), ui.ipx(85)).apply { topMargin = ui.ipx(24) })
        }
    }

    private fun useTemplate(profile: Profile, t: Catalog.Template, replace: Boolean) {
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

    private fun addDay(profile: Profile, days: List<PlanDay>) {
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

    // ==== Kerékpár ====

    private fun buildAutoPause() {
        pane.addView(ui.label("AUTOMATIKUS SZÜNET"))
        pane.addView(onOff(Prefs.autoPause(this)) { Prefs.setAutoPause(this, it) }, ui.fieldLp())
        pane.addView(ui.label("KÜSZÖB"), sectionLp())
        pane.addView(options(listOf("2 km/h" to 2, "3 km/h" to 3, "5 km/h" to 5), Prefs.autoPauseKmh(this)) {
            Prefs.setAutoPauseKmh(this, it)
        }, ui.fieldLp())
        pane.addView(note(
            "Ez alatt a sebesség alatt áll az idő (pl. piros lámpánál), induláskor magától megy tovább. " +
                "Lassú, emelkedős tekeréshez a 2 km/h, városi forgalomhoz az 5 km/h jobb. A következő tekeréstől érvényes.",
        ), sectionLp())
    }

    private fun buildMap() {
        pane.addView(ui.label("NAGYÍTÁS TEKERÉS KÖZBEN"))
        pane.addView(options(listOf("Közeli" to 17.0, "Közepes" to 15.0, "Távoli" to 13.0), Prefs.mapZoom(this)) {
            Prefs.setMapZoom(this, it)
        }, ui.fieldLp())
        pane.addView(note("Közeli: utcaszint. Távoli: a környék. Két ujjal tekerés közben is állíthatod."), ui.fieldLp())

        pane.addView(ui.label("KÖVETÉS VISSZAKAPCSOLÁSA"), sectionLp())
        pane.addView(options(listOf("10 mp" to 10, "30 mp" to 30, "Soha" to 0), Prefs.followResumeSec(this)) {
            Prefs.setFollowResumeSec(this, it)
        }, ui.fieldLp())
        pane.addView(note("Ha kézzel elhúzod a térképet, ennyi idő múlva újra a helyedre áll. „Soha”: csak a Követés gombra."), ui.fieldLp())

        pane.addView(ui.label("TÉRKÉP TÁROLÁSA"), sectionLp())
        val size = ui.text("", 30, INK, ui.bold)
        fun refresh() {
            size.text = "${formatNum(Maps.tileBytes(this) / 1_048_576.0, 1)} MB letöltött térkép"
        }
        refresh()
        pane.addView(size, ui.fieldLp())
        pane.addView(ui.button("Törlés", PILL, INK) {
            Maps.clearTiles(this)
            refresh()
            ui.toast("Térkép törölve. Legközelebb újra letölti, amit látsz.")
        }, ui.lp(ui.ipx(260), ui.ipx(85)).apply { topMargin = ui.ipx(16) })
        pane.addView(note("A bejárt környék térképe a telefonon marad, így gyenge térerőnél is látszik. Törlés után újra letölti."), ui.fieldLp())
    }

    private fun buildSignals() {
        pane.addView(ui.label("SÍPSZÓ MINDEN MEGTETT KM-NÉL"))
        pane.addView(onOff(Prefs.kmBeep(this)) { Prefs.setKmBeep(this, it) }, ui.fieldLp())
        pane.addView(ui.label("JELZÉS FÉLÚTON"), sectionLp())
        pane.addView(onOff(Prefs.halfwayBeep(this)) { Prefs.setHalfwayBeep(this, it) }, ui.fieldLp())
        pane.addView(note(
            "Céllal (táv, idő, kalória) a cél felénél egy rövid és egy hosszú hang. A cél elérésekor mindig dupla hosszú hang szól. " +
                "A hangerő az Android értesítési hangereje; a hangjelzések az Általános → Hangjelzések alatt kapcsolhatók ki.",
        ), sectionLp())
    }

    private fun buildRideScreen() {
        pane.addView(ui.label("KÉPERNYŐ ÉBREN TARTÁSA TEKERÉS KÖZBEN"))
        pane.addView(onOff(Prefs.rideScreenOn(this)) { Prefs.setRideScreenOn(this, it) }, ui.fieldLp())
        pane.addView(note("Kikapcsolva kíméli az akkut: a kijelző a szokásos idő után elalszik, a mérés a háttérben ugyanúgy megy."), sectionLp())
    }

    // ==== Általános ====

    private fun buildUsers() {
        pane.addView(ui.label("FELHASZNÁLÓK"))
        for (p in Db.get(this).profiles()) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ui.rounded(CARD, 24)
                setPadding(ui.ipx(28), ui.ipx(18), ui.ipx(28), ui.ipx(18))
                setOnClickListener { editProfile(p.id) }
            }
            card.addView(ui.text(p.name, 32, INK, ui.bold))
            val sex = if (p.sex == Sex.MALE) "férfi" else "nő"
            card.addView(ui.text("${p.age} év · ${goalText(p.weightKg)} kg · $sex", 20, MUTED, ui.regular),
                ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(6) })
            pane.addView(card, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(14) })
        }
        pane.addView(ui.button("+ Új profil", BAR, Color.WHITE) { editProfile(null) },
            ui.lp(ui.ipx(300), ui.ipx(85)).apply { topMargin = ui.ipx(24) })
        pane.addView(note("Koppints egy profilra a szerkesztéshez vagy törléshez. Felhasználót váltani a bal felső névre koppintva lehet."), sectionLp())
    }

    private fun editProfile(id: Long?) {
        val intent = Intent(this, ProfileEditActivity::class.java)
        if (id != null) intent.putExtra(ProfileEditActivity.EXTRA_PROFILE_ID, id)
        startActivity(intent)
    }

    private fun buildSounds() {
        pane.addView(ui.label("HANGJELZÉSEK"))
        pane.addView(onOff(Prefs.beeps(this)) { Prefs.setBeeps(this, it) }, ui.fieldLp())
        pane.addView(ui.button("Hang kipróbálása", PILL, INK) { Beeper.test(handler) },
            ui.lp(ui.ipx(340), ui.ipx(85)).apply { topMargin = ui.ipx(16) })
        pane.addView(note(
            "Minden sportban: programszakasz váltása, a súlyzós pihenő vége, a kerékpáros km és cél. " +
                "A hangerő az Android értesítési hangereje.",
        ), ui.fieldLp())
    }

    private fun buildAbout() {
        pane.addView(ui.text("Kettler edző", 44, INK, ui.bold))
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        pane.addView(note("Verzió: $version"), ui.fieldLp())
        pane.addView(note("Elliptikus tréner, súlyzós edzés és kerékpár. Az edzésnapló csak ezen az eszközön tárolódik."), ui.fieldLp())
        pane.addView(ui.label("GYAKORLATRAJZOK"), sectionLp())
        pane.addView(ui.note(
            "A beépített gyakorlatrajzok forrása az Everkinetic nyílt gyűjteménye (everkinetic.com, részben a wger.de-n át). Szerző: " +
                Catalog.IMAGE_CREDITS.joinToString("; ") + ". Licenc: Creative Commons Nevezd meg! – Így add tovább!",
            6,
        ), ui.fieldLp())
        pane.addView(ui.label("TÉRKÉP"), sectionLp())
        pane.addView(note("Térképadatok: © OpenStreetMap közreműködők (openstreetmap.org/copyright)."), ui.fieldLp())
        pane.addView(ui.button("Kilépés az appból", DANGER, Color.WHITE) { exitApp() },
            ui.lp(ui.ipx(340), ui.ipx(85)).apply { topMargin = ui.ipx(48) })
        pane.addView(note("Ha fut edzés, előbb elmenti. Leállítja a háttérben futó kapcsolatot is."), ui.fieldLp())
    }

    // Az elliptikus edzést az edzésképernyő menti el (RESULT_EXIT); máshonnan itt zárjuk be az appot.
    private fun exitApp() {
        if (sport == Sport.ELLIPTICAL) {
            setResult(RESULT_EXIT)
            finish()
            return
        }
        if (BikeState.running) {
            ui.toast("Tekerés közben nem lehet kilépni. Előbb fejezd be a tekerést.")
            return
        }
        if (KettlerState.running) {
            ui.toast("Fut egy elliptikus edzés. Azt az elliptikus képernyőn fejezd be.")
            return
        }
        stopService(Intent(this, WorkoutService::class.java))
        finishAffinity()
    }

    // ==== Építőkockák ====

    private fun note(s: String) = ui.note(s)

    private fun noProfile() {
        pane.addView(note("Előbb válassz profilt."))
    }

    private fun sectionLp() = ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(40) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ui.rounded(CARD, 24)
        setPadding(ui.ipx(28), ui.ipx(18), ui.ipx(28), ui.ipx(18))
    }

    // Be / Ki választó
    private fun onOff(value: Boolean, onChange: (Boolean) -> Unit): View =
        options(listOf("Be" to true, "Ki" to false), value) { onChange(it) }

    // Egymás melletti választógombok; a kiválasztott sötét
    private fun <T> options(items: List<Pair<String, T>>, current: T, onSelect: (T) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = mutableListOf<Pair<TextView, T>>()
        fun refresh(v: T) = buttons.forEach { (b, bv) -> ui.setChosen(b, bv == v) }
        items.forEachIndexed { i, (label, v) ->
            val b = ui.choice(label, 28) {
                onSelect(v)
                refresh(v)
            }
            buttons += b to v
            row.addView(b, ui.lp(0, ui.ipx(84), 1f).apply { if (i > 0) leftMargin = ui.ipx(20) })
        }
        refresh(current)
        return row
    }

    companion object {
        const val RESULT_EXIT = Activity.RESULT_FIRST_USER + 1
        const val EXTRA_SPORT = "sport"
        private const val STATE_SECTION = "section"
    }
}
