package hu.kettleredzo.app

import android.app.Activity
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

// Beállítások menürendszer: bal oldalt a menüpontok, jobb oldalt a kiválasztott pont.
// A „Kilépés az appból” RESULT_EXIT eredménnyel tér vissza, a kilépést
// (edzés mentése, szolgáltatás leállítása) az edzésképernyő végzi el.
class SettingsActivity : KettlerActivity() {
    private enum class Section(val title: String) {
        CONNECTION("Kapcsolat"),
        DISPLAY("Kijelző"),
        TRAINING("Edzés"),
        USERS("Felhasználók"),
        ABOUT("Névjegy"),
    }

    private lateinit var ui: Ui
    private lateinit var pane: LinearLayout
    private val navButtons = mutableMapOf<Section, TextView>()
    private var section = Section.CONNECTION
    private var statusV: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val listener: () -> Unit = { renderStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG)
        }

        val left = ui.sidePanel("Beállítások")
        for (s in Section.entries) {
            val b = ui.choice(s.title) { show(s) }
            navButtons[s] = b
            left.addView(b, ui.lp(MATCH_PARENT, ui.ipx(78)).apply { topMargin = ui.ipx(if (s.ordinal == 0) 40 else 14) })
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
        KettlerState.addListener(listener)
        show(section) // pl. az overlay-engedély vagy egy profil közben megváltozhatott
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
            Section.TRAINING -> buildTraining()
            Section.USERS -> buildUsers()
            Section.ABOUT -> buildAbout()
        }
    }

    // ---- Kapcsolat ----

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

    // ---- Kijelző ----

    private fun buildDisplay() {
        pane.addView(ui.label("OVERLAY-SÁV"))
        val on = ui.choice("Be") { Prefs.setOverlay(this, true); show(section) }
        val off = ui.choice("Ki") { Prefs.setOverlay(this, false); show(section) }
        val overlay = Prefs.overlay(this)
        ui.setChosen(on, overlay)
        ui.setChosen(off, !overlay)
        pane.addView(pair(on, off), ui.fieldLp())

        if (overlay && !Settings.canDrawOverlays(this)) {
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

    // ---- Edzés ----

    private fun buildTraining() {
        pane.addView(ui.label("FÉKFOKOZATOK SZÁMA"))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val value = ui.text(Prefs.levelCount(this).toString(), 50, INK, ui.bold).apply {
            gravity = Gravity.CENTER
            minWidth = ui.ipx(120)
        }
        fun change(d: Int) {
            Prefs.setLevelCount(this, Prefs.levelCount(this) + d)
            value.text = Prefs.levelCount(this).toString()
        }
        row.addView(ui.choice("−", 40) { change(-1) }, ui.lp(ui.ipx(90), ui.ipx(90)))
        row.addView(value)
        row.addView(ui.choice("+", 40) { change(1) }, ui.lp(ui.ipx(90), ui.ipx(90)))
        pane.addView(row, ui.fieldLp())
        pane.addView(note("Ahány fokozat a gép fékerő-állítóján van. Az edzésképernyőn ennyiig lehet állítani."), ui.fieldLp())

        pane.addView(ui.label("HANGJELZÉSEK"), sectionLp())
        val on = ui.choice("Be") { Prefs.setBeeps(this, true); show(section) }
        val off = ui.choice("Ki") { Prefs.setBeeps(this, false); show(section) }
        val beeps = Prefs.beeps(this)
        ui.setChosen(on, beeps)
        ui.setChosen(off, !beeps)
        pane.addView(pair(on, off), ui.fieldLp())
        pane.addView(ui.button("Hang kipróbálása", PILL, INK) { Beeper.test(handler) },
            ui.lp(ui.ipx(340), ui.ipx(85)).apply { topMargin = ui.ipx(16) })
        pane.addView(note("Programszakasz váltásakor és a cél elérésekor szól. A hangerő az Android értesítési hangereje."), ui.fieldLp())
    }

    // ---- Felhasználók ----

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

    // ---- Névjegy ----

    private fun buildAbout() {
        pane.addView(ui.text("Kettler edző", 44, INK, ui.bold))
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        pane.addView(note("Verzió: $version"), ui.fieldLp())
        pane.addView(note("Kettler Vito XL elliptikus trénerhez és súlyzós edzéshez. Az edzésnapló csak ezen az eszközön tárolódik."), ui.fieldLp())
        pane.addView(ui.label("GYAKORLATRAJZOK"), sectionLp())
        pane.addView(ui.note(
            "A beépített gyakorlatrajzok forrása a wger.de nyílt adatbázisa. Szerzők: " +
                Catalog.IMAGE_CREDITS.joinToString("; ") + ". Licenc: Creative Commons Nevezd meg! – Így add tovább!",
            6,
        ), ui.fieldLp())
        pane.addView(ui.button("Kilépés az appból", DANGER, Color.WHITE) {
            setResult(RESULT_EXIT)
            finish()
        }, ui.lp(ui.ipx(340), ui.ipx(85)).apply { topMargin = ui.ipx(48) })
        pane.addView(note("Ha fut edzés, előbb elmenti. Leállítja a háttérben futó kapcsolatot is."), ui.fieldLp())
    }

    // ---- Építőkockák ----

    private fun note(s: String) = ui.text(s, 22, MUTED, ui.regular).apply {
        maxLines = 4
        ellipsize = null
    }

    private fun sectionLp() = ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(40) }

    private fun pair(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, ui.lp(0, ui.ipx(90), 1f))
        addView(b, ui.lp(0, ui.ipx(90), 1f).apply { leftMargin = ui.ipx(30) })
    }

    companion object {
        const val RESULT_EXIT = Activity.RESULT_FIRST_USER + 1
    }
}
