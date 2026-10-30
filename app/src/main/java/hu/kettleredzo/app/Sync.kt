package hu.kettleredzo.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// Két irányú szinkron a ULC-vel (Ultimate Life Control, otthoni szerver, Edzés modul).
// Felküldjük az utolsó sikeres feltöltés óta módosult sorokat, és lehozzuk, ami a szerveren
// változott (rev sorszám alapján). A sorokat uuid azonosítja, a hivatkozások a szerver felé
// uuid-k (pl. profile_id helyett profile_uuid). Ütközésnél a későbbi updated_at nyer.
// Ha a szerver egy sort egy meglévőbe olvaszt (pl. azonos nevű és születési évű profil
// a másik eszközről), álnevet küld, és itt is átírjuk.
object Sync {
    // Helyi oszlop -> (szerveroldali oszlop, szülőtábla)
    private class Table(val name: String, val cols: List<String>, val fks: Map<String, Pair<String, String>> = emptyMap())

    // Függőségi sorrendben: előbb a szülő
    private val TABLES = listOf(
        Table("profiles", listOf("name", "birth_year", "weight_kg", "sex")),
        Table("equipment", listOf("name", "image", "seed_key")),
        Table("exercises", listOf("name", "image", "seed_key"), mapOf("equipment_id" to ("equipment_uuid" to "equipment"))),
        Table("plan_days", listOf("letter", "position"), mapOf("profile_id" to ("profile_uuid" to "profiles"))),
        Table(
            "plan_items", listOf("position", "rest_sec", "sets"),
            mapOf("day_id" to ("day_uuid" to "plan_days"), "exercise_id" to ("exercise_uuid" to "exercises")),
        ),
        Table(
            "workouts",
            listOf(
                "started_at", "moving_ms", "distance_km", "avg_speed", "max_speed", "kcal", "level", "avg_hr", "max_hr",
                "mode", "goal", "program", "completed", "sport", "title", "elev_gain", "hr_samples",
            ),
            mapOf("profile_id" to ("profile_uuid" to "profiles")),
        ),
        Table(
            "workout_sets",
            listOf("item_pos", "set_pos", "planned_reps", "planned_kg", "reps", "weight_kg", "rest_sec", "done"),
            mapOf(
                "workout_id" to ("workout_uuid" to "workouts"),
                "plan_item_id" to ("plan_item_uuid" to "plan_items"),
                "exercise_id" to ("exercise_uuid" to "exercises"),
            ),
        ),
        Table("tracks", listOf("points"), mapOf("workout_id" to ("workout_uuid" to "workouts"))),
        Table("goals", listOf("sport", "metric", "period", "target"), mapOf("profile_id" to ("profile_uuid" to "profiles"))),
    )

    // A folyamatban lévő edzés (és sorozatai, útvonala) csak a befejezés után megy fel.
    private val PENDING_FILTER = mapOf(
        "workouts" to "in_progress = 0",
        "workout_sets" to "workout_id IN (SELECT id FROM workouts WHERE in_progress = 0)",
        "tracks" to "workout_id IN (SELECT id FROM workouts WHERE in_progress = 0)",
    )

    class Result(val ok: Boolean, val pushed: Int, val pulled: Int, val message: String)

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun configured(c: Context) = Prefs.syncToken(c).isNotBlank() && Prefs.syncUrl(c).isNotBlank()

    // Háttérben fut; ha nincs beállítva, vagy épp fut egy, nem csinál semmit. A visszahívás a fő szálon jön.
    fun runAsync(c: Context, onDone: ((Result) -> Unit)? = null) {
        if (!configured(c)) return
        if (!running.compareAndSet(false, true)) return
        val app = c.applicationContext
        executor.execute {
            val r = try {
                run(app)
            } catch (e: RevokedException) {
                // A kulcsot visszavonták a ULC-ben: kijelentkezünk, a Beállításokban újra be lehet lépni.
                logout(app)
                Result(false, 0, 0, "a kulcsot visszavonták, jelentkezz be újra")
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Szinkron: a ULC nem érhető el", e)
                Result(false, 0, 0, "a ULC nem érhető el (csak otthoni hálózaton megy)")
            } catch (e: Exception) {
                Log.w(TAG, "Szinkron hiba", e)
                Result(false, 0, 0, e.message ?: e.javaClass.simpleName)
            } finally {
                running.set(false)
            }
            val time = SimpleDateFormat("yyyy. MM. dd. HH:mm", HU).format(Date())
            Prefs.setSyncStatus(
                app,
                if (r.ok) "$time – rendben (${r.pushed} fel, ${r.pulled} le)" else "$time – nem sikerült: ${r.message}",
            )
            main.post { onDone?.invoke(r) }
        }
    }

