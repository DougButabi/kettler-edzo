package hu.kettleredzo.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar

// Egy profil elliptikus rutinja teljes képernyőn: fülhallgató és hangerő. Az elliptikus képernyő
// megnyitásakor fut le. A fülhallgató-lista ugyanitt, a tartalom helyén nyílik meg, „← Vissza” gombbal.
class RoutineActivity : KettlerActivity() {
    private lateinit var ui: Ui
    private lateinit var profile: Profile
    private lateinit var pane: LinearLayout
    private lateinit var r: Routine

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
                "Az elliptikus képernyő megnyitásakor beállítja a hangerőt, és szól, ha a fülhallgató nincs csatlakozva.",
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

    // ---- A rutin ----

    private fun render() {
        pane.removeAllViews()
        pane.addView(ui.label("FÜLHALLGATÓ"))
        pane.addView(ui.text(r.btName.ifBlank { "Nincs (a tablet hangszórója)" }, 30, INK, ui.bold), ui.fieldLp())
        pane.addView(buttonRow("Választás" to { pickHeadset() }, "Nincs" to { r = r.copy(bt = "", btName = ""); render() }),
            ui.fieldLp())
        pane.addView(ui.note("Ha meg van adva, és az elliptikus képernyő megnyitásakor nincs csatlakozva, szól. Csatlakoztatni a tablet Bluetooth-beállításaiban lehet."),
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
            Db.get(this).saveRoutine(profile.id, r)
            Sync.runAsync(this)
            finish()
        }, ui.lp(ui.ipx(260), ui.ipx(85)).apply { leftMargin = ui.ipx(20) })
        pane.addView(buttons, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(50) })
    }

    // ---- Választólisták ----

    private fun listPage(title: String): LinearLayout {
        pane.removeAllViews()
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
