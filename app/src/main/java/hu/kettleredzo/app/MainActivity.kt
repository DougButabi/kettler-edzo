package hu.kettleredzo.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

// 1. szakasz: csak azt ellenőrizzük, hogy a fordítás és a telepítés működik.
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            text = "Kettler edző\nverzió: ${packageManager.getPackageInfo(packageName, 0).versionName}"
            textSize = 32f
            gravity = Gravity.CENTER
        }
        setContentView(text)
    }
}
