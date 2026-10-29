package hu.kettleredzo.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Gép vagy gyakorlat létrehozása / szerkesztése: név, kép, és gyakorlatnál a hozzá tartozó gép.
// Az EXTRA_KIND mondja meg, melyik; ha nincs EXTRA_ID, új elem készül.
class CatalogEditActivity : KettlerActivity() {
    private lateinit var ui: Ui
    private lateinit var kind: String
    private var equipment: Equipment? = null
    private var exercise: Exercise? = null

    private var image: String? = null
    private var equipmentId: Long? = null

    private lateinit var pictureBox: FrameLayout
    private lateinit var nameE: EditText
    private lateinit var errorV: TextView
    private val equipmentButtons = mutableMapOf<Long?, TextView>()

    private val isExercise get() = kind == KIND_EXERCISE
    private val seedKey get() = if (isExercise) exercise?.seedKey else equipment?.seedKey

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_EXERCISE
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val db = Db.get(this)
        if (isExercise) {
            exercise = db.exercise(id)
            image = exercise?.image
            equipmentId = exercise?.equipmentId
        } else {
            equipment = db.equipment(id)
            image = equipment?.image
        }
        // Forgatás után (telefon) a választott kép és gép megmarad.
        savedInstanceState?.let {
            image = it.getString(STATE_IMAGE)
            equipmentId = if (it.containsKey(STATE_EQUIPMENT)) it.getLong(STATE_EQUIPMENT) else null
        }

