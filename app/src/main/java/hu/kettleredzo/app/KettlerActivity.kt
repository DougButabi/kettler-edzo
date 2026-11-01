package hu.kettleredzo.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.content.pm.ActivityInfo
import android.os.Bundle

// Az app minden képernyőjének közös alapja: elrejti a rendszersávokat, és nyilvántartja,
// hogy az app elöl van-e. Amíg bármelyik képernyője látszik, az overlay-sáv rejtve marad.
// Tableten mindig fekvő; telefonon a forgatás szerint álló vagy fekvő (a képernyők mindkettőre
// fel vannak készítve, lásd Ui.portrait).
open class KettlerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = if (resources.configuration.smallestScreenWidthDp >= TABLET_DP) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_USER
        }
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        IdleExit.cancel()
        KettlerState.visibleScreens++
        hideSystemBars()
    }

    override fun onPause() {
        KettlerState.visibleScreens--
        if (KettlerState.visibleScreens <= 0) IdleExit.schedule(applicationContext)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }
}

// Ha az app 10 percig a háttérben van, és nem fut edzés (elliptikus, kerékpár, folyamatban lévő súlyzós),
// magától kilép: leáll az ESP-kapcsolat, lecsatlakozik a pulzusmérő, és eltűnik a legutóbbi appok közül.
object IdleExit {
    private const val DELAY_MS = 10 * 60 * 1000L
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun schedule(c: Context) {
        cancel()
        val r = Runnable { exitIfIdle(c) }
        pending = r
        handler.postDelayed(r, DELAY_MS)
    }

    fun cancel() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    private fun exitIfIdle(c: Context) {
        pending = null
        if (KettlerState.appVisible || KettlerState.running || BikeState.running) return
        val db = Db.get(c)
        if (db.profiles().any { db.activeLift(it.id) != null }) return
        c.stopService(Intent(c, WorkoutService::class.java))
        HeartRate.disconnect()
        c.getSystemService(ActivityManager::class.java)?.appTasks?.forEach { it.finishAndRemoveTask() }
    }
}
