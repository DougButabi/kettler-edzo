package hu.kettleredzo.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
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

// Edzésképernyő. A méreteket a 904 px magas mintaterv pixeleiben adjuk meg,
// és a képernyő magasságához skálázzuk őket.
class MainActivity : Activity() {
    private var u = 1f
    private fun px(v: Number) = v.toFloat() * u
    private fun ipx(v: Number) = px(v).roundToInt()

    private val bold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)

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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dm = resources.displayMetrics
        u = min(dm.widthPixels, dm.heightPixels) / 904f
        setContentView(buildUi())
        if (Prefs.host(this).isBlank()) showSettings()
    }

    override fun onResume() {
        super.onResume()
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

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
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
                s.start()
            }
            s.paused -> s.resume()
            else -> s.pause()
        }
        render()
    }

    private fun changeLevel(delta: Int) {
        Prefs.setLevel(this, Prefs.level(this) + delta)
        render()
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
        }
        val goal = EditText(this).apply {
            setText(Prefs.goalMin(this@MainActivity).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
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
                KettlerState.finish()
                stopService(Intent(this, WorkoutService::class.java))
                finishAndRemoveTask()
            }
            .show()
    }

    // ---- Felület felépítése ----

    private fun buildUi(): View {
        val root = FrameLayout(this)
        val photoArea = buildPhoto(root)
        root.addView(photoArea, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { bottomMargin = ipx(BAR_H) })
        root.addView(buildTopBar(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(View(this), lp(0, MATCH_PARENT, 0.525f))
        content.addView(buildPanel(), lp(0, MATCH_PARENT, 0.475f))
        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { bottomMargin = ipx(BAR_H) })

        root.addView(buildBottomBar(), FrameLayout.LayoutParams(MATCH_PARENT, ipx(BAR_H), Gravity.BOTTOM))
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
                postTranslate(-MACHINE_LEFT * s + px(20), dy)
            }
            strip.imageMatrix = Matrix().apply {
                setScale(w.toFloat(), s)
                postTranslate(0f, dy)
            }
        }
        return area
    }

    private fun buildTopBar(): View {
        val bar = FrameLayout(this).apply { setPadding(ipx(35), ipx(22), ipx(28), 0) }

        val logo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logo.addView(text("KETTLER", 32, INK, light, 0.35f))
        logo.addView(text("VITO XL · ELLIPTIKUS TRÉNER", 13, MUTED, medium, 0.25f).apply {
            setPadding(0, ipx(8), 0, 0)
        })
        bar.addView(logo, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START))

        val mode = text("Szabad edzés", 20, INK, medium).apply {
            background = rounded(PILL, 30)
            setPadding(ipx(40), ipx(14), ipx(40), ipx(14))
        }
        bar.addView(mode, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))

        val settings = text("BEÁLLÍTÁSOK", 15, INK, medium, 0.06f).apply {
            background = rounded(PILL, 30)
            setPadding(ipx(30), ipx(16), ipx(30), ipx(16))
            setOnClickListener { showSettings() }
        }
        bar.addView(settings, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END))
        return bar
    }

    private fun buildPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ipx(115), ipx(45), ipx(20))
        }

        // 1. sor: sebesség és eltelt idő
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val speedCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        speedCol.addView(label("SEBESSÉG"))
        speedV = bigValue()
        speedCol.addView(speedV, lp(MATCH_PARENT, ipx(BIG_H)).apply { topMargin = ipx(14) })
        speedCol.addView(text("km/h", 32, MUTED, Typeface.DEFAULT))
        row1.addView(speedCol, lp(0, WRAP_CONTENT, 1f))

        row1.addView(vLine(LINE), lp(1, MATCH_PARENT).apply {
            leftMargin = ipx(36)
            rightMargin = ipx(36)
        })

        val timeCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        timeCol.addView(label("ELTELT IDŐ"))
        timeV = bigValue()
        timeCol.addView(timeV, lp(MATCH_PARENT, ipx(BIG_H)).apply { topMargin = ipx(14) })
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(TRACK, 7)
            clipToOutline = true
        }
        progressFill = View(this).apply { background = rounded(YELLOW, 7) }
        progressRest = View(this)
        track.addView(progressFill, lp(0, MATCH_PARENT, 0f))
        track.addView(progressRest, lp(0, MATCH_PARENT, 1f))
        progressRow.addView(track, lp(0, ipx(14), 1f))
        percentV = text("0%", 18, INK, medium)
        progressRow.addView(percentV, lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ipx(16) })
        timeCol.addView(progressRow, lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ipx(6) })
        goalV = text("", 18, MUTED, Typeface.DEFAULT)
        timeCol.addView(goalV, lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ipx(14) })
        row1.addView(timeCol, lp(0, WRAP_CONTENT, 1f))

        panel.addView(row1, lp(MATCH_PARENT, WRAP_CONTENT))

        // 2. és 3. sor: kisebb csempék
        panel.addView(hLine(), lp(MATCH_PARENT, 1).apply {
            topMargin = ipx(24)
            bottomMargin = ipx(24)
        })
        hrV = text("–", SMALL_VALUE, INK, bold)
        avgV = text("0,0", SMALL_VALUE, INK, bold)
        panel.addView(tileRow(
            tile(R.drawable.ic_heart, "PULZUS", hrV, "bpm"),
            tile(R.drawable.ic_trend, "ÁTLAGSEBESSÉG", avgV, "km/h"),
        ))
        panel.addView(hLine(), lp(MATCH_PARENT, 1).apply {
            topMargin = ipx(24)
            bottomMargin = ipx(24)
        })
        distV = text("0,00", SMALL_VALUE, INK, bold)
        kcalV = text("0", SMALL_VALUE, INK, bold)
        panel.addView(tileRow(
            tile(R.drawable.ic_place, "TÁVOLSÁG", distV, "km"),
            tile(R.drawable.ic_fire, "BECSÜLT ENERGIA", kcalV, "kcal"),
        ))
        return panel
    }

    private fun tileRow(a: View, b: View): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(a, lp(0, WRAP_CONTENT, 1f))
        row.addView(vLine(LINE), lp(1, MATCH_PARENT).apply {
            leftMargin = ipx(36)
            rightMargin = ipx(36)
        })
        row.addView(b, lp(0, WRAP_CONTENT, 1f))
        return row
    }

    private fun tile(iconRes: Int, title: String, value: TextView, unit: String): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(icon(iconRes, 30, INK))
        header.addView(label(title), lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ipx(22) })
        col.addView(header)

        // A vízszintes LinearLayout alapból alapvonalra igazít, így a mértékegység a szám aljához kerül.
        val valueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ipx(52), ipx(14), 0, 0)
        }
        valueRow.addView(value)
        valueRow.addView(text(unit, 26, MUTED, Typeface.DEFAULT), lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = ipx(14)
        })
        col.addView(valueRow)
        return col
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR)
            setPadding(ipx(50), 0, ipx(30), 0)
        }

        // Ellenállás: a gép nem méri, kézzel állítjuk
        bar.addView(icon(R.drawable.ic_bars, 44, Color.WHITE))
        val levelCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        levelCol.addView(text("ELLENÁLLÁS", 17, BAR_MUTED, medium, 0.12f))
        val levelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        levelRow.addView(roundButton("−") { changeLevel(-1) })
        levelV = text("1", 50, Color.WHITE, bold).apply {
            gravity = Gravity.CENTER
            minWidth = ipx(70)
        }
        levelRow.addView(levelV)
        levelRow.addView(roundButton("+") { changeLevel(1) })
        levelCol.addView(levelRow, lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ipx(6) })
        bar.addView(levelCol, lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ipx(30) })

        bar.addView(vLine(BAR_LINE), lp(1, ipx(80)).apply {
            leftMargin = ipx(55)
            rightMargin = ipx(55)
        })

        // Fordulatszám
        bar.addView(icon(R.drawable.ic_refresh, 44, Color.WHITE))
        val rpmCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rpmCol.addView(text("FORDULAT", 17, BAR_MUTED, medium, 0.12f))
        val rpmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rpmV = text("0", 50, Color.WHITE, bold)
        rpmRow.addView(rpmV)
        rpmRow.addView(text("rpm", 28, BAR_MUTED, Typeface.DEFAULT), lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = ipx(12)
        })
        rpmCol.addView(rpmRow, lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ipx(6) })
        bar.addView(rpmCol, lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ipx(30) })

        // Kapcsolat állapota
        statusV = text("", 16, ORANGE, Typeface.DEFAULT).apply {
            maxLines = 3
            gravity = Gravity.END
        }
        bar.addView(statusV, lp(0, WRAP_CONTENT, 1f).apply {
            leftMargin = ipx(30)
            rightMargin = ipx(30)
        })

        // Befejezés (csak szünetben) és a fő gomb
        val finish = pillButton(R.drawable.ic_stop, "Befejezés", BAR_LINE, Color.WHITE) {
            KettlerState.finish()
            render()
        }
        finishBtn = finish
        bar.addView(finish, lp(ipx(250), ipx(85)).apply { rightMargin = ipx(20) })

        val main = pillButton(R.drawable.ic_play, "Indítás", BUTTON, INK) { onMainButton() }
        mainIcon = main.getChildAt(0) as ImageView
        mainText = main.getChildAt(1) as TextView
        bar.addView(main, lp(ipx(290), ipx(85)))
        return bar
    }

    // ---- Kis építőkockák ----

    private fun text(s: String, size: Number, color: Int, face: Typeface, spacing: Float = 0f) =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size))
            setTextColor(color)
            typeface = face
            letterSpacing = spacing
            includeFontPadding = false
            maxLines = 1
        }

    private fun label(s: String) = text(s, 20, INK, medium, 0.12f)

    // Nagy szám, ami szükség esetén magától kisebb lesz, hogy kiférjen (pl. 1:05:12).
    private fun bigValue() = text("0", BIG_VALUE, INK, bold).apply {
        setAutoSizeTextTypeUniformWithConfiguration(ipx(50), ipx(BIG_VALUE), 2, TypedValue.COMPLEX_UNIT_PX)
    }

    private fun icon(res: Int, size: Number, color: Int) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(ipx(size), ipx(size))
    }

    private fun rounded(color: Int, radius: Number) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = px(radius)
    }

    private fun vLine(color: Int) = View(this).apply { setBackgroundColor(color) }
    private fun hLine() = View(this).apply { setBackgroundColor(LINE) }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun roundButton(caption: String, onClick: () -> Unit) = text(caption, 30, Color.WHITE, medium).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(BAR_LINE)
        }
        layoutParams = LinearLayout.LayoutParams(ipx(52), ipx(52))
        setOnClickListener { onClick() }
    }

    private fun pillButton(iconRes: Int, caption: String, bg: Int, fg: Int, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(bg, 45)
            addView(icon(iconRes, 34, fg))
            addView(text(caption, 30, fg, medium), lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ipx(22) })
            setOnClickListener { onClick() }
        }

    companion object {
        private const val BAR_H = 145
        private const val BIG_VALUE = 165
        private const val BIG_H = 175
        private const val SMALL_VALUE = 88

        // A gép vízszintes helye a fotón (a kép pixeleiben)
        private const val MACHINE_LEFT = 250f
        private const val MACHINE_RIGHT = 1120f

        private val INK = Color.parseColor("#161616")
        private val MUTED = Color.parseColor("#3A3A3A")
        private val LINE = Color.parseColor("#BDBBB6")
        private val PILL = Color.parseColor("#CFCCC7")
        private val TRACK = Color.parseColor("#CBC9C4")
        private val YELLOW = Color.parseColor("#F3E46C")
        private val BAR = Color.parseColor("#2B2B2B")
        private val BAR_MUTED = Color.parseColor("#B5B5B5")
        private val BAR_LINE = Color.parseColor("#4A4A4A")
        private val BUTTON = Color.parseColor("#ECEAE6")
        private val GREEN = Color.parseColor("#8FD18F")
        private val ORANGE = Color.parseColor("#F0B070")
    }
}
