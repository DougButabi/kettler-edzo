package hu.kettleredzo.app

import android.app.Activity
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
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
        HEART("Pulzusmérő", Group.GENERAL),
        SOUNDS("Hangjelzések", Group.GENERAL),
        ABOUT("Névjegy", Group.GENERAL),
    }

    private lateinit var ui: Ui
    private lateinit var sport: Sport
    private lateinit var pane: LinearLayout
    private val navButtons = mutableMapOf<Section, TextView>()
    private lateinit var section: Section
    private var statusV: TextView? = null
    private var dropsV: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val listener: () -> Unit = { renderStatus() }
    private val hrListener: () -> Unit = { renderHeart() }
    private var hrStatusV: TextView? = null
    private var foundList: LinearLayout? = null
    private var scanner: BluetoothLeScanner? = null
    private val found = linkedMapOf<String, String>() // cím -> név
    private lateinit var titleV: TextView
    private var menuView: View? = null
    private var contentView: View? = null
    private var menuMode = false // telefon, álló: a menülista látszik (nem egy menüpont)
    private var connLog = false // a Kapcsolat részletes naplója látszik
    private var detailV: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this).apply { settingsMode() }
        sport = Sport.of(intent.getStringExtra(EXTRA_SPORT))
        // Telefonon nincs overlay-sáv, így a Kijelző oldal (csak annak a kapcsolója) sem kell.
        val sections = Section.entries.filter {
            (it.group.sport == sport || it.group == Group.GENERAL) && !(it == Section.DISPLAY && ui.phone)
        }
        section = sections.first()
        savedInstanceState?.getString(STATE_SECTION)?.let { name -> sections.firstOrNull { it.name == name }?.let { section = it } }

        val root = ui.splitRoot()
        val left = ui.sidePanel("Beállítások")
        titleV = left.getChildAt(1) as TextView
        // Fekvőben a menü bal oldalt van; telefonon állóban külön oldal, a menüpontok egymás alatt,
        // és a kiválasztott pont teljes képernyőn nyílik meg (a „← Vissza” a menübe visz).
        val nav = if (ui.portrait) LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } else left
        var lastGroup: Group? = null
        for (s in sections) {
            if (s.group != lastGroup) {
                nav.addView(ui.label(s.group.title, MUTED), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
                    topMargin = ui.ipx(if (lastGroup == null) 34 else 26)
                })
            }
            val b = ui.navButton(s.title) { show(s) }
            navButtons[s] = b
            nav.addView(b, ui.lp(MATCH_PARENT, ui.ipx(if (ui.portrait) 80 else 70)).apply { topMargin = ui.ipx(10) })
            lastGroup = s.group
        }
        if (!ui.portrait) left.addView(View(this), ui.lp(MATCH_PARENT, 0, 1f))
        // Kilépés az appból: a menü alján
        nav.addView(ui.button("Kilépés az appból", DANGER, Color.WHITE) { exitApp() },
            ui.lp(MATCH_PARENT, ui.ipx(if (ui.portrait) 80 else 78)).apply { topMargin = ui.ipx(if (ui.portrait) 40 else 20) })
        nav.addView(ui.button("← Vissza", PILL, INK) { finish() }, ui.lp(MATCH_PARENT, ui.ipx(if (ui.portrait) 80 else 78)).apply {
            topMargin = ui.ipx(if (ui.portrait) 14 else 20)
        })
        root.addView(if (ui.portrait) left else ScrollView(this).apply {
            isFillViewport = true
            addView(left)
        }, ui.sideLp(0.34f))

        pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.paneLayout(pane)
        val paneScroll = ScrollView(this).apply { addView(pane) }
        root.addView(paneScroll, ui.mainLp(0.66f))
        if (ui.portrait) {
            nav.setPadding(ui.ipx(35 + ui.edge), 0, ui.ipx(35 + ui.edge), ui.ipx(40))
            val menu = ScrollView(this).apply { addView(nav) }
            root.addView(menu, ui.mainLp(0.66f))
            menuView = menu
            contentView = paneScroll
            menuMode = savedInstanceState?.getBoolean(STATE_MENU) ?: true
            applyMenuMode()
        }
        setContentView(root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SECTION, section.name)
        outState.putBoolean(STATE_MENU, menuMode)
    }

    // Telefon, álló: a menü és a tartalom felváltva látszik
    private fun applyMenuMode() {
        menuView?.visibility = if (menuMode) View.VISIBLE else View.GONE
        contentView?.visibility = if (menuMode) View.GONE else View.VISIBLE
        titleV.text = if (menuMode || !ui.portrait) "Beállítások" else section.title
        // A menülistában nincs kiemelt pont (nem látszik mellette a tartalom)
        if (ui.portrait) navButtons.forEach { (k, v) -> ui.setChosen(v, !menuMode && k == section) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            connLog -> show(Section.CONNECTION)
            ui.portrait && !menuMode -> {
                menuMode = true
                applyMenuMode()
            }
            else -> super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        KettlerState.addListener(listener)
        HeartRate.addListener(hrListener)
        // pl. az overlay-engedély, egy profil vagy az edzésterv közben megváltozhatott
        if (connLog) showConnectionLog() else build(section)
    }

    override fun onPause() {
        KettlerState.removeListener(listener)
        HeartRate.removeListener(hrListener)
        stopScan()
        // A pántot csak edzés közben tartjuk: a próbakapcsolatot elengedjük (folyamatban lévő súlyzós edzésnél
        // a LiftActivity úgyis újra rácsatlakozik).
        if (isFinishing && !KettlerState.running && !BikeState.running) HeartRate.disconnect()
        // Kilépéskor a beállításokban (pl. edzésterv, gyakorlatok) tett változások mennek a ULC-be.
        if (isFinishing) Sync.runAsync(this)
        super.onPause()
    }

    // Egy menüpont megnyitása (telefonon állóban teljes képernyőn)
    private fun show(s: Section) {
        build(s)
        if (ui.portrait) {
            menuMode = false
            applyMenuMode()
        }
    }

    private fun build(s: Section) {
        section = s
        connLog = false
        navButtons.forEach { (k, v) -> ui.setChosen(v, k == s) }
        pane.removeAllViews()
        // Telefonon állóban a tartalom tetején vissza a menübe
        if (ui.portrait) {
            pane.addView(ui.button("← Menü", PILL, INK) {
                menuMode = true
                applyMenuMode()
            }, ui.lp(ui.ipx(260), ui.ipx(80)).apply { bottomMargin = ui.ipx(30) })
        }
        statusV = null
        dropsV = null
        hrStatusV = null
        foundList = null
        if (s != Section.HEART) stopScan()
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
            Section.HEART -> buildHeart()
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
        val sv = ui.text("", 28, INK, ui.bold)
        statusV = sv
        pane.addView(sv, ui.fieldLp())
        pane.addView(ui.button("Napló", PILL, INK) { showConnectionLog() }, ui.lp(ui.ipx(260), ui.ipx(85)).apply { topMargin = ui.ipx(20) })
        pane.addView(note("A külső eszköz (ESP32) küldi a fordulatszámot, a sebességet és a távot."), sectionLp())
        renderStatus()
    }

    // A kapcsolat részletei: cím, hibaüzenet és az utolsó megszakadások
    private fun showConnectionLog() {
        build(Section.CONNECTION)
        pane.removeAllViews()
        connLog = true
        pane.addView(ui.button("← Vissza", PILL, INK) { show(Section.CONNECTION) }, ui.lp(ui.ipx(260), ui.ipx(80)).apply { bottomMargin = ui.ipx(30) })
        pane.addView(ui.label("KAPCSOLAT"))
        val sv = ui.text("", 28, INK, ui.bold)
        statusV = sv
        pane.addView(sv, ui.fieldLp())
        val dt = note("")
        detailV = dt
        pane.addView(dt, ui.fieldLp())
        pane.addView(ui.label("UTOLSÓ MEGSZAKADÁSOK"), sectionLp())
        val dv = note("")
        dropsV = dv
        pane.addView(dv, ui.fieldLp())
        renderStatus()
    }

    private fun renderStatus() {
        val v = statusV ?: return
        val s = KettlerState
        val host = Prefs.host(this)
        val (text, color) = when {
            host.isBlank() -> "Nincs megadva cím" to DANGER
            s.connected -> "● Kapcsolódva" to Color.parseColor("#2E7D32")
            else -> "Nincs kapcsolat" to DANGER
        }
        v.text = text
        v.setTextColor(color)
        detailV?.text = "Cím: ${host.ifBlank { "–" }}" + (if (!s.connected) s.lastError?.let { "\nHiba: $it" } ?: "" else "")
        dropsV?.text = EspClient.recentDrops().joinToString("\n").ifEmpty { "Nem volt megszakadás." }
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

    // Szinkron a ULC-vel: cím, eszközkulcs (a ULC Edzés oldalán készül), állapot, kézi indítás
    private fun buildUsers() {
        pane.addView(ui.label("FELHASZNÁLÓK"))
        // Személyes eszközön csak a saját profil látszik, újat létrehozni nem lehet.
        val personal = Prefs.personal(this)
        val own = Sync.boundProfileId(this)
        for (p in Db.get(this).profiles().filter { !personal || it.id == own }) {
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
        if (personal) {
            pane.addView(note("Ez egy személyes eszköz, csak a saját profilod van rajta. Koppints rá a szerkesztéshez."), sectionLp())
            return
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
        pane.addView(ui.label("HANGOS VISSZAJELZÉS A CÉLNÁL"))
        pane.addView(choiceRow(VoiceEvery.entries.map { it.title }, VoiceEvery.entries.indexOf(Prefs.voiceEvery(this))) {
            Prefs.setVoiceEvery(this, VoiceEvery.entries[it])
            show(Section.SOUNDS)
        }, ui.fieldLp())
        pane.addView(note(
            "Elliptikusnál és kerékpárnál, ha van cél (táv, kalória, idő vagy program): a cél felénél, harmadainál vagy " +
                "negyedeinél bemondja, hol tartasz, 95%-nál biztat, az utolsó km-nél vagy percnél szól, a végén gratulál.",
        ), ui.fieldLp())
        pane.addView(ui.label("HANG"), sectionLp())
        pane.addView(choiceRow(listOf("Férfi", "Női"), if (Prefs.voiceMale(this)) 0 else 1) {
            Prefs.setVoiceMale(this, it == 0)
            show(Section.SOUNDS)
        }, ui.fieldLp())
        pane.addView(ui.button("Bemondás kipróbálása", PILL, INK) { Voice.say(this, listOf("q1", "t1", "h", "t2", "q3", "p95", "goal").random()) },
            ui.lp(ui.ipx(420), ui.ipx(85)).apply { topMargin = ui.ipx(16) })

        pane.addView(ui.label("HANGJELZÉSEK"), sectionLp())
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
        buildUpdate()
        // Forrásmegjelölések (az OpenStreetMap és a gyakorlatrajzok CC BY-SA licence is előírja)
        pane.addView(note("Térképadatok: © OpenStreetMap közreműködők (openstreetmap.org/copyright)."), sectionLp())
        pane.addView(note("Gyakorlatrajzok: Everkinetic, CC BY-SA."), ui.fieldLp())
        // A szinkron láthatatlan; itt egy sor mutatja, hogy működik-e.
        val device = (if (Prefs.personal(this)) "személyes eszköz" else "közös eszköz") +
            Prefs.syncAccount(this).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        pane.addView(note("Utolsó szinkron: ${Prefs.syncStatus(this).ifBlank { "még nem volt" }} ($device)"), sectionLp())
    }

    // Egymás melletti választógombok (pl. Ki / Fél / Harmad / Negyed)
    private fun choiceRow(titles: List<String>, chosen: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        titles.forEachIndexed { i, t ->
            val b = ui.choice(t, 26) { onPick(i) }
            ui.setChosen(b, i == chosen)
            row.addView(b, ui.lp(0, ui.ipx(80), 1f).apply { if (i > 0) leftMargin = ui.ipx(16) })
        }
        return row
    }

    // ---- Pulzusmérő ----

    // Párosítás: keresés (csak a pulzus-szolgáltatást hirdető eszközök), kiválasztás, élő próba.
    private fun buildHeart() {
        pane.addView(ui.label("PULZUSMÉRŐ"))
        val name = Prefs.hrName(this).ifBlank { Prefs.hrAddress(this) }
        pane.addView(ui.text(if (HeartRate.paired(this)) name else "Nincs párosítva", 34, INK, ui.bold), ui.fieldLp())
        val status = ui.text("", 24, MUTED, ui.medium)
        hrStatusV = status
        pane.addView(status, ui.fieldLp())
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(ui.button("Keresés", BAR, Color.WHITE) { startScan() }, ui.lp(ui.ipx(260), ui.ipx(85)))
        if (HeartRate.paired(this)) {
            buttons.addView(ui.button("Elfelejtés", PILL, INK) {
                HeartRate.forget(this)
                show(Section.HEART)
            }, ui.lp(ui.ipx(260), ui.ipx(85)).apply { leftMargin = ui.ipx(20) })
        }
        pane.addView(buttons, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        foundList = list
        pane.addView(list, ui.fieldLp())
        pane.addView(note(
            "Vedd fel a pántot (bőrkontaktus kell, hogy bekapcsoljon), és nyomd meg a Keresést. Edzés indításakor az app " +
                "magától rácsatlakozik, a befejezéskor lecsatlakozik, így a pánt szabad lesz más eszköznek. Ha egy másik " +
                "eszköz (pl. a gyártó appja vagy egy óra) épp használja, előbb ott bontsd a kapcsolatot.",
        ), sectionLp())
        pane.addView(note(
            "Pulzussal a kalóriát a pulzusból, életkorból, testsúlyból és nemből számolom (Keytel-képlet), ami pontosabb a becslésnél.",
        ), ui.fieldLp())
        // Ezen az oldalon a párosított pántra próbaként rácsatlakozunk, hogy látszódjon a pulzus.
        HeartRate.connect(this)
        renderFound()
        renderHeart()
    }

    private fun renderHeart() {
        val v = hrStatusV ?: return
        val bpm = HeartRate.bpm
        v.text = when {
            !HeartRate.paired(this) -> if (scanner != null) "Keresés…" else "Nyomd meg a Keresést."
            !HeartRate.hasPermission(this) -> "Engedélyezd a Bluetooth-hozzáférést (Keresés gomb)."
            bpm != null -> "● Kapcsolódva   ·   ♥ $bpm bpm"
            HeartRate.state == HeartRate.State.CONNECTED -> "● Kapcsolódva, pulzusra vár…"
            HeartRate.state == HeartRate.State.CONNECTING -> "Kapcsolódás…"
            else -> "Nincs kapcsolat (be van kapcsolva a pánt?)"
        }
    }

    private fun renderFound() {
        val list = foundList ?: return
        list.removeAllViews()
        for ((address, name) in found) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ui.rounded(CARD, 24)
                setPadding(ui.ipx(28), ui.ipx(18), ui.ipx(28), ui.ipx(18))
                setOnClickListener {
                    stopScan()
                    HeartRate.disconnect()
                    Prefs.setHrDevice(this@SettingsActivity, address, name)
                    found.clear()
                    show(Section.HEART)
                }
            }
            card.addView(ui.text(name, 30, INK, ui.bold))
            card.addView(ui.text("Koppints a párosításhoz   ·   $address", 18, MUTED, ui.regular),
                ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(6) })
            list.addView(card, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(14) })
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!HeartRate.hasPermission(this)) {
            requestPermissions(HeartRate.permissions(), REQ_BLUETOOTH)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            ui.toast("Kapcsold be a Bluetooth-t, aztán nyomd meg újra a Keresést.")
            return
        }
        stopScan()
        found.clear()
        renderFound()
        val s = adapter.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(HR_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        s.startScan(listOf(filter), settings, scanCallback)
        scanner = s
        hrStatusV?.text = "Keresés… (15 mp)"
        handler.postDelayed(scanTimeout, 15_000)
    }

    private val scanTimeout = Runnable {
        stopScan()
        if (found.isEmpty()) hrStatusV?.text = "Nem találtam pulzusmérőt. Rajtad van a pánt, és nem csatlakozik máshoz?" else renderHeart()
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        val s = scanner ?: return
        scanner = null
        try {
            s.stopScan(scanCallback)
        } catch (e: Exception) {
            // a Bluetooth közben kikapcsolhatott
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            if (address in found) return
            found[address] = result.scanRecord?.deviceName ?: result.device.name ?: "Pulzusmérő"
            renderFound()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BLUETOOTH) return
        if (HeartRate.hasPermission(this)) startScan() else ui.toast("Bluetooth-engedély nélkül nem találom a pulzusmérőt.")
    }

    // Frissítés gomb: megnézi a GitHubon a legújabb kiadást, és ha újabb, letölti és telepíti.
    private fun buildUpdate() {
        val status = note("")
        lateinit var button: TextView
        var latest: Long? = null
        button = ui.button("Frissítés keresése", PILL, INK) {
            val target = latest
            if (target == null) {
                status.text = "Keresés…"
                Updater.latest { n, error ->
                    if (isFinishing) return@latest
                    when {
                        n == null -> status.text = "Nem sikerült: $error"
                        n <= Updater.currentBuild(this) -> status.text = "Ez a legújabb verzió."
                        else -> {
                            latest = n
                            status.text = "Van újabb: 0.8.$n"
                            button.text = "Letöltés és telepítés"
                        }
                    }
                }
                return@button
            }
            // Az első telepítés előtt az Android engedélyt kér („Ismeretlen alkalmazások telepítése”).
            if (!packageManager.canRequestPackageInstalls()) {
                status.text = "Engedélyezd a telepítést a Kettler edzőnek, aztán nyomd meg újra."
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return@button
            }
            status.text = "Letöltés…"
            Updater.install(this, { pct -> if (!isFinishing) status.text = "Letöltés… $pct%" }) { error ->
                if (!isFinishing) status.text = "Nem sikerült: $error"
            }
        }
        pane.addView(button, ui.lp(ui.ipx(420), ui.ipx(85)).apply { topMargin = ui.ipx(24) })
        pane.addView(status, ui.fieldLp())
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
        private const val REQ_BLUETOOTH = 41
        private const val HR_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb"
        const val RESULT_EXIT = Activity.RESULT_FIRST_USER + 1
        const val EXTRA_SPORT = "sport"
        private const val STATE_SECTION = "section"
        private const val STATE_MENU = "menu"
    }
}
