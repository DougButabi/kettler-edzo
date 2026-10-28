package hu.kettleredzo.app

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar
import kotlin.math.roundToInt

// Indító képernyő: ki edz most? Koppintás = belépés, hosszú nyomás = szerkesztés / törlés.
class ProfileActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var grid: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edzés közben nem lehet profilt váltani, ilyenkor egyenesen az edzésképernyőre megyünk.
        if (KettlerState.running) {
            openMain()
            return
        }
        ui = Ui(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(ui.ipx(60), ui.ipx(45), ui.ipx(30), 0)
        }
        root.addView(ui.text("KETTLER", 32, INK, ui.light, 0.35f))
        root.addView(ui.text("Ki edz ma?", 72, INK, ui.bold), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = ui.ipx(40)
        })
        root.addView(ui.text("Hosszan nyomva szerkesztheted vagy törölheted a profilt.", 20, MUTED, ui.regular),
            ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(14) })
        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, ui.ipx(30))
        }
        root.addView(ScrollView(this).apply { addView(grid) }, ui.lp(MATCH_PARENT, 0, 1f).apply {
            topMargin = ui.ipx(40)
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (::ui.isInitialized) refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun refresh() {
        grid.removeAllViews()
        val tiles = Db.get(this).profiles().map { profileTile(it) } + addTile()
        for (rowTiles in tiles.chunked(COLUMNS)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until COLUMNS) {
                val tile = rowTiles.getOrNull(i) ?: View(this)
                row.addView(tile, ui.lp(0, ui.ipx(250), 1f).apply { rightMargin = ui.ipx(30) })
            }
            grid.addView(row, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = ui.ipx(30) })
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = ui.rounded(CARD, 28)
        setPadding(ui.ipx(16), 0, ui.ipx(16), 0)
    }

    private fun profileTile(p: Profile): View {
        val card = card()
        val initial = ui.text(p.name.take(1).uppercase(), 48, Color.WHITE, ui.bold).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BAR)
            }
        }
        card.addView(initial, ui.lp(ui.ipx(100), ui.ipx(100)))
        card.addView(ui.text(p.name, 34, INK, ui.bold), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = ui.ipx(18)
        })
        card.addView(ui.text("${p.age} év · ${weightText(p.weightKg)} kg", 18, MUTED, ui.regular),
            ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(8) })
        card.setOnClickListener { select(p) }
        card.setOnLongClickListener {
            editProfile(p)
            true
        }
        return card
    }

    private fun addTile(): View {
        val card = card()
        card.addView(ui.text("+", 80, INK, ui.light))
        card.addView(ui.text("Új profil", 26, INK, ui.medium), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = ui.ipx(10)
        })
        card.setOnClickListener { editProfile(null) }
        return card
    }

    private fun select(p: Profile) {
        Prefs.setCurrentProfileId(this, p.id)
        openMain()
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun editProfile(p: Profile?) {
        val pad = (24 * resources.displayMetrics.density).roundToInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val name = EditText(this).apply {
            setText(p?.name ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val year = EditText(this).apply {
            hint = "pl. 1985"
            setText(p?.birthYear?.toString() ?: "")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val weight = EditText(this).apply {
            hint = "pl. 80"
            setText(p?.let { weightText(it.weightKg).replace(',', '.') } ?: "")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val sex = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        sex.addView(RadioButton(this).apply {
            id = ID_MALE
            text = "Férfi"
        })
        sex.addView(RadioButton(this).apply {
            id = ID_FEMALE
            text = "Nő"
        })
        when (p?.sex) {
            Sex.MALE -> sex.check(ID_MALE)
            Sex.FEMALE -> sex.check(ID_FEMALE)
            null -> {}
        }
        box.addView(TextView(this).apply { text = "Név" })
        box.addView(name)
        box.addView(TextView(this).apply { text = "Születési év" })
        box.addView(year)
        box.addView(TextView(this).apply { text = "Testsúly (kg)" })
        box.addView(weight)
        box.addView(TextView(this).apply { text = "Nem" })
        box.addView(sex)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (p == null) "Új profil" else "Profil szerkesztése")
            .setView(box)
            .setPositiveButton("Mentés", null)
            .setNegativeButton("Mégse", null)
        if (p != null) builder.setNeutralButton("Törlés") { _, _ -> confirmDelete(p) }
        val dialog = builder.create()

        // A Mentés gomb csak akkor zárja be az ablakot, ha minden adat rendben van.
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                val y = year.text.toString().trim().toIntOrNull()
                val w = weight.text.toString().trim().replace(',', '.').toDoubleOrNull()
                val s = when (sex.checkedRadioButtonId) {
                    ID_MALE -> Sex.MALE
                    ID_FEMALE -> Sex.FEMALE
                    else -> null
                }
                val thisYear = Calendar.getInstance().get(Calendar.YEAR)
                val error = when {
                    n.isEmpty() -> "Add meg a nevet."
                    y == null || y !in 1900..thisYear -> "A születési év nem jó."
                    w == null || w < 20 || w > 300 -> "A testsúly nem jó (kg-ban add meg)."
                    s == null -> "Válaszd ki a nemet."
                    else -> null
                }
                if (error != null) {
                    ui.toast(error)
                    return@setOnClickListener
                }
                Db.get(this).saveProfile(Profile(p?.id ?: 0L, n, y!!, w!!, s!!))
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }

    private fun confirmDelete(p: Profile) {
        AlertDialog.Builder(this)
            .setTitle("Törlöd ${p.name} profilját?")
            .setMessage("Az összes edzése is törlődik a naplóból. Ezt nem lehet visszavonni.")
            .setPositiveButton("Törlés") { _, _ ->
                Db.get(this).deleteProfile(p.id)
                if (Prefs.currentProfileId(this) == p.id) Prefs.setCurrentProfileId(this, -1)
                refresh()
            }
            .setNegativeButton("Mégse", null)
            .show()
    }

    private fun weightText(kg: Double) = if (kg % 1.0 == 0.0) kg.toInt().toString() else formatNum(kg, 1)

    companion object {
        private const val COLUMNS = 4
        private const val ID_MALE = 1001
        private const val ID_FEMALE = 1002
    }
}
