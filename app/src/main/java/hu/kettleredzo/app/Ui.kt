package hu.kettleredzo.app

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min
import kotlin.math.roundToInt

val BG: Int = Color.parseColor("#DEDCD8")
val CARD: Int = Color.parseColor("#ECEAE6")
val INK: Int = Color.parseColor("#161616")
val MUTED: Int = Color.parseColor("#3A3A3A")
val LINE: Int = Color.parseColor("#BDBBB6")
val PILL: Int = Color.parseColor("#CFCCC7")
val TRACK: Int = Color.parseColor("#CBC9C4")
val YELLOW: Int = Color.parseColor("#F3E46C")
val BAR: Int = Color.parseColor("#2B2B2B")
val BAR_MUTED: Int = Color.parseColor("#B5B5B5")
val BAR_LINE: Int = Color.parseColor("#4A4A4A")
val BUTTON: Int = Color.parseColor("#ECEAE6")
val GREEN: Int = Color.parseColor("#8FD18F")
val ORANGE: Int = Color.parseColor("#F0B070")
val DANGER: Int = Color.parseColor("#B3473B")
val RED: Int = Color.parseColor("#FF6B5E") // sötét háttéren jól látható piros

// Álló nézetben az edzésképernyők fotósávjának magassága (mintaterv-pixelben)
private const val PORTRAIT_PHOTO_H = 420

// Közös építőkockák a képernyőkhöz. A méreteket a 904 px magas mintaterv pixeleiben
// adjuk meg, és a képernyő magasságához skálázzuk őket.
class Ui(private val ctx: Context) {
    val u: Float = ctx.resources.displayMetrics.let { min(it.widthPixels, it.heightPixels) / 904f }
    fun px(v: Number) = v.toFloat() * u
    fun ipx(v: Number) = px(v).roundToInt()

    val bold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val regular: Typeface = Typeface.DEFAULT