    private fun run(c: Context): Result {
        val db = Db.get(c).writableDatabase
        val startedAt = System.currentTimeMillis()
        val since = Prefs.syncRev(c)
        val pushedAt = Prefs.syncPushedAt(c)

        // ---- Felküldés ----
        val changes = JSONObject()
        val images = JSONObject()
        var pushed = 0
        val uuidCache = HashMap<String, HashMap<Long, String?>>()
        for (t in TABLES) {
            val where = listOfNotNull("updated_at > ?", PENDING_FILTER[t.name]).joinToString(" AND ")
            val rows = JSONArray()
            db.rawQuery("SELECT * FROM ${t.name} WHERE $where", arrayOf(pushedAt.toString())).use { cur ->
                while (cur.moveToNext()) {
                    val o = JSONObject()
                    o.put("uuid", cur.getString(cur.getColumnIndexOrThrow("uuid")))
                    o.put("updated_at", cur.getLong(cur.getColumnIndexOrThrow("updated_at")))
                    o.put("deleted", cur.getInt(cur.getColumnIndexOrThrow("deleted")))
                    for (col in t.cols) o.put(col, cur.value(col))
                    for ((local, fk) in t.fks) {
                        val idx = cur.getColumnIndexOrThrow(local)
                        val id = if (cur.isNull(idx)) null else cur.getLong(idx)
                        o.put(fk.first, id?.let { uuidOf(db, fk.second, it, uuidCache) } ?: JSONObject.NULL)
                    }
                    (o.opt("image") as? String)?.let { addImage(c, it, images) }
                    rows.put(o)
                }
            }
            if (rows.length() > 0) changes.put(t.name, rows)
            pushed += rows.length()
        }

        val body = JSONObject().put("since", since).put("changes", changes).put("images", images)
        // A személyes eszköz megmondja, kié: csak annak a profilnak az adatai jönnek le rá.
        if (Prefs.personal(c) && Prefs.boundProfileUuid(c).isNotBlank()) body.put("profile_uuid", Prefs.boundProfileUuid(c))
        val response = JSONObject(post(c, "/api/edzes/sync", body.toString()))

        // ---- Álnevek (összefésült sorok), majd a letöltött változások ----
        var pulled = 0
        db.beginTransaction()
        try {
            response.optJSONObject("aliases")?.let { aliases ->
                applyAliases(c, db, aliases)
                // Ha a saját profilunkat a szerver egy meglévőbe olvasztotta, mostantól azt kell kérnünk.
                aliases.optJSONObject("profiles")?.optString(Prefs.boundProfileUuid(c))?.takeIf { it.isNotEmpty() }
                    ?.let { Prefs.setBoundProfileUuid(c, it) }
            }
            val incoming = response.optJSONObject("changes") ?: JSONObject()
            val idCache = HashMap<String, HashMap<String, Long?>>()
            for (t in TABLES) {
                val rows = incoming.optJSONArray(t.name) ?: continue
                for (i in 0 until rows.length()) {
                    if (applyRow(db, t, rows.getJSONObject(i), idCache)) pulled++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        downloadMissingImages(c, db)
        Prefs.setSyncDone(c, response.optLong("rev", since), startedAt)
        return Result(true, pushed, pulled, "")
    }

    private fun Cursor.value(col: String): Any {
        val i = getColumnIndexOrThrow(col)
        return when (getType(i)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            Cursor.FIELD_TYPE_INTEGER -> getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
            else -> getString(i)
        }
    }

    private fun uuidOf(db: SQLiteDatabase, table: String, id: Long, cache: HashMap<String, HashMap<Long, String?>>): String? =
        cache.getOrPut(table) { HashMap() }.getOrPut(id) {
            db.rawQuery("SELECT uuid FROM $table WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
        }

    private fun idOf(db: SQLiteDatabase, table: String, uuid: String, cache: HashMap<String, HashMap<String, Long?>>): Long? =
        cache.getOrPut(table) { HashMap() }.getOrPut(uuid) {
            db.rawQuery("SELECT id FROM $table WHERE uuid = ?", arrayOf(uuid)).use { if (it.moveToFirst()) it.getLong(0) else null }
        }

    // Egy letöltött sor beírása: ha nálunk újabb, marad a miénk. Hiányzó szülőnél kihagyjuk.
    private fun applyRow(db: SQLiteDatabase, t: Table, o: JSONObject, cache: HashMap<String, HashMap<String, Long?>>): Boolean {
        val uuid = o.getString("uuid")
        val updatedAt = o.getLong("updated_at")
        val deleted = o.optInt("deleted", 0)
        val v = ContentValues()
        for (col in t.cols) v.putJson(col, o.opt(col))
        for ((local, fk) in t.fks) {
            val ref = o.opt(fk.first)
            if (ref == null || ref == JSONObject.NULL) {
                v.putNull(local)
            } else {
                val id = idOf(db, fk.second, ref as String, cache) ?: return false
                v.put(local, id)
            }
        }
        v.put("updated_at", updatedAt)
        v.put("deleted", deleted)
        val local = db.rawQuery("SELECT id, updated_at FROM ${t.name} WHERE uuid = ?", arrayOf(uuid)).use {
            if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null
        }
        return when {
            local == null -> {
                if (deleted == 1) return false
                v.put("uuid", uuid)
                val id = db.insertOrThrow(t.name, null, v)
                cache.getOrPut(t.name) { HashMap() }[uuid] = id
                true
            }
            updatedAt > local.second -> {
                db.update(t.name, v, "id = ?", arrayOf(local.first.toString()))
                true
            }
            else -> false
        }
    }

    private fun ContentValues.putJson(col: String, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> putNull(col)
            is Int -> put(col, value)
            is Long -> put(col, value)
            is Double -> put(col, value)
            is Boolean -> put(col, if (value) 1 else 0)
            else -> put(col, value.toString())
        }
    }

    // Melyik tábla melyik oszlopa hivatkozik az adott táblára (összefésüléskor ezeket írjuk át)
    private val CHILDREN = mapOf(
        "profiles" to listOf("workouts" to "profile_id", "plan_days" to "profile_id"),
        "equipment" to listOf("exercises" to "equipment_id"),
        "exercises" to listOf("plan_items" to "exercise_id", "workout_sets" to "exercise_id"),
        "plan_days" to listOf("plan_items" to "day_id"),
        "plan_items" to listOf("workout_sets" to "plan_item_id"),
        "workouts" to listOf("workout_sets" to "workout_id", "tracks" to "workout_id"),
    )

    // Régi uuid -> a szerveren már meglévő. Ha az utóbbi nálunk még nincs, csak átnevezzük;
    // ha már megvan, a hivatkozásokat átirányítjuk rá, és a régi sort töröljük.
    private fun applyAliases(c: Context, db: SQLiteDatabase, aliases: JSONObject) {
        for (t in TABLES) {
            val map = aliases.optJSONObject(t.name) ?: continue
            for (old in map.keys()) {
                val new = map.getString(old)
                val oldId = db.rawQuery("SELECT id FROM ${t.name} WHERE uuid = ?", arrayOf(old)).use { if (it.moveToFirst()) it.getLong(0) else null } ?: continue
                val newId = db.rawQuery("SELECT id FROM ${t.name} WHERE uuid = ?", arrayOf(new)).use { if (it.moveToFirst()) it.getLong(0) else null }
                if (newId == null) {
                    db.execSQL("UPDATE ${t.name} SET uuid = ? WHERE id = ?", arrayOf(new, oldId))
                } else {
                    for ((child, col) in CHILDREN[t.name].orEmpty()) {
                        db.execSQL("UPDATE $child SET $col = ? WHERE $col = ?", arrayOf(newId, oldId))
                    }
                    db.execSQL("DELETE FROM ${t.name} WHERE id = ?", arrayOf(oldId))
                    if (t.name == "profiles" && Prefs.currentProfileId(c) == oldId) Prefs.setCurrentProfileId(c, newId)
                }
            }
        }
    }

    // ---- Bejelentkezés ----

    // Egyszeri bejelentkezés a ULC-felhasználóval: a ULC eszközkulcsot ad, a jelszót nem tároljuk.
    // A visszahívás a fő szálon jön: null, ha sikerült, különben a hibaüzenet.
    fun login(c: Context, url: String, user: String, password: String, personal: Boolean, onDone: (String?) -> Unit) {
        val app = c.applicationContext
        executor.execute {
            val error = try {
                Prefs.setSyncUrl(app, url)
                val body = JSONObject().put("username", user).put("password", password).put("device", android.os.Build.MODEL)
                    .put("mode", if (personal) "personal" else "shared")
                val conn = (URL(Prefs.syncUrl(app) + "/api/edzes/login").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 15000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                try {
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    when (conn.responseCode) {
                        200 -> {
                            val o = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                            Prefs.setSyncToken(app, o.getString("token"))
                            Prefs.setSyncAccount(app, o.optString("device"))
                            Prefs.setPersonal(app, personal)
                            Prefs.setBoundProfileUuid(app, "")
                            Prefs.setSetupProfiles(app, (o.optJSONArray("profiles") ?: JSONArray()).toString())
                            null
                        }
                        401 -> "Hibás felhasználónév vagy jelszó."
                        else -> "A ULC hibát jelzett (${conn.responseCode})."
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: java.io.IOException) {
                "A ULC nem érhető el. Otthoni wifin vagy? Jó a cím?"
            }
            main.post { onDone(error) }
        }
    }

    // Kijelentkezés (pl. visszavont kulcsnál): a következő indításkor újra a beállító képernyő jön.
    fun logout(c: Context) {
        Prefs.setSyncToken(c, "")
        Prefs.setSyncAccount(c, "")
        Prefs.setBoundProfileUuid(c, "")
    }

    // Személyes eszköz: a hozzárendelt profil helyi azonosítója (ha már megvan nálunk)
    fun boundProfileId(c: Context): Long? {
        val uuid = Prefs.boundProfileUuid(c).ifBlank { return null }
        return Db.get(c).readableDatabase.rawQuery("SELECT id FROM profiles WHERE uuid = ? AND deleted = 0", arrayOf(uuid))
            .use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    fun profileUuid(c: Context, id: Long): String? =
        Db.get(c).readableDatabase.rawQuery("SELECT uuid FROM profiles WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    // ---- Saját fotók (file:kepek/<név>) ----

    private fun addImage(c: Context, image: String, images: JSONObject) {
        if (!image.startsWith("file:")) return
        val file = File(c.filesDir, image.removePrefix("file:"))
        if (!file.isFile || images.has(file.name)) return
        images.put(file.name, Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
    }

    private fun downloadMissingImages(c: Context, db: SQLiteDatabase) {
        for (table in listOf("equipment", "exercises")) {
            val names = db.rawQuery("SELECT image FROM $table WHERE image LIKE 'file:%' AND deleted = 0", null).use { cur ->
                generateSequence { if (cur.moveToNext()) cur.getString(0) else null }.toList()
            }
            for (image in names) {
                val file = File(c.filesDir, image.removePrefix("file:"))
                if (file.exists()) continue
                try {
                    val bytes = get(c, "/api/edzes/kep/" + file.name) ?: continue
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                } catch (e: Exception) {
                    Log.w(TAG, "Kép letöltése nem sikerült: ${file.name}", e)
                }
            }
        }
    }

    // ---- HTTP ----

    private fun open(c: Context, path: String) = (URL(Prefs.syncUrl(c) + path).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5000
        readTimeout = 60000
        setRequestProperty("Authorization", "Bearer " + Prefs.syncToken(c))
    }

    private fun post(c: Context, path: String, json: String): String {
        val conn = open(c, path)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 401) throw RevokedException()
            if (code != 200) throw IllegalStateException("a szerver hibát jelzett ($code)")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun get(c: Context, path: String): ByteArray? {
        val conn = open(c, path)
        try {
            return if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() } else null
        } finally {
            conn.disconnect()
        }
    }

    private class RevokedException : Exception()

    private const val TAG = "Sync"
}
