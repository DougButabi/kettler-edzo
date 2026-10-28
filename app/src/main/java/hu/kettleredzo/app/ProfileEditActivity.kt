package hu.kettleredzo.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar

// Profil létrehozása / szerkesztése teljes képernyőn, az app stílusában.
// Indításkor az EXTRA_PROFILE_ID-ben jön a szerkesztett profil; ha hiányzik, új profil készül.
class ProfileEditActivity : Activity() {
    private lateinit var ui: Ui
    private var profile: Profile? = null
    private var sex: Sex? = null

    private lateinit var avatar: TextView
    private lateinit var nameE: EditText
    private lateinit var yearE: EditText
    private lateinit var weightE: EditText
    private lateinit var maleBtn: TextView
    private lateinit var femaleBtn: TextView
    private lateinit var errorV: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        profile = Db.get(this).profile(intent.getLongExtra(EXTRA_PROFILE_ID, -1))
        sex = profile?.sex

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG)
        }
        root.addView(buildLeft(), ui.lp(0, MATCH_PARENT, 0.38f))
        root.addView(buildForm(), ui.lp(0, MATCH_PARENT, 0.62f))
        setContentView(root)
        updateSex()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // Bal oldal: cím és a névből élőben frissülő monogram
    private fun buildLeft(): LinearLayout {
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.ipx(60), ui.ipx(45), ui.ipx(30), ui.ipx(45))
        }
        left.addView(ui.text("KETTLER", 32, INK, ui.light, 0.35f))
        left.addView(
            ui.text(if (profile == null) "Új profil" else "Profil\nszerkesztése", 64, INK, ui.bold).apply { maxLines = 2 },
            ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(50) },
        )
        avatar = ui.text("", 110, Color.WHITE, ui.bold).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BAR)
            }
        }
        left.addView(avatar, ui.lp(ui.ipx(220), ui.ipx(220)).apply { topMargin = ui.ipx(50) })
        left.addView(
            ui.text("A testadatokból számolom majd\na max. pulzust és a kalóriát.", 20, MUTED, ui.regular).apply { maxLines = 2 },
            ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(40) },
        )
        return left
    }

    private fun buildForm(): ScrollView {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.ipx(20), ui.ipx(120), ui.ipx(60), ui.ipx(60))
        }
        val p = profile

        nameE = field(p?.name ?: "", "pl. Anna", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, false)
        nameE.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateAvatar()
        })
        form.addView(ui.label("NÉV"))
        form.addView(nameE, fieldLp())

        yearE = field(p?.birthYear?.toString() ?: "", "pl. 1985", InputType.TYPE_CLASS_NUMBER, false)
        weightE = field(
            p?.let { weightText(it.weightKg) } ?: "", "pl. 80",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, true,
        )
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(column("SZÜLETÉSI ÉV", yearE), ui.lp(0, WRAP_CONTENT, 1f))
        row.addView(column("TESTSÚLY (KG)", weightE), ui.lp(0, WRAP_CONTENT, 1f).apply { leftMargin = ui.ipx(30) })
        form.addView(row, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(36) })

        form.addView(ui.label("NEM"), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(36) })
        val sexRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        maleBtn = choice("Férfi") { sex = Sex.MALE; updateSex() }
        femaleBtn = choice("Nő") { sex = Sex.FEMALE; updateSex() }
        sexRow.addView(maleBtn, ui.lp(0, ui.ipx(90), 1f))
        sexRow.addView(femaleBtn, ui.lp(0, ui.ipx(90), 1f).apply { leftMargin = ui.ipx(30) })
        form.addView(sexRow, fieldLp())

        errorV = ui.text("", 22, DANGER, ui.medium)
        form.addView(errorV, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (p != null) {
            buttons.addView(button("Törlés", DANGER, Color.WHITE) { confirmDelete(p) }, ui.lp(ui.ipx(200), ui.ipx(85)))
        }
        buttons.addView(android.view.View(this), ui.lp(0, 1, 1f))
        buttons.addView(button("Mégse", PILL, INK) { finish() }, ui.lp(ui.ipx(200), ui.ipx(85)))
        buttons.addView(button("Mentés", BAR, Color.WHITE) { save() }, ui.lp(ui.ipx(260), ui.ipx(85)).apply {
            leftMargin = ui.ipx(20)
        })
        form.addView(buttons, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) })

        updateAvatar()
        return ScrollView(this).apply { addView(form) }
    }

    // ---- Építőkockák ----

    private fun field(value: String, hint: String, type: Int, last: Boolean) = EditText(this).apply {
        setText(value)
        this.hint = hint
        inputType = type
        maxLines = 1
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.px(38))
        typeface = ui.bold
        setTextColor(INK)
        setHintTextColor(LINE)
        background = ui.rounded(CARD, 22)
        setPadding(ui.ipx(28), ui.ipx(18), ui.ipx(28), ui.ipx(18))
        compactKeyboard(last)
    }

    private fun fieldLp() = ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(12) }

    private fun column(title: String, field: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(ui.label(title))
        addView(field, fieldLp())
    }

    private fun choice(caption: String, onClick: () -> Unit) = ui.text(caption, 30, INK, ui.medium).apply {
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun button(caption: String, bg: Int, fg: Int, onClick: () -> Unit) = ui.text(caption, 30, fg, ui.medium).apply {
        gravity = Gravity.CENTER
        background = ui.rounded(bg, 45)
        setOnClickListener { onClick() }
    }

    private fun updateSex() {
        for ((btn, value) in listOf(maleBtn to Sex.MALE, femaleBtn to Sex.FEMALE)) {
            val selected = sex == value
            btn.background = ui.rounded(if (selected) BAR else CARD, 45)
            btn.setTextColor(if (selected) Color.WHITE else INK)
        }
    }

    private fun updateAvatar() {
        avatar.text = nameE.text.toString().trim().take(1).uppercase().ifEmpty { "?" }
    }

    // ---- Mentés és törlés ----

    private fun save() {
        val n = nameE.text.toString().trim()
        val y = yearE.text.toString().trim().toIntOrNull()
        val w = weightE.text.toString().trim().replace(',', '.').toDoubleOrNull()
        val s = sex
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)
        val error = when {
            n.isEmpty() -> "Add meg a nevet."
            y == null || y !in 1900..thisYear -> "A születési év nem jó."
            w == null || w < 20 || w > 300 -> "A testsúly nem jó (kg-ban add meg)."
            s == null -> "Válaszd ki a nemet."
            else -> null
        }
        if (error != null) {
            errorV.text = error
            return
        }
        Db.get(this).saveProfile(Profile(profile?.id ?: 0L, n, y!!, w!!, s!!))
        finish()
    }

    private fun confirmDelete(p: Profile) {
        AlertDialog.Builder(this)
            .setTitle("Törlöd ${p.name} profilját?")
            .setMessage("Az összes edzése is törlődik a naplóból. Ezt nem lehet visszavonni.")
            .setPositiveButton("Törlés") { _, _ ->
                Db.get(this).deleteProfile(p.id)
                if (Prefs.currentProfileId(this) == p.id) Prefs.setCurrentProfileId(this, -1)
                finish()
            }
            .setNegativeButton("Mégse", null)
            .show()
    }

    private fun weightText(kg: Double) = if (kg % 1.0 == 0.0) kg.toInt().toString() else kg.toString()

    companion object {
        const val EXTRA_PROFILE_ID = "profileId"
    }
}
