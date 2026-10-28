package hu.kettleredzo.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

// Edzésképernyő
class MainActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var profile: Profile

    private lateinit var profilePill: TextView
    private lateinit var speedV: TextView
    private lateinit var timeV: TextView
    private lateinit var progressFill: View
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
        ui = Ui(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        if (Prefs.host(this).isBlank()) showSettings()
    }

    override fun onResume() {
        super.onResume()
        // A profilt közben szerkeszthették vagy törölhették.
        val p = loadProfile()
        if (p == null) {
            openProfiles()
            return
        }
        profile = p
        profilePill.text = p.name
        hideSystemBars()
        KettlerState.activityVisible = true
        KettlerState.addListener(listener)
        startForegroundService(Intent(this, WorkoutService::class.java))
        render()
    }

    override fun onPause() {
        KettlerState.activityVisible = false
        KettlerState.removeListener(listener)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
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
        timeV.text = formatTime(s.movingMs)

        val goal = Prefs.goalMin(this)
        val p = if (goal > 0) (s.movingMs / (goal * 60_000f)).coerceIn(0f, 1f) else 0f
        (progressFill.layoutParams as LinearLayout.LayoutParams).weight = p
        (progressRest.layoutParams as LinearLayout.LayoutParams).weight = 1f - p
        progressFill.requestLayout()
        percentV.text = "${(p * 100).roundToInt()}%"
        goalV.text = "Cél: $goal perc"

        hrV.text = "–" // a pulzuspánt a 3. szakaszban jön
        avgV.text = formatNum(s.avgSpeedKmh, 1)
        distV.text = formatNum(s.workoutKm, 2)
        kcalV.text = formatNum(s.kcal, 0)
        levelV.text = Prefs.level(this).toString()
        rpmV.text = formatNum(s.rpm, 0)

        val host = Prefs.host(this)
        when {
            host.isBlank() -> status("Nincs megadva az ESP32 címe", ORANGE)
            s.connected -> status("● Kapcsolódva", GREEN)
            else -> status("Kapcsolódás: $host…" + (s.lastError?.let { "\n$it" } ?: ""), ORANGE)
        }

        when {
            !s.running -> mainButton(R.drawable.ic_play, "Indítás")
            s.paused -> mainButton(R.drawable.ic_play, "Folytatás")
            else -> mainButton(R.drawable.ic_pause, "Szünet")
        }
        finishBtn.visibility = if (s.paused) View.VISIBLE else View.GONE
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
            ),
        )
        ui.toast("Edzés elmentve a naplóba.")
    }

    private fun changeLevel(delta: Int) {
        Prefs.setLevel(this, Prefs.level(this) + delta)
        render()
    }

    private fun onProfilePill() {
        if (KettlerState.running) {
            ui.toast("Edzés közben nem lehet profilt váltani. Előbb fejezd be az edzést.")
            return
        }
        openProfiles()
    }

    private fun askOverlayPermission() {
        if (!Prefs.overlay(this) || Settings.canDrawOverlays(this)) return
        AlertDialog.Builder(this)
            .setTitle("Engedély kell az overlay-sávhoz")
            .setMessage(
                "Ahhoz, hogy a Kodi fölött is lásd az adatokat, kapcsold be a " +
                    "„Megjelenítés más alkalmazások felett” engedélyt a Kettler edző számára.",
            )
            .setPositiveButton("Beállítások") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Most nem", null)
            .show()
    }

    private fun showSettings() {
        val pad = (24 * resources.displayMetrics.density).roundToInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val host = EditText(this).apply {
            hint = "pl. 192.168.1.50"
            setText(Prefs.host(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            compactKeyboard(false)
        }
        val goal = EditText(this).apply {
            setText(Prefs.goalMin(this@MainActivity).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            compactKeyboard(true)
        }
        val overlay = CheckBox(this).apply {
            text = "Overlay-sáv más appok (pl. Kodi) fölött"
            isChecked = Prefs.overlay(this@MainActivity)
        }
        box.addView(TextView(this).apply { text = "Az ESP32 IP-címe" })
        box.addView(host)
        box.addView(TextView(this).apply { text = "Célidő (perc)" })
        box.addView(goal)
        box.addView(overlay)

        AlertDialog.Builder(this)
            .setTitle("Beállítások")
            .setView(box)
            .setPositiveButton("Mentés") { _, _ ->
                Prefs.setHost(this, host.text.toString())
                goal.text.toString().toIntOrNull()?.let { Prefs.setGoalMin(this, it.coerceIn(1, 600)) }
                Prefs.setOverlay(this, overlay.isChecked)
                startForegroundService(
                    Intent(this, WorkoutService::class.java).setAction(WorkoutService.ACTION_RECONNECT),
                )
                render()
            }
            .setNegativeButton("Mégse", null)
            .setNeutralButton("Kilépés az appból") { _, _ ->
                finishWorkout()
                stopService(Intent(this, WorkoutService::class.java))
                finishAndRemoveTask()
            }
            .show()
    }

    // ---- Felület felépítése ----

    private fun buildUi(): View {
        val root = FrameLayout(this)
        val photoArea = buildPhoto(root)
        root.addView(photoArea, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            bottomMargin = ui.ipx(BAR_H)
        })
        root.addView(buildTopBar(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(View(this), ui.lp(0, MATCH_PARENT, 0.525f))
        content.addView(buildPanel(), ui.lp(0, MATCH_PARENT, 0.475f))
        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            bottomMargin = ui.ipx(BAR_H)
        })

        root.addView(buildBottomBar(), FrameLayout.LayoutParams(MATCH_PARENT, ui.ipx(BAR_H), Gravity.BOTTOM))
        return root
    }

    // A fotót a magasságra méretezzük úgy, hogy a gép a bal félbe essen. A jobb oldalt
    // a fotó utolsó oszlopának kinyújtásával töltjük ki, így nem látszik illesztés.
    private fun buildPhoto(root: FrameLayout): View {
        val bmp = BitmapFactory.decodeResource(resources, R.drawable.trener)
        root.setBackgroundColor(bmp.getPixel(bmp.width - 1, 0))
        val strip = ImageView(this).apply {
            setImageBitmap(Bitmap.createBitmap(bmp, bmp.width - 2, 0, 1, bmp.height))
            scaleType = ImageView.ScaleType.MATRIX
        }
        val photo = ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.MATRIX
        }
        val area = FrameLayout(this)
        area.addView(strip, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        area.addView(photo, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        area.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            val s = min(h / bmp.height.toFloat(), w * 0.5f / (MACHINE_RIGHT - MACHINE_LEFT))
            val dy = h - bmp.height * s
            photo.imageMatrix = Matrix().apply {
                setScale(s, s)
                postTranslate(-MACHINE_LEFT * s + ui.px(20), dy)
            }
            strip.imageMatrix = Matrix().apply {
                setScale(w.toFloat(), s)
                postTranslate(0f, dy)
            }
        }
        return area
    }

    private fun buildTopBar(): View {
        val bar = FrameLayout(this).apply { setPadding(ui.ipx(35), ui.ipx(22), ui.ipx(28), 0) }

        val logo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logo.addView(ui.text("KETTLER", 32, INK, ui.light, 0.35f))
        logo.addView(ui.text("VITO XL · ELLIPTIKUS TRÉNER", 13, MUTED, ui.medium, 0.25f).apply {
            setPadding(0, ui.ipx(8), 0, 0)
        })
        bar.addView(logo, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START))

        val mode = ui.text("Szabad edzés", 20, INK, ui.medium).apply {
            background = ui.rounded(PILL, 30)
            setPadding(ui.ipx(40), ui.ipx(14), ui.ipx(40), ui.ipx(14))
        }
        bar.addView(mode, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))

        val right = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        profilePill = ui.pill(profile.name) { onProfilePill() }.apply {
            background = ui.rounded(BAR, 30)
            setTextColor(Color.WHITE)
        }
        right.addView(profilePill)
        right.addView(ui.pill("NAPLÓ") { startActivity(Intent(this, LogActivity::class.java)) },
            ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(14) })
        right.addView(ui.pill("BEÁLLÍTÁSOK") { showSettings() },
            ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(14) })
        bar.addView(right, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END))
        return bar
    }

    private fun buildPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.ipx(115), ui.ipx(45), ui.ipx(20))
        }

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
        timeCol.addView(ui.label("ELTELT IDŐ"))
        timeV = ui.bigValue(BIG_VALUE)
        timeCol.addView(timeV, ui.lp(MATCH_PARENT, ui.ipx(BIG_H)).apply { topMargin = ui.ipx(14) })
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ui.rounded(TRACK, 7)
            clipToOutline = true
        }
        progressFill = View(this).apply { background = ui.rounded(YELLOW, 7) }
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

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR)
            setPadding(ui.ipx(50), 0, ui.ipx(30), 0)
        }

        // Ellenállás: a gép nem méri, kézzel állítjuk
        bar.addView(ui.icon(R.drawable.ic_bars, 44, Color.WHITE))
        val levelCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        levelCol.addView(ui.text("ELLENÁLLÁS", 17, BAR_MUTED, ui.medium, 0.12f))
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
        levelRow.addView(ui.text("/ ${Prefs.LEVEL_MAX}", 28, BAR_MUTED, ui.regular),
            ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(14) })
        levelCol.addView(levelRow, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(6) })
        bar.addView(levelCol, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(30) })

        bar.addView(ui.line(BAR_LINE), ui.lp(1, ui.ipx(80)).apply {
            leftMargin = ui.ipx(55)
            rightMargin = ui.ipx(55)
        })

        // Fordulatszám
        bar.addView(ui.icon(R.drawable.ic_refresh, 44, Color.WHITE))
        val rpmCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rpmCol.addView(ui.text("FORDULAT", 17, BAR_MUTED, ui.medium, 0.12f))
        val rpmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rpmV = ui.text("0", 50, Color.WHITE, ui.bold)
        rpmRow.addView(rpmV)
        rpmRow.addView(ui.text("rpm", 28, BAR_MUTED, ui.regular), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = ui.ipx(12)
        })
        rpmCol.addView(rpmRow, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(6) })
        bar.addView(rpmCol, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(30) })

        // Kapcsolat állapota
        statusV = ui.text("", 16, ORANGE, ui.regular).apply {
            maxLines = 3
            gravity = Gravity.END
        }
        bar.addView(statusV, ui.lp(0, WRAP_CONTENT, 1f).apply {
            leftMargin = ui.ipx(30)
            rightMargin = ui.ipx(30)
        })

        // Befejezés (csak szünetben) és a fő gomb
        val finish = ui.pillButton(R.drawable.ic_stop, "Befejezés", BAR_LINE, Color.WHITE) {
            finishWorkout()
            render()
        }
        finishBtn = finish
        bar.addView(finish, ui.lp(ui.ipx(250), ui.ipx(85)).apply { rightMargin = ui.ipx(20) })

        val main = ui.pillButton(R.drawable.ic_play, "Indítás", BUTTON, INK) { onMainButton() }
        mainIcon = main.getChildAt(0) as ImageView
        mainText = main.getChildAt(1) as TextView
        bar.addView(main, ui.lp(ui.ipx(290), ui.ipx(85)))
        return bar
    }

    companion object {
        private const val MIN_SAVED_MS = 60_000L

        private const val BAR_H = 145
        private const val BIG_VALUE = 165
        private const val BIG_H = 175
        private const val SMALL_VALUE = 88

        // A gép vízszintes helye a fotón (a kép pixeleiben)
        private const val MACHINE_LEFT = 250f
        private const val MACHINE_RIGHT = 1120f
    }
}