        val root = ui.splitRoot()
        root.addView(buildLeft(), ui.sideLp(0.38f))
        root.addView(buildForm(), ui.mainLp(0.62f))
        setContentView(root)
        savedInstanceState?.getString(STATE_NAME)?.let { nameE.setText(it) }
        updatePicture()
        updateEquipment()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_NAME, nameE.text.toString())
        outState.putString(STATE_IMAGE, image)
        equipmentId?.let { outState.putLong(STATE_EQUIPMENT, it) }
    }

    private fun buildLeft(): LinearLayout {
        val isNew = (if (isExercise) exercise else equipment) == null
        val title = when {
            isExercise && isNew -> "Új gyakorlat"
            isExercise -> "Gyakorlat"
            isNew -> "Új gép"
            else -> "Gép"
        }
        val left = ui.sidePanel(title)
        // Állóban a kép és mellette a két gomb egy sorban fér el.
        val row = LinearLayout(this).apply {
            orientation = if (ui.portrait) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (ui.portrait) Gravity.CENTER_VERTICAL else Gravity.START
        }
        val size = if (ui.portrait) 220 else 300
        pictureBox = FrameLayout(this)
        row.addView(pictureBox, ui.lp(ui.ipx(size), ui.ipx(size)))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        buttons.addView(ui.button("Kép választása", PILL, INK) { pickImage() }, ui.lp(ui.ipx(300), ui.ipx(78)))
        val default = if (isExercise) Catalog.defaultExerciseImage(seedKey) else Catalog.defaultEquipmentImage(seedKey)
        if (default != null) {
            buttons.addView(ui.button("Eredeti kép", PILL, INK) {
                image = default
                updatePicture()
            }, ui.lp(ui.ipx(300), ui.ipx(78)).apply { topMargin = ui.ipx(14) })
        }
        row.addView(buttons, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            if (ui.portrait) leftMargin = ui.ipx(30) else topMargin = ui.ipx(20)
        })
        left.addView(row, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(if (ui.portrait) 20 else 40) })
        return left
    }

    private fun buildForm(): ScrollView {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.paneLayout(form)
        val currentName = if (isExercise) exercise?.name else equipment?.name
        nameE = ui.field(currentName ?: "", if (isExercise) "pl. Fekvenyomás" else "pl. Fekvenyomó pad",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, true)
        form.addView(ui.label("NÉV"))
        form.addView(nameE, ui.fieldLp())

        if (isExercise) {
            form.addView(ui.label("GÉP"), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(36) })
            val options = Db.get(this).equipmentList().map { it.id as Long? to it.name } + (null to "Gép nélkül")
            for (row in options.chunked(2)) {
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.forEachIndexed { i, (id, name) ->
                    val b = ui.choice(name, 26) {
                        equipmentId = id
                        updateEquipment()
                    }
                    equipmentButtons[id] = b
                    line.addView(b, ui.lp(0, ui.ipx(78), 1f).apply { if (i > 0) leftMargin = ui.ipx(20) })
                }
                if (row.size == 1) line.addView(View(this), ui.lp(0, 1, 1f).apply { leftMargin = ui.ipx(20) })
                form.addView(line, ui.fieldLp())
            }
        }

        errorV = ui.text("", 22, DANGER, ui.medium)
        form.addView(errorV, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (exercise != null || equipment != null) {
            buttons.addView(ui.button("Törlés", DANGER, Color.WHITE) { confirmDelete() }, ui.lp(ui.ipx(200), ui.ipx(85)))
        }
        buttons.addView(View(this), ui.lp(0, 1, 1f))
        buttons.addView(ui.button("Mégse", PILL, INK) { finish() }, ui.lp(ui.ipx(200), ui.ipx(85)))
        buttons.addView(ui.button("Mentés", BAR, Color.WHITE) { save() }, ui.lp(ui.ipx(260), ui.ipx(85)).apply {
            leftMargin = ui.ipx(20)
        })
        form.addView(buttons, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) })
        if (isExercise) {
            form.addView(ui.note("A beépített rajzok az Everkinetic nyílt gyűjteményéből valók (CC-BY-SA). Saját fotóra bármikor lecserélheted."),
                ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(36) })
        }
        return ScrollView(this).apply { addView(form) }
    }

    private fun updatePicture() {
        pictureBox.removeAllViews()
        pictureBox.addView(ui.picture(image, nameE.text.toString().ifBlank { "?" }, if (ui.portrait) 220 else 300),
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun updateEquipment() {
        equipmentButtons.forEach { (id, b) -> ui.setChosen(b, id == equipmentId) }
    }

    // ---- Kép választása a galériából / fájlkezelőből ----

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(intent, "Kép választása"), REQ_IMAGE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK || uri == null) return
        val imported = try {
            Images.importImage(this, uri)
        } catch (e: Exception) {
            null
        }
        if (imported == null) {
            ui.toast("Ezt a képet nem sikerült beolvasni.")
            return
        }
        image = imported
        updatePicture()
    }

    // ---- Mentés és törlés ----

    private fun save() {
        val name = nameE.text.toString().trim()
        if (name.isEmpty()) {
            errorV.text = "Add meg a nevet."
            return
        }
        val db = Db.get(this)
        if (isExercise) {
            db.saveExercise(Exercise(exercise?.id ?: 0L, name, equipmentId, image, exercise?.seedKey))
        } else {
            db.saveEquipment(Equipment(equipment?.id ?: 0L, name, image, equipment?.seedKey))
        }
        finish()
    }

    private fun confirmDelete() {
        val name = if (isExercise) exercise?.name else equipment?.name
        AlertDialog.Builder(this)
            .setTitle("Törlöd: $name?")
            .setMessage(
                if (isExercise) "A régi edzéseidben és a tervedben a neve megmarad, de új tervbe már nem választható."
                else "A hozzá tartozó gyakorlatok megmaradnak.",
            )
            .setPositiveButton("Törlés") { _, _ ->
                val db = Db.get(this)
                exercise?.let { db.deleteExercise(it.id) }
                equipment?.let { db.deleteEquipment(it.id) }
                finish()
            }
            .setNegativeButton("Mégse", null)
            .show()
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_ID = "id"
        const val KIND_EXERCISE = "exercise"
        const val KIND_EQUIPMENT = "equipment"
        private const val REQ_IMAGE = 1
        private const val STATE_NAME = "name"
        private const val STATE_IMAGE = "image"
        private const val STATE_EQUIPMENT = "equipment"
    }
}
