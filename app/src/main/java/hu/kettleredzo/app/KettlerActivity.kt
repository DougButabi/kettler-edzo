package hu.kettleredzo.app

import android.app.Activity
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
        KettlerState.visibleScreens++
        hideSystemBars()
    }

    override fun onPause() {
        KettlerState.visibleScreens--
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    companion object {
        private const val TABLET_DP = 600
    }
}
