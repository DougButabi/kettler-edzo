package hu.kettleredzo.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date

// A kerékpáros rész kezdőképernyője, a többi sport mintájára: fotó (a profil neme szerint),
// edzésmód, heti összesítő és a legutóbbi tekerések, alul az Indítás. Tekerés közben a
// térképes képernyő (RideActivity) a fő nézet; ide visszalépve a Folytatás oda visz vissza.
class BikeActivity : KettlerActivity() {
    private lateinit var ui: Ui
    private lateinit var profile: Profile

    private lateinit var modePill: TextView
    private lateinit var list: LinearLayout
    private lateinit var statusV: TextView
    private lateinit var mainBtn: LinearLayout

    private val dateFormat = SimpleDateFormat("MM. dd. HH:mm", HU)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Db.get(this).profile(Prefs.currentProfileId(this))
        if (p == null) {
            finish()
            return
        }
        profile = p
        ui = Ui(this).apply { workoutButtons() }
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        if (!::ui.isInitialized) return
        HeartRate.connect(this)
        Prefs.applyBikeMode(this)
        render()
    }

    private fun render() {
        val s = BikeState
        modePill.text = modeLabel(s.mode, s.goal, null)
        list.removeAllViews()
        if (s.running) {
            list.addView(ui.label("FOLYAMATBAN LÉVŐ TEKERÉS"))
            list.addView(ui.text("${formatNum(s.distanceKm, 2)} km · ${formatTime(s.movingMs)}", 56, INK, ui.bold),
                ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(10) })
            mainButton("Folytatás")
        } else {
            val db = Db.get(this)
            val week = db.totals(profile.id, startOfWeek(), Sport.BIKE)
            list.addView(ui.label("EZEN A HÉTEN"))
            list.addView(ui.text("${formatNum(week.distanceKm, 1)} km", 72, INK, ui.bold),
                ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(10) })
            list.addView(ui.text("${week.count} tekerés · ${formatTime(week.movingMs)} · ${formatNum(week.kcal, 0)} kcal", 24, MUTED, ui.regular),
                ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(6) })
            list.addView(ui.line(), ui.lp(MATCH_PARENT, 1).apply {
                topMargin = ui.ipx(24)
                bottomMargin = ui.ipx(20)
            })
            val rides = db.workouts(profile.id, limit = 5, sport = Sport.BIKE)
            list.addView(ui.label("LEGUTÓBBI TEKERÉSEK"))
            if (rides.isEmpty()) list.addView(ui.note("Még nincs mentett tekerés."), ui.fieldLp())
            for (w in rides) {
                list.addView(ui.text("${formatNum(w.distanceKm, 1)} km · ${formatTime(w.movingMs)} · átlag ${formatNum(w.avgSpeed, 1)} km/h", 26, INK, ui.bold),
                    ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(14) })
                list.addView(ui.text(dateFormat.format(Date(w.startedAt)) + "   ·   ↑ ${formatNum(w.elevGain ?: 0.0, 0)} m", 19, MUTED, ui.regular),
                    ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(4) })
            }
            mainButton("Indítás")
        }
        statusV.text = when {
            !hasLocationPermission() -> "Az induláskor helyengedélyt kérek."
            !locationEnabled() -> "A helymeghatározás ki van kapcsolva."
            else -> "GPS: a telefonét használom"
        }
    }

    private fun mainButton(text: String) {
        (mainBtn.getChildAt(1) as TextView).text = text
    }

    // ---- Indítás és engedélyek ----

    private fun onMainButton() {
        if (BikeState.running) {
            openRide()
            return
        }
        if (!hasLocationPermission()) {
            val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(perms.toTypedArray(), REQ_LOCATION)
            return
        }
        if (!locationEnabled()) {
            ui.toast("Kapcsold be a helymeghatározást (GPS), aztán nyomd meg újra az Indítást.")
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        Prefs.applyBikeMode(this)
        BikeState.start(profile, if (Prefs.autoPause(this)) Prefs.autoPauseKmh(this).toDouble() else 0.0)
        HeartRate.begin("bike:${BikeState.startedAt}", profile) { BikeState.isCounting && !BikeState.autoPaused }
        startForegroundService(Intent(this, BikeService::class.java))
        openRide()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCATION) return
        if (hasLocationPermission()) {
            onMainButton()
        } else {
            ui.toast("Helyengedély nélkül nem tudom mérni a tekerést.")
            render()
        }
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun locationEnabled(): Boolean {
        val lm = getSystemService(LocationManager::class.java)
        return if (Build.VERSION.SDK_INT >= 28) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun openRide() {
        startActivity(Intent(this, RideActivity::class.java))
    }

    private fun openMode() {
        if (BikeState.running) {
            ui.toast("Tekerés közben nem lehet módot váltani.")
            return
        }
        startActivity(Intent(this, ModeActivity::class.java).putExtra(ModeActivity.EXTRA_SPORT, Sport.BIKE.code))
    }

    private fun openProfiles() {
        if (BikeState.running) {
            ui.toast("Tekerés közben nem lehet profilt váltani.")
            return
        }
        startActivity(Intent(this, ProfileActivity::class.java))
        finishAffinity()
    }

    // ---- Felület ----

    private fun buildUi(): View {
        val nameV = ui.text(profile.name, 46, INK, ui.bold).apply { setOnClickListener { openProfiles() } }
        modePill = ui.buttonText("", 20, INK).apply {
            background = ui.rounded(PILL, 30)
            setPadding(ui.ipx(40), ui.ipx(14), ui.ipx(40), ui.ipx(14))
            setOnClickListener { openMode() }
        }
        val right = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        right.addView(ui.pill("NAPLÓ") { startActivity(Intent(this, LogActivity::class.java)) })
        right.addView(ui.pill("BEÁLLÍTÁSOK") {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SPORT, Sport.BIKE.code))
        }, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(14) })
        // A sportválasztó alattunk van a veremben, elég bezárni ezt a képernyőt.
        val top = ui.topBar(ui.logo("KERÉKPÁR", nameV) { finish() }, modePill, right)

        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(ScrollView(this).apply { addView(list) }, ui.lp(MATCH_PARENT, 0, 1f))

        val info = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statusV = ui.text("", 20, BAR_MUTED, ui.medium)
        info.addView(statusV, ui.lp(0, WRAP_CONTENT, 1f))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mainBtn = ui.pillButton(R.drawable.ic_play, "Indítás", BUTTON, INK) { onMainButton() }
        buttons.addView(mainBtn, ui.lp(ui.ipx(290), ui.ipx(85)))
        val bottom = ui.bottomBar(info, buttons)

        val photo = if (profile.sex == Sex.MALE) R.drawable.kerekpar_ferfi else R.drawable.kerekpar
        return ui.homeScreen(photo, PHOTO_LEFT, PHOTO_RIGHT, top, panel, bottom, BAR_H)
    }

    companion object {
        private const val REQ_LOCATION = 1
        private const val BAR_H = 145

        // A kerékpáros fotón a kerékpár vízszintes helye (a kép pixeleiben, 1200 px széles kép)
        private const val PHOTO_LEFT = 260f
        private const val PHOTO_RIGHT = 910f
    }
}
