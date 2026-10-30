package hu.kettleredzo.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors

// Egy profil elliptikus rutinja teljes képernyőn: indítandó app (YouTube-nál link, Kodinál sorozat),
// fülhallgató és hangerő. A választólisták (appok, sorozatok, fülhallgatók) ugyanitt, a tartalom
// helyén nyílnak meg, „← Vissza” gombbal.
class RoutineActivity : KettlerActivity() {
    private lateinit var ui: Ui
    private lateinit var profile: Profile
    private lateinit var pane: LinearLayout
    private lateinit var r: Routine
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    // A szerkesztés közbeni szövegmezők (a választólisták megnyitásakor elmentjük az értéküket)
    private var linkE: EditText? = null
    private var portE: EditText? = null
    private var userE: EditText? = null
    private var passE: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Db.get(this).profile(intent.getLongExtra(EXTRA_PROFILE_ID, -1))
        if (p == null) {
            finish()
            return
        }
        profile = p
        r = Routine.parse(p.routine)
        ui = Ui(this).apply { settingsMode() }
        val root = ui.splitRoot()
        val left = ui.sidePanel("Rutin")
        left.addView(ui.text(p.name, 34, INK, ui.bold), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(20) })
        if (!ui.portrait) {
            left.addView(ui.note(
                "Az elliptikus képernyőn a Rutin gomb futtatja: beállítja a hangerőt, megnézi a fülhallgatót, és a háttérben " +
                    "elindítja a videót. A Kettler elöl marad; a „Videó ›” gombbal váltasz át.",
                6,
            ), ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(30) })
        }
        root.addView(left, ui.sideLp(0.38f))
        pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.paneLayout(pane)
        root.addView(ScrollView(this).apply { addView(pane) }, ui.mainLp(0.62f))
        setContentView(root)
        render()
    }

    private fun keepFields() {
        linkE?.let { r = r.copy(link = it.text.toString().trim()) }
        val port = portE?.text?.toString()?.trim()?.toIntOrNull()
        if (portE != null && userE != null && passE != null) {
            Prefs.setKodi(this, port ?: 8080, userE!!.text.toString().trim(), passE!!.text.toString())
        }
    }

    private fun clearFields() {
        linkE = null
        portE = null
        userE = null
        passE = null
    }

    // ---- A rutin ----

    private fun render() {
        pane.removeAllViews()
        clearFields()

        pane.addView(ui.label("APP"))
        pane.addView(ui.text(r.appName.ifBlank { "Nincs" }, 30, INK, ui.bold), ui.fieldLp())
        pane.addView(buttonRow("Választás" to { keepFields(); pickApp() }, "Nincs" to { keepFields(); r = r.copy(app = "", appName = ""); render() }),
            ui.fieldLp())

        if (r.isYouTube) {
            pane.addView(ui.label("YOUTUBE-LINK (NEM KÖTELEZŐ)"), sectionLp())
            val e = ui.field(r.link, "pl. https://youtube.com/playlist?list=…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, true)
            linkE = e
            pane.addView(e, ui.fieldLp())
            pane.addView(ui.note("Egy videó vagy lejátszási lista linkje. Üresen a YouTube kezdőlapja nyílik meg."), ui.fieldLp())
        }
        if (r.isKodi) {
            pane.addView(ui.label("SOROZAT"), sectionLp())
            pane.addView(ui.text(r.kodiShow.ifBlank { "Nincs (csak elindítja a Kodit)" }, 30, INK, ui.bold), ui.fieldLp())
            pane.addView(buttonRow("Választás" to { keepFields(); pickShow() }, "Nincs" to { keepFields(); r = r.copy(kodiShowId = -1, kodiShow = ""); render() }),
                ui.fieldLp())
            pane.addView(ui.note("A sorozat következő, még nem látott része töltődik be, ott folytatva, ahol abbahagytad."), ui.fieldLp())
            pane.addView(ui.label("KODI ELÉRÉSE"), sectionLp())
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            portE = ui.field(Prefs.kodiPort(this).toString(), "8080", InputType.TYPE_CLASS_NUMBER, false)
            userE = ui.field(Prefs.kodiUser(this), "kodi", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false)
            passE = ui.field(Prefs.kodiPass(this), "jelszó", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, true)
            row.addView(portE, ui.lp(0, WRAP_CONTENT, 0.6f))
            row.addView(userE, ui.lp(0, WRAP_CONTENT, 1f).apply { leftMargin = ui.ipx(16) })
            row.addView(passE, ui.lp(0, WRAP_CONTENT, 1f).apply { leftMargin = ui.ipx(16) })
            pane.addView(row, ui.fieldLp())
            pane.addView(ui.note(
                "Port, felhasználó, jelszó, ahogy a Kodiban beállítottad: Beállítások › Szolgáltatások › Vezérlés › " +
                    "„Távvezérlés engedélyezése HTTP-n”. Ez a tablet saját beállítása, minden profilnál ugyanaz.",
                5,
            ), ui.fieldLp())
        }

        pane.addView(ui.label("FÜLHALLGATÓ"), sectionLp())
        pane.addView(ui.text(r.btName.ifBlank { "Nincs (a tablet hangszórója)" }, 30, INK, ui.bold), ui.fieldLp())
        pane.addView(buttonRow("Választás" to { keepFields(); pickHeadset() }, "Nincs" to { keepFields(); r = r.copy(bt = "", btName = ""); render() }),
            ui.fieldLp())
        pane.addView(ui.note("Ha meg van adva, és a Rutin gombnál nincs csatlakozva, szól. Csatlakoztatni a tablet Bluetooth-beállításaiban lehet."),
            ui.fieldLp())

        pane.addView(ui.label("HANGERŐ"), sectionLp())
        val volumeV = ui.text("", 30, INK, ui.bold)
        fun showVolume() {
            volumeV.text = if (r.volume < 0) "Nem állítja" else "${r.volume}%"
        }
        showVolume()
        pane.addView(volumeV, ui.fieldLp())
        val seek = SeekBar(this).apply {
            max = 100
            progress = if (r.volume < 0) 50 else r.volume
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    r = r.copy(volume = value)
                    showVolume()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        pane.addView(seek, ui.lp(MATCH_PARENT, ui.ipx(70)).apply { topMargin = ui.ipx(10) })
        pane.addView(buttonRow("Nem állítja" to { r = r.copy(volume = -1); showVolume() }), ui.fieldLp())

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(View(this), ui.lp(0, 1, 1f))
        buttons.addView(ui.button("Mégse", PILL, INK) { finish() }, ui.lp(ui.ipx(200), ui.ipx(85)))
        buttons.addView(ui.button("Mentés", BAR, Color.WHITE) {
            keepFields()
            Db.get(this).saveRoutine(profile.id, r)
            Sync.runAsync(this)
            finish()
        }, ui.lp(ui.ipx(260), ui.ipx(85)).apply { leftMargin = ui.ipx(20) })
        pane.addView(buttons, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(50) })
    }

    // ---- Választólisták ----

    private fun listPage(title: String): LinearLayout {
        pane.removeAllViews()
        clearFields()
        pane.addView(ui.button("← Vissza", PILL, INK) { render() }, ui.lp(ui.ipx(260), ui.ipx(80)))
        pane.addView(ui.label(title), sectionLp())
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pane.addView(list, ui.fieldLp())
        return list
    }

    private fun item(list: LinearLayout, title: String, subtitle: String?, onClick: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(CARD, 24)
            setPadding(ui.ipx(28), ui.ipx(18), ui.ipx(28), ui.ipx(18))
            setOnClickListener { onClick() }
        }
        card.addView(ui.text(title, 28, INK, ui.bold))
        subtitle?.let { card.addView(ui.text(it, 18, MUTED, ui.regular), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(4) }) }
        list.addView(card, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(12) })
    }

    private fun pickApp() {
        val list = listPage("MELYIK APP INDULJON?")
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            // A YouTube és a Kodi elöl, utána ábécérendben
            .sortedWith(compareBy({ it.first != Routine.YOUTUBE && it.first != Routine.KODI }, { it.second.lowercase() }))
        for ((pkg, label) in apps) {
            item(list, label, null) {
                r = r.copy(app = pkg, appName = label, link = if (pkg == Routine.YOUTUBE) r.link else "",
                    kodiShowId = if (pkg == Routine.KODI) r.kodiShowId else -1, kodiShow = if (pkg == Routine.KODI) r.kodiShow else "")
                render()
            }
        }
    }

    private fun pickShow() {
        val list = listPage("SOROZAT A KODIBAN")
        val status = ui.note("Sorozatok lekérése a Kodiból…")
        list.addView(status)
        executor.execute {
            val result = runCatching { Kodi.tvShows(this) }
            handler.post {
                if (isFinishing) return@post
                result.onSuccess { shows ->
                    status.text = if (shows.isEmpty()) "A Kodi könyvtárában nincs sorozat." else ""
                    for ((id, title) in shows) item(list, title, null) {
                        r = r.copy(kodiShowId = id, kodiShow = title)
                        render()
                    }
                }.onFailure { status.text = "Nem sikerült: ${it.message}" }
            }
        }
    }

    private fun pickHeadset() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
            return
        }
        val list = listPage("FÜLHALLGATÓ")
        val devices = RoutineRunner.bondedDevices(this)
        if (devices.isEmpty()) list.addView(ui.note("Nincs párosított Bluetooth-eszköz. Párosítsd a tablet Bluetooth-beállításaiban."))
        for ((address, name) in devices) {
            item(list, name, address) {
                r = r.copy(bt = address, btName = name)
                render()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) pickHeadset()
    }

    private fun buttonRow(vararg buttons: Pair<String, () -> Unit>): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEachIndexed { i, (title, onClick) ->
            row.addView(ui.button(title, PILL, INK) { onClick() }, ui.lp(ui.ipx(240), ui.ipx(80)).apply { if (i > 0) leftMargin = ui.ipx(16) })
        }
        return row
    }

    private fun sectionLp() = ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(40) }

    companion object {
        const val EXTRA_PROFILE_ID = "profileId"
        private const val REQ_BT = 51
    }
}
