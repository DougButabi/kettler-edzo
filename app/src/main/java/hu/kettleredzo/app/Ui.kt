package hu.kettleredzo.app

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
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
