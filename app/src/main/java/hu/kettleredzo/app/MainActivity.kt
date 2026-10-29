package hu.kettleredzo.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

// Edzésképernyő
class MainActivity : KettlerActivity() {
    private lateinit var ui: Ui
    private lateinit var profile: Profile
    private var photoSex: Sex? = null

    private lateinit var nameV: TextView
    private lateinit var modePill: TextView
    private lateinit var speedV: TextView
    private lateinit var timeLabelV: TextView
    private lateinit var timeV: TextView
    private lateinit var progressRow: View
    private lateinit var progressFill: View
    private lateinit var progressFillBg: GradientDrawable
    private lateinit var progressRest: View
    private lateinit var percentV: TextView
    private lateinit var goalV: TextView
    private lateinit var hrV: TextView
    private lateinit var avgV: TextView
    private lateinit var distV: TextView
    private lateinit var kcalV: TextView
    private lateinit var levelV: TextView
    private lateinit var rpmV: TextView
    private lateinit var statusV: TextView
    private lateinit var mainIcon: ImageView
    private lateinit var mainText: TextView
    private lateinit var finishBtn: View

    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = loadProfile()
        if (p == null) {
            openProfiles()
            return
        }
        profile = p
        ui = Ui(this).apply { workoutButtons() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        if (Prefs.host(this).isBlank()) openSettings()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SETTINGS && resultCode == SettingsActivity.RESULT_EXIT) exitApp()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return // pl. a Beállításokból kiléptek az appból
        // A profilt közben szerkeszthették vagy törölhették.
        val p = loadProfile()
        if (p == null) {
            openProfiles()
            return
        }
        // Ha közben a profil nemét átírták, a fotó miatt újraépítjük a képernyőt.
        if (p.sex != photoSex) {
            recreate()
            return
        }
        profile = p
        nameV.text = p.name
        Prefs.applyMode(this)
        KettlerState.addListener(listener)
        startForegroundService(Intent(this, WorkoutService::class.java))
        render()
    }

    override fun onPause() {
        KettlerState.removeListener(listener)
        super.onPause()
    }

    // Edzés közben az a profil számít, amelyikkel elindult.
    private fun loadProfile(): Profile? {
        val id = if (KettlerState.running) KettlerState.profileId else Prefs.currentProfileId(this)
        return Db.get(this).profile(id)
    }

    private fun openProfiles() {
        startActivity(Intent(this, ProfileActivity::class.java))
        finish()
    }

    // ---- Kirajzolás ----

    private fun render() {
        val s = KettlerState
        speedV.text = formatNum(s.speedKmh, 1)
        modePill.text = modeLabel(s.mode, s.goal, s.program?.week)
        renderGoal()

        hrV.text = "–" // a pulzuspánt a 3. szakaszban jön
        avgV.text = formatNum(s.avgSpeedKmh, 1)
        distV.text = formatNum(s.workoutKm, 2)
        kcalV.text = formatNum(s.kcal, 0)
        levelV.text = Prefs.level(this).toString()
        rpmV.text = formatNum(s.rpm, 0)

        // A részletek (cím, hibaüzenet) a Beállítások / Kapcsolat oldalon látszanak.
        if (s.connected) status("● Kapcsolódva", GREEN) else status("Nincs kapcsolat", RED)

        when {
            !s.running -> mainButton(R.drawable.ic_play, "Indítás")
            s.paused -> mainButton(R.drawable.ic_play, "Folytatás")
            else -> mainButton(R.drawable.ic_pause, "Szünet")
        }
        finishBtn.visibility = if (s.paused) View.VISIBLE else View.GONE
    }

    // A jobb felső nagy mező a módhoz igazodik: eltelt idő, visszaszámlálás vagy programszakasz.
    private fun renderGoal() {
        val s = KettlerState
        var fill = YELLOW
        when (s.mode) {
            WorkoutMode.FREE -> {
                timeLabelV.text = "ELTELT IDŐ"
                timeV.text = formatTime(s.movingMs)
                goalV.text = ""
            }
            WorkoutMode.DISTANCE -> {
                timeLabelV.text = "HÁTRALÉVŐ TÁV"
                timeV.text = formatNum(s.remainingKm, 2)
                goalV.text = "km   ·   Cél: ${goalText(s.goal)} km"
            }
            WorkoutMode.KCAL -> {
                timeLabelV.text = "HÁTRALÉVŐ KALÓRIA"
                timeV.text = formatNum(s.remainingKcal, 0)
                goalV.text = "kcal   ·   Cél: ${goalText(s.goal)} kcal"
            }
            WorkoutMode.TIME -> {
                timeLabelV.text = "HÁTRALÉVŐ IDŐ"
                timeV.text = formatTimeUp(s.remainingMs)
                goalV.text = "Cél: ${goalText(s.goal)} perc"
            }
            WorkoutMode.PROGRAM -> {
                val program = s.program
                val seg = s.segmentState()
                if (program != null && seg != null) {
                    timeLabelV.text = "${seg.segment.type.title}   ·   ${seg.index + 1}/${program.segments.size}. SZAKASZ"
                    timeV.text = formatTimeUp(seg.remainingMs)
                    goalV.text = "${program.title}   ·   eltelt ${formatTime(s.programElapsedMs)}"
                    if (seg.segment.type == SegmentType.REST) fill = GREEN
                }
            }
        }
        if (s.goalReached) timeLabelV.text = "CÉL TELJESÍTVE! ✓"

        val p = s.progress
        progressRow.visibility = if (s.mode == WorkoutMode.FREE) View.GONE else View.VISIBLE
        progressFillBg.setColor(fill)
        (progressFill.layoutParams as LinearLayout.LayoutParams).weight = p
        (progressRest.layoutParams as LinearLayout.LayoutParams).weight = 1f - p
        progressFill.requestLayout()
        percentV.text = "${(p * 100).roundToInt()}%"
    }

    private fun status(text: String, color: Int) {
        statusV.text = text
        statusV.setTextColor(color)
    }

    private fun mainButton(icon: Int, text: String) {
        mainIcon.setImageResource(icon)
        mainText.text = text
    }

    // ---- Gombok ----

    private fun onMainButton() {
        val s = KettlerState
        when {
            !s.running -> {
                askOverlayPermission()
                s.start(profile.id)
            }
            s.paused -> s.resume()
            else -> s.pause()
        }
        render()
    }

    // Lezárja az edzést, és elmenti a naplóba, ha legalább 1 percig tartott.
    private fun finishWorkout() {
        val s = KettlerState
        if (!s.running) return
        s.finish()
        if (s.movingMs < MIN_SAVED_MS) {
            ui.toast("1 percnél rövidebb edzést nem mentek el.")
            return
        }
        Db.get(this).addWorkout(
            Workout(
                id = 0,
                profileId = s.profileId,
                startedAt = s.startedAt,
                movingMs = s.movingMs,
                distanceKm = s.workoutKm,
                avgSpeed = s.avgSpeedKmh,
                maxSpeed = s.maxSpeedKmh,
                kcal = s.kcal,
                level = Prefs.level(this),
                mode = s.mode,
                goal = if (s.mode == WorkoutMode.FREE || s.mode == WorkoutMode.PROGRAM) null else s.goal,
                programWeek = s.program?.week,
                completed = s.goalReached,
            ),
        )
        ui.toast("Edzés elmentve a naplóba.")
        Sync.runAsync(this)
    }

    private fun exitApp() {
        finishWorkout()
        stopService(Intent(this, WorkoutService::class.java))
        finishAndRemoveTask()
    }

    private fun openSettings() {
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SPORT, Sport.ELLIPTICAL.code),
            REQ_SETTINGS,
        )
    }

    private fun openMode() {
        if (KettlerState.running) {
            ui.toast("Edzés közben nem lehet módot váltani. Előbb fejezd be az edzést.")
            return
        }
        startActivity(Intent(this, ModeActivity::class.java))
    }

    private fun changeLevel(delta: Int) {
        Prefs.setLevel(this, Prefs.level(this) + delta)
        render()
    }

    private fun onProfileName() {
        if (KettlerState.running) {
            ui.toast("Edzés közben nem lehet profilt váltani. Előbb fejezd be az edzést.")
            return
        }
        openProfiles()
    }

    private fun askOverlayPermission() {
        if (ui.phone || !Prefs.overlay(this) || Settings.canDrawOverlays(this)) return
        AlertDialog.Builder(this)
            .setTitle("Engedély kell az overlay-sávhoz")
            .setMessage(
                "Ahhoz, hogy más appok (pl. videólejátszó) fölött is lásd az adatokat, kapcsold be a " +
                    "„Megjelenítés más alkalmazások felett” engedélyt a Kettler edző számára.",
            )
            .setPositiveButton("Beállítások") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Most nem", null)
            .show()
    }

    // ---- Felület felépítése ----

    // Fekvőben a fotó a bal félben, az adatok jobbra; állóban (telefon) egymás alatt (lásd ui.homeScreen).
    private fun buildUi(): View {
        // Férfi profilnál férfi, egyébként női fotó (a két kép kompozíciója azonos)
        photoSex = profile.sex
        val res = if (profile.sex == Sex.MALE) R.drawable.trener_ferfi else R.drawable.trener
        return ui.homeScreen(res, MACHINE_LEFT, MACHINE_RIGHT, buildTopBar(), buildPanel(), buildBottomBar(), BAR_H)
    }

    private fun buildTopBar(): View {
        // A profil neve nagyban; koppintásra profilváltás
        nameV = ui.text(profile.name, 46, INK, ui.bold).apply { setOnClickListener { onProfileName() } }
        modePill = ui.buttonText("", 20, INK).apply {
            background = ui.rounded(PILL, 30)
            setPadding(ui.ipx(40), ui.ipx(14), ui.ipx(40), ui.ipx(14))
            setOnClickListener { openMode() }
        }
        val right = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        right.addView(ui.pill("NAPLÓ") { startActivity(Intent(this, LogActivity::class.java)) })
        right.addView(ui.pill("BEÁLLÍTÁSOK") { openSettings() },
            ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(14) })
        return ui.topBar(ui.logo("VITO XL · ELLIPTIKUS TRÉNER", nameV) { openSports() }, modePill, right)
    }

    // Vissza a sportválasztóhoz (edzés közben nem lehet)
    private fun openSports() {
        if (KettlerState.running) {
            ui.toast("Edzés közben nem lehet sportot váltani. Előbb fejezd be az edzést.")
            return
        }
        startActivity(Intent(this, SportActivity::class.java))
        finish()
    }

    private fun buildPanel(): View {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 1. sor: sebesség és eltelt idő
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val speedCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        speedCol.addView(ui.label("SEBESSÉG"))
        speedV = ui.bigValue(BIG_VALUE)
        speedCol.addView(speedV, ui.lp(MATCH_PARENT, ui.ipx(BIG_H)).apply { topMargin = ui.ipx(14) })
        speedCol.addView(ui.text("km/h", 32, MUTED, ui.regular))
        row1.addView(speedCol, ui.lp(0, WRAP_CONTENT, 1f))

        row1.addView(ui.line(), ui.lp(1, MATCH_PARENT).apply {
            leftMargin = ui.ipx(36)
            rightMargin = ui.ipx(36)
        })

        val timeCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        timeLabelV = ui.label("ELTELT IDŐ")
        timeCol.addView(timeLabelV)
        timeV = ui.bigValue(BIG_VALUE)
        timeCol.addView(timeV, ui.lp(MATCH_PARENT, ui.ipx(BIG_H)).apply { topMargin = ui.ipx(14) })
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        this.progressRow = progressRow
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ui.rounded(TRACK, 7)
            clipToOutline = true
        }
        progressFillBg = ui.rounded(YELLOW, 7)
        progressFill = View(this).apply { background = progressFillBg }
        progressRest = View(this)
        track.addView(progressFill, ui.lp(0, MATCH_PARENT, 0f))
        track.addView(progressRest, ui.lp(0, MATCH_PARENT, 1f))
        progressRow.addView(track, ui.lp(0, ui.ipx(14), 1f))
        percentV = ui.text("0%", 18, INK, ui.medium)
        progressRow.addView(percentV, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(16) })
        timeCol.addView(progressRow, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(6) })
        goalV = ui.text("", 18, MUTED, ui.regular)
        timeCol.addView(goalV, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(14) })
        row1.addView(timeCol, ui.lp(0, WRAP_CONTENT, 1f))

        panel.addView(row1, ui.lp(MATCH_PARENT, WRAP_CONTENT))

        // 2. és 3. sor: kisebb csempék
        panel.addView(ui.line(), ui.lp(MATCH_PARENT, 1).apply {
            topMargin = ui.ipx(24)
            bottomMargin = ui.ipx(24)
        })
        hrV = ui.text("–", SMALL_VALUE, INK, ui.bold)
        avgV = ui.text("0,0", SMALL_VALUE, INK, ui.bold)
        panel.addView(tileRow(
            tile(R.drawable.ic_heart, "PULZUS", hrV, "bpm"),
            tile(R.drawable.ic_trend, "ÁTLAGSEBESSÉG", avgV, "km/h"),
        ))
        panel.addView(ui.line(), ui.lp(MATCH_PARENT, 1).apply {
            topMargin = ui.ipx(24)
            bottomMargin = ui.ipx(24)
        })
        distV = ui.text("0,00", SMALL_VALUE, INK, ui.bold)
        kcalV = ui.text("0", SMALL_VALUE, INK, ui.bold)
        panel.addView(tileRow(
            tile(R.drawable.ic_place, "TÁVOLSÁG", distV, "km"),
            tile(R.drawable.ic_fire, "BECSÜLT ENERGIA", kcalV, "kcal"),
        ))
        return panel
    }

    private fun tileRow(a: View, b: View): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(a, ui.lp(0, WRAP_CONTENT, 1f))
        row.addView(ui.line(), ui.lp(1, MATCH_PARENT).apply {
            leftMargin = ui.ipx(36)
            rightMargin = ui.ipx(36)
        })
        row.addView(b, ui.lp(0, WRAP_CONTENT, 1f))
        return row
    }

    private fun tile(iconRes: Int, title: String, value: TextView, unit: String): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ui.icon(iconRes, 30, INK))
        header.addView(ui.label(title), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(22) })
        col.addView(header)

        // A vízszintes LinearLayout alapból alapvonalra igazít, így a mértékegység a szám aljához kerül.
        val valueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.ipx(52), ui.ipx(14), 0, 0)
        }
        valueRow.addView(value)
        valueRow.addView(ui.text(unit, 26, MUTED, ui.regular), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = ui.ipx(14)
        })
        col.addView(valueRow)
        return col
    }

    // Alsó sáv: ellenállás, fordulat és kapcsolat (bal), Befejezés és a fő gomb (jobb). Állóban két sorban.
    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val gap = if (ui.portrait) 28 else 55

        // Ellenállás: a gép nem méri, kézzel állítjuk
        bar.addView(ui.icon(R.drawable.ic_bars, 44, Color.WHITE))
        val levelCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // WRAP_CONTENT kell, különben az oszlop a gombsor szélességét kapja, és a felirat levágódik
        levelCol.addView(barLabel("ELLENÁLLÁS"), ui.lp(WRAP_CONTENT, WRAP_CONTENT))
        val levelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        levelRow.addView(ui.roundButton("−") { changeLevel(-1) })
        levelV = ui.text("1", 50, Color.WHITE, ui.bold).apply {
            gravity = Gravity.CENTER
            minWidth = ui.ipx(70)
        }
        levelRow.addView(levelV)
        levelRow.addView(ui.roundButton("+") { changeLevel(1) })
        levelCol.addView(levelRow, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(6) })
        bar.addView(levelCol, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(if (ui.portrait) 16 else 30) })

        bar.addView(ui.line(BAR_LINE), ui.lp(1, ui.ipx(80)).apply {
            leftMargin = ui.ipx(gap)
            rightMargin = ui.ipx(gap)
        })

        // Fordulatszám
        bar.addView(ui.icon(R.drawable.ic_refresh, 44, Color.WHITE))
        val rpmCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rpmCol.addView(barLabel("FORDULAT"), ui.lp(WRAP_CONTENT, WRAP_CONTENT))
        val rpmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rpmV = ui.text("0", 50, Color.WHITE, ui.bold)
        rpmRow.addView(rpmV)
        rpmRow.addView(ui.text("rpm", 28, BAR_MUTED, ui.regular), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = ui.ipx(12)
        })
        rpmCol.addView(rpmRow, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(6) })
        bar.addView(rpmCol, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(if (ui.portrait) 16 else 30) })

        // Kapcsolat állapota. Állóban a nagy betűk miatt nem fér a felső sorba, a gombok mellé kerül.
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statusV = ui.text("", 20, RED, ui.medium).apply { gravity = if (ui.portrait) Gravity.START else Gravity.END }
        if (ui.portrait) {
            buttons.addView(statusV, ui.lp(0, WRAP_CONTENT, 1f).apply { rightMargin = ui.ipx(16) })
        } else {
            bar.addView(statusV, ui.lp(0, WRAP_CONTENT, 1f).apply {
                leftMargin = ui.ipx(30)
                rightMargin = ui.ipx(30)
            })
        }

        // Befejezés (csak szünetben) és a fő gomb
        val finish = ui.pillButton(R.drawable.ic_stop, "Befejezés", BAR_LINE, Color.WHITE) {
            finishWorkout()
            render()
        }
        finishBtn = finish
        buttons.addView(finish, ui.lp(ui.ipx(250), ui.ipx(85)).apply { rightMargin = ui.ipx(20) })

        val main = ui.pillButton(R.drawable.ic_play, "Indítás", BUTTON, INK) { onMainButton() }
        mainIcon = main.getChildAt(0) as ImageView
        mainText = main.getChildAt(1) as TextView
        buttons.addView(main, ui.lp(ui.ipx(290), ui.ipx(85)))
        return ui.bottomBar(bar, buttons)
    }

    private fun barLabel(s: String) = ui.barLabel(s)

    companion object {
        private const val MIN_SAVED_MS = 60_000L
        private const val REQ_SETTINGS = 1

        private const val BAR_H = 145
        private const val BIG_VALUE = 165
        private const val BIG_H = 175
        private const val SMALL_VALUE = 88

        // A gép vízszintes helye a fotón (a kép pixeleiben)
        private const val MACHINE_LEFT = 250f
        private const val MACHINE_RIGHT = 1120f
    }
}
