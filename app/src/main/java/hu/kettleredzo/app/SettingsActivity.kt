package hu.kettleredzo.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Beállítások teljes képernyőn. A „Kilépés az appból” RESULT_EXIT eredménnyel tér vissza,
// a kilépést (edzés mentése, szolgáltatás leállítása) az edzésképernyő végzi el.
class SettingsActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var hostE: EditText
    private lateinit var statusV: TextView
    private lateinit var overlayOn: TextView
    private lateinit var overlayOff: TextView
    private lateinit var permissionBtn: View
    private var overlay = true

    private val listener: () -> Unit = { renderStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        overlay = Prefs.overlay(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG)
        }

        val left = ui.sidePanel("Beállítások")
        left.addView(ui.label("ESP32 KAPCSOLAT"), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(60) })
        statusV = ui.text("", 24, INK, ui.medium).apply {
            maxLines = 4
            ellipsize = null
        }
        left.addView(statusV, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(12) })
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        left.addView(ui.text("Verzió: $version", 18, MUTED, ui.regular), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = ui.ipx(40)
        })
        root.addView(left, ui.lp(0, MATCH_PARENT, 0.38f))

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.ipx(20), ui.ipx(120), ui.ipx(60), ui.ipx(60))
        }
        form.addView(ui.label("ESP32 IP-CÍME"))
        hostE = ui.field(Prefs.host(this), "pl. 192.168.1.50", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, true)
        form.addView(hostE, ui.fieldLp())

        form.addView(ui.label("OVERLAY-SÁV A KODI FÖLÖTT"), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = ui.ipx(36)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        overlayOn = ui.choice("Be") { overlay = true; renderOverlay() }
        overlayOff = ui.choice("Ki") { overlay = false; renderOverlay() }
        row.addView(overlayOn, ui.lp(0, ui.ipx(90), 1f))
        row.addView(overlayOff, ui.lp(0, ui.ipx(90), 1f).apply { leftMargin = ui.ipx(30) })
        form.addView(row, ui.fieldLp())

        permissionBtn = ui.button("Engedély megadása az overlay-hez", PILL, INK) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }.apply { setPadding(ui.ipx(30), 0, ui.ipx(30), 0) }
        form.addView(permissionBtn, ui.lp(WRAP_CONTENT, ui.ipx(75)).apply { topMargin = ui.ipx(20) })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttons.addView(ui.button("Kilépés az appból", DANGER, Color.WHITE) {
            setResult(RESULT_EXIT)
            finish()
        }.apply { setPadding(ui.ipx(30), 0, ui.ipx(30), 0) }, ui.lp(WRAP_CONTENT, ui.ipx(85)))
        buttons.addView(View(this), ui.lp(0, 1, 1f))
        buttons.addView(ui.button("Mégse", PILL, INK) { finish() }, ui.lp(ui.ipx(200), ui.ipx(85)))
        buttons.addView(ui.button("Mentés", BAR, Color.WHITE) { save() }, ui.lp(ui.ipx(260), ui.ipx(85)).apply {
            leftMargin = ui.ipx(20)
        })
        form.addView(buttons, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(48) })

        root.addView(ScrollView(this).apply { addView(form) }, ui.lp(0, MATCH_PARENT, 0.62f))
        setContentView(root)
        renderOverlay()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        KettlerState.addListener(listener)
        renderStatus()
        renderOverlay() // az engedély közben megváltozhatott
    }

    override fun onPause() {
        KettlerState.removeListener(listener)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun renderStatus() {
        val s = KettlerState
        val host = Prefs.host(this)
        when {
            host.isBlank() -> status("Nincs megadva cím.", DANGER)
            s.connected -> status("● Kapcsolódva\n$host", Color.parseColor("#2E7D32"))
            else -> status("Kapcsolódás: $host…" + (s.lastError?.let { "\n$it" } ?: ""), DANGER)
        }
    }

    private fun status(text: String, color: Int) {
        statusV.text = text
        statusV.setTextColor(color)
    }

    private fun renderOverlay() {
        ui.setChosen(overlayOn, overlay)
        ui.setChosen(overlayOff, !overlay)
        permissionBtn.visibility = if (overlay && !Settings.canDrawOverlays(this)) View.VISIBLE else View.GONE
    }

    private fun save() {
        Prefs.setHost(this, hostE.text.toString())
        Prefs.setOverlay(this, overlay)
        startForegroundService(Intent(this, WorkoutService::class.java).setAction(WorkoutService.ACTION_RECONNECT))
        finish()
    }

    companion object {
        const val RESULT_EXIT = Activity.RESULT_FIRST_USER + 1
    }
}