    fun text(s: String, size: Number, color: Int, face: Typeface = medium, spacing: Float = 0f) =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size))
            setTextColor(color)
            typeface = face
            letterSpacing = spacing
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    fun label(s: String, color: Int = INK) = text(s, 20, color, medium, 0.12f)

    // Nagy szám, ami szükség esetén magától kisebb lesz, hogy kiférjen (pl. 1:05:12).
    fun bigValue(size: Number) = text("0", size, INK, bold).apply {
        ellipsize = null
        setAutoSizeTextTypeUniformWithConfiguration(ipx(40), ipx(size), 2, TypedValue.COMPLEX_UNIT_PX)
    }

    fun icon(res: Int, size: Number, color: Int) = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(ipx(size), ipx(size))
    }

    fun rounded(color: Int, radius: Number) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = px(radius)
    }

    fun line(color: Int = LINE) = View(ctx).apply { setBackgroundColor(color) }

    fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    // Lekerekített, világos szöveges gomb (pl. BEÁLLÍTÁSOK)
    fun pill(caption: String, onClick: () -> Unit) = text(caption, 15, INK, medium, 0.06f).apply {
        background = rounded(PILL, 30)
        setPadding(ipx(30), ipx(16), ipx(30), ipx(16))
        setOnClickListener { onClick() }
    }

    fun roundButton(caption: String, onClick: () -> Unit) = text(caption, 30, Color.WHITE, medium).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(BAR_LINE)
        }
        layoutParams = LinearLayout.LayoutParams(ipx(52), ipx(52))
        setOnClickListener { onClick() }
    }

    fun pillButton(iconRes: Int, caption: String, bg: Int, fg: Int, onClick: () -> Unit) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(bg, 45)
            addView(icon(iconRes, 34, fg))
            addView(text(caption, 30, fg, medium), lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ipx(22) })
            setOnClickListener { onClick() }
        }

    // ---- Teljes képernyős űrlapok (profil, beállítások, edzésmód) ----

    // Bal oldali sáv: KETTLER logó és nagy cím. A hívó további elemeket tehet alá.
    // Álló nézetben (telefon) a képernyő tetejére kerül, kisebb címmel.
    fun sidePanel(title: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (portrait) setPadding(ipx(35), ipx(30), ipx(35), ipx(10)) else setPadding(ipx(60), ipx(45), ipx(30), ipx(45))
        addView(text("KETTLER", 32, INK, light, 0.35f))
        addView(text(if (portrait) title.replace('\n', ' ') else title, if (portrait) 48 else 64, INK, bold).apply { maxLines = 2 },
            lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ipx(if (portrait) 16 else 50) })
    }

    // ---- Álló / fekvő elrendezés ----
    // A tablet mindig fekvő. Telefonon a forgatás szerint vált (lásd KettlerActivity), és álló
    // nézetben a bal-jobb panelek egymás alá kerülnek.

    val portrait: Boolean get() = ctx.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    // Két részes képernyő gyökere: fekvőben bal-jobb, állóban fent-lent
    fun splitRoot() = LinearLayout(ctx).apply {
        orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        setBackgroundColor(BG)
    }

    fun sideLp(weight: Float) = if (portrait) lp(MATCH_PARENT, WRAP_CONTENT) else lp(0, MATCH_PARENT, weight)
    fun mainLp(weight: Float) = if (portrait) lp(MATCH_PARENT, 0, 1f) else lp(0, MATCH_PARENT, weight)

    // A jobb oldali (állóban alsó) tartalom belső margója
    fun paneLayout(pane: LinearLayout, top: Int = 120) {
        if (portrait) pane.setPadding(ipx(35), ipx(24), ipx(35), ipx(40)) else pane.setPadding(ipx(40), ipx(top), ipx(60), ipx(60))
    }

    // Menügombok helye: fekvőben a bal sávban egymás alatt, állóban vízszintesen görgethető sorban.
    fun nav(side: LinearLayout): LinearLayout {
        if (!portrait) return side
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        side.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }, lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ipx(20) })
        return row
    }

    fun navButton(caption: String, onClick: () -> Unit) = choice(caption) { onClick() }.apply {
        setPadding(ipx(32), 0, ipx(32), 0)
    }

    fun navLp(first: Boolean) = if (portrait) {
        lp(WRAP_CONTENT, ipx(72)).apply { if (!first) leftMargin = ipx(12) }
    } else {
        lp(MATCH_PARENT, ipx(78)).apply { topMargin = ipx(if (first) 40 else 14) }
    }

    // Edzésképernyők (elliptikus, súlyzós, kerékpár) közös váza. Fekvőben: háttérfotó a bal
    // félben, a tartalom a jobb oldalon, felül a fejléc, alul a sötét sáv. Állóban: fejléc,
    // fotó, tartalom, sáv egymás alatt.
    fun homeScreen(photoRes: Int, left: Float, right: Float, topBar: View, panel: View, bottomBar: View, barH: Int): View {
        if (portrait) {
            val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            root.addView(topBar, lp(MATCH_PARENT, WRAP_CONTENT))
            root.addView(photoArea(root, photoRes, left, right), lp(MATCH_PARENT, ipx(PORTRAIT_PHOTO_H)))
            panel.setPadding(ipx(35), ipx(24), ipx(35), ipx(20))
            root.addView(panel, lp(MATCH_PARENT, 0, 1f))
            root.addView(bottomBar, lp(MATCH_PARENT, WRAP_CONTENT))
            return root
        }
        val root = FrameLayout(ctx)
        root.addView(photoArea(root, photoRes, left, right), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            bottomMargin = ipx(barH)
        })
        root.addView(topBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(View(ctx), lp(0, MATCH_PARENT, 0.525f))
        panel.setPadding(0, ipx(115), ipx(45), ipx(20))
        content.addView(panel, lp(0, MATCH_PARENT, 0.475f))
        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { bottomMargin = ipx(barH) })
        root.addView(bottomBar, FrameLayout.LayoutParams(MATCH_PARENT, ipx(barH), Gravity.BOTTOM))
        return root
    }

    // Fejléc: bal oldalt a logó, középen egy opcionális gomb (pl. edzésmód), jobb oldalt a gombok.
    // Állóban a középső gomb a logó alá kerül.
    fun topBar(logo: View, center: View?, right: View): View {
        if (portrait) {
            val bar = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ipx(35), ipx(22), ipx(28), ipx(12))
            }
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(logo, lp(0, WRAP_CONTENT, 1f))
            row.addView(right)
            bar.addView(row)
            if (center != null) bar.addView(center, lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ipx(6) })
            return bar
        }
        val bar = FrameLayout(ctx).apply { setPadding(ipx(35), ipx(22), ipx(28), 0) }
        bar.addView(logo, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START))
        if (center != null) bar.addView(center, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        bar.addView(right, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END))
        return bar
    }

    // Logó: KETTLER, alcím (a sport), és a profil neve nagyban. A logóra / sportnévre koppintva
    // onSports fut (vissza a sportválasztóhoz), a névre koppintva a hívó dönt (profilváltás).
    fun logo(subtitle: String, nameV: TextView, onSports: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setOnClickListener { onSports() }
        }
        head.addView(text("KETTLER", 32, INK, light, 0.35f))
        head.addView(text("‹ $subtitle", 13, MUTED, medium, 0.25f).apply { setPadding(0, ipx(8), 0, 0) })
        addView(head)
        nameV.setPadding(0, ipx(18), 0, ipx(6))
        addView(nameV)
    }

    // Alsó sötét sáv: bal oldalt az adatok (info), jobb oldalt a gombok. Állóban két sorban.
    fun bottomBar(info: LinearLayout, buttons: LinearLayout): View {
        info.gravity = Gravity.CENTER_VERTICAL
        buttons.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        if (portrait) {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(BAR)
                setPadding(ipx(35), ipx(20), ipx(30), ipx(24))
                addView(info, lp(MATCH_PARENT, WRAP_CONTENT))
                addView(buttons, lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ipx(18) })
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR)
            setPadding(ipx(50), 0, ipx(30), 0)
            addView(info, lp(0, WRAP_CONTENT, 1f))
            addView(buttons)
        }
    }

    // Felirat az alsó sávban. A betűköz miatt az Android néha kevesebb helyet mér,
    // és levágná a végét („FORDU…”), ezért nincs kipontozás, és kap egy kis ráhagyást.
    fun barLabel(s: String) = text(s, 17, BAR_MUTED, medium, 0.12f).apply {
        ellipsize = null
        setPadding(0, 0, ipx(10), 0)
    }

    fun field(value: String, hint: String, type: Int, last: Boolean) = EditText(ctx).apply {
        setText(value)
        this.hint = hint
        inputType = type
        maxLines = 1
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(38))
        typeface = bold
        setTextColor(INK)
        setHintTextColor(LINE)
        background = rounded(CARD, 22)
        setPadding(ipx(28), ipx(18), ipx(28), ipx(18))
        compactKeyboard(last)
    }

    // Mezők közötti alapértelmezett térköz
    fun fieldLp() = lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ipx(12) }

    // Nagy, lekerekített szöveges gomb
    fun button(caption: String, bg: Int, fg: Int, onClick: () -> Unit) = text(caption, 30, fg, medium).apply {
        gravity = Gravity.CENTER
        background = rounded(bg, 45)
        setOnClickListener { onClick() }
    }

    // Választógomb (pl. Férfi / Nő); a kijelölést a setChosen állítja
    fun choice(caption: String, size: Number = 30, onClick: () -> Unit) = text(caption, size, INK, medium).apply {
        gravity = Gravity.CENTER
        setChosen(this, false)
        setOnClickListener { onClick() }
    }

    fun setChosen(view: TextView, chosen: Boolean) {
        view.background = rounded(if (chosen) BAR else CARD, 45)
        view.setTextColor(if (chosen) Color.WHITE else INK)
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    // Teljes képernyős háttérfotó (edzésképernyők). A fotót a magasságra méretezzük úgy, hogy a
    // tárgy (a kép left..right pixelei közötti rész) a bal félbe essen. A jobb oldalt a fotó utolsó
    // oszlopának kinyújtásával töltjük ki, így nem látszik illesztés. A root háttere a fotó sarokszíne.
    // Állóban a fotó a saját sávját tölti ki, a tárgy középre kerül.
    fun photoArea(root: View, res: Int, left: Float, right: Float): View {
        val share = if (portrait) 1f else 0.5f
        val centered = portrait
        val bmp = BitmapFactory.decodeResource(ctx.resources, res)
        root.setBackgroundColor(bmp.getPixel(bmp.width - 1, 0))
        val strip = ImageView(ctx).apply {
            setImageBitmap(Bitmap.createBitmap(bmp, bmp.width - 2, 0, 1, bmp.height))
            scaleType = ImageView.ScaleType.MATRIX
        }
        val photo = ImageView(ctx).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.MATRIX
        }
        val area = FrameLayout(ctx)
        area.addView(strip, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        area.addView(photo, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        area.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            val s = min(h / bmp.height.toFloat(), w * share / (right - left))
            val dy = h - bmp.height * s
            val dx = if (centered) (w - (right - left) * s) / 2 - left * s else -left * s + px(20)
            photo.imageMatrix = Matrix().apply {
                setScale(s, s)
                postTranslate(dx, dy)
            }
            strip.imageMatrix = Matrix().apply {
                setScale(w.toFloat(), s)
                postTranslate(0f, dy)
            }
        }
        return area
    }

    // Több soros, halvány magyarázó szöveg
    fun note(s: String, lines: Int = 4) = text(s, 22, MUTED, regular).apply {
        maxLines = lines
        ellipsize = null
    }

    // Gép vagy gyakorlat képe fehér, lekerekített kártyán. Kép nélkül a név kezdőbetűje látszik.
    fun picture(image: String?, name: String, size: Number): View {
        val box = FrameLayout(ctx).apply {
            background = rounded(Color.WHITE, 18)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ipx(size), ipx(size))
        }
        val bmp = Images.load(ctx, image)
        if (bmp != null) {
            box.addView(ImageView(ctx).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(ipx(6), ipx(6), ipx(6), ipx(6))
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else {
            box.addView(text(name.take(1).uppercase(), size.toFloat() * 0.45f, LINE, bold).apply {
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        return box
    }

    // Kis −/+ léptető: a középső szöveget a hívó frissíti.
    fun stepper(value: TextView, onMinus: () -> Unit, onPlus: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(choice("−", 34) { onMinus() }, lp(ipx(70), ipx(70)))
        value.gravity = Gravity.CENTER
        addView(value, lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = ipx(8)
            rightMargin = ipx(8)
        })
        addView(choice("+", 34) { onPlus() }, lp(ipx(70), ipx(70)))
    }
}

// Fekvő módban a billentyűzet alapból az egész képernyőt elfoglalja, és csak a szerkesztett
// mezőt mutatja. Ez kikapcsolja, és a billentyűzeten „Tovább” / „Kész” gomb lesz.
fun EditText.compactKeyboard(last: Boolean) {
    imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
        (if (last) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT)
}

// Edzés közben a rendszersávok ne vegyenek el helyet.
@Suppress("DEPRECATION")
fun Activity.hideSystemBars() {
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
        View.SYSTEM_UI_FLAG_FULLSCREEN or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
}
