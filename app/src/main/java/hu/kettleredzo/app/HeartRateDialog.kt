package hu.kettleredzo.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// A szív ikonra koppintva felugró ablak: a pulzusmérő pontos állapota, tipp, elemszint, az utolsó
// adat kora és az utolsó események; Újracsatlakozás gombbal. Másodpercenként frissül.
object HeartRateDialog {
    fun show(a: Activity) {
        val ui = Ui(a)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.ipx(40), ui.ipx(28), ui.ipx(40), ui.ipx(8))
        }
        val statusV = ui.text("", 30, INK, ui.bold)
        val tipV = ui.text("", 22, MUTED, ui.regular)
        val detailV = ui.text("", 20, MUTED, ui.regular)
        val eventsV = ui.text("", 18, MUTED, ui.regular)
        box.addView(statusV)
        box.addView(tipV, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(12) })
        box.addView(detailV, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(20) })
        box.addView(ui.label("ESEMÉNYEK"), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) })
        box.addView(eventsV, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(6) })
        val scroll = ScrollView(a).apply { addView(box) }

        val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        fun render() {
            val (status, tip) = HeartRate.describe(a)
            statusV.text = "♥  $status"
            statusV.setTextColor(HeartRate.heartColor() ?: INK)
            tipV.text = tip ?: ""
            tipV.visibility = if (tip == null) android.view.View.GONE else android.view.View.VISIBLE
            val parts = mutableListOf<String>()
            Prefs.hrName(a).takeIf { it.isNotBlank() }?.let { parts += "Pánt: $it" }
            HeartRate.battery?.let { parts += "Elem: $it%" + if (it <= 20) " (cseréld!)" else "" }
            parts += when (val ago = HeartRate.lastDataAgoMs) {
                null -> "Még nem jött pulzus"
                else -> "Utolsó pulzus: " + if (ago < 2_000) "most" else "${ago / 1000} mp-e"
            }
            when (HeartRate.contact) {
                true -> parts += "Bőrkontaktus: van"
                false -> parts += "Bőrkontaktus: nincs"
                null -> {}
            }
            detailV.text = parts.joinToString("\n")
            val ev = HeartRate.events
            eventsV.text = if (ev.isEmpty()) "–" else ev.joinToString("\n") { "${clock.format(Date(it.at))}  ${it.text}" }
        }

        val dialog = AlertDialog.Builder(a)
            .setTitle("Pulzusmérő")
            .setView(scroll)
            .setPositiveButton("Bezár", null)
            .setNeutralButton("Újracsatlakozás", null)
            .create()
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                render()
                handler.postDelayed(this, 1_000)
            }
        }
        val listener: () -> Unit = { render() }
        dialog.setOnShowListener {
            // A gomb ne zárja be az ablakot, hogy látszódjon, mi történik
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (!HeartRate.paired(a)) ui.toast("Előbb párosítsd a pántot a Beállításokban.") else HeartRate.reconnect(a)
                render()
            }
            HeartRate.addListener(listener)
            handler.post(tick)
        }
        dialog.setOnDismissListener {
            HeartRate.removeListener(listener)
            handler.removeCallbacks(tick)
        }
        dialog.show()
    }
}

// TextView kattinthatóvá tétele a pulzus-ablakhoz (közös a képernyőkön)
fun android.view.View.opensHeartRate(a: Activity) = setOnClickListener { HeartRateDialog.show(a) }
