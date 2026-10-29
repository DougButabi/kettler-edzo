package hu.kettleredzo.app

import android.app.Activity

// Az app minden képernyőjének közös alapja: elrejti a rendszersávokat, és nyilvántartja,
// hogy az app elöl van-e. Amíg bármelyik képernyője látszik, az overlay-sáv rejtve marad.
open class KettlerActivity : Activity() {
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
}
