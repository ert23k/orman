package com.example.orman

import android.app.AppOpsManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class Tree(val t: Long, val sp: String, val min: Int, val ok: Boolean, val tag: String)
data class Sp(val id: String, val name: String, val price: Int)
data class Result(val ok: Boolean, val min: Int, val sp: String, val reason: String)

val SPECIES = listOf(
    Sp("oak", "Meşe", 0), Sp("pine", "Çam", 60), Sp("sakura", "Kiraz çiçeği", 120),
    Sp("maple", "Akçaağaç", 180), Sp("cactus", "Kaktüs", 250)
)

/** Çalışan oturumun anlık durumu (servis yazar, arayüz okur). */
object Session {
    var active by mutableStateOf(false)
    var mode by mutableStateOf("down")
    var remaining by mutableLongStateOf(0L) // down: kalan sn, up: geçen sn
    var progress by mutableFloatStateOf(0f)
    var sp by mutableStateOf("oak")
    var tag by mutableStateOf("")
    var away by mutableIntStateOf(0)
    var result by mutableStateOf<Result?>(null)
}

object Store {
    private lateinit var prefs: SharedPreferences
    var coins by mutableIntStateOf(0)
    var sel by mutableStateOf("oak")
    var unlocked by mutableStateOf(setOf("oak"))
    var allowed by mutableStateOf(setOf<String>())
    var strict by mutableStateOf(true)
    var trees by mutableStateOf(listOf<Tree>())
    var usageGranted by mutableStateOf(false)
    var tags by mutableStateOf(listOf("Çalışma", "Okuma", "Spor", "Proje"))
    var tag by mutableStateOf("Çalışma")
    var mode by mutableStateOf("down")
    var dur by mutableIntStateOf(25)
    var sound by mutableStateOf("off")
    var goal by mutableIntStateOf(120)

    // Devam eden oturum (uygulama kapansa da kaybolmasın diye diske yazılır)
    var aStart = 0L
    var aDur = 25
    var aMode = "down"
    var aSp = "oak"
    var aTag = ""
    var aStrict = true

    fun init(c: Context) {
        if (::prefs.isInitialized) return
        prefs = c.applicationContext.getSharedPreferences("orman", Context.MODE_PRIVATE)
        loadAll()
    }

    private fun loadAll() {
        coins = prefs.getInt("coins", 0)
        sel = prefs.getString("sel", "oak") ?: "oak"
        unlocked = prefs.getStringSet("unlocked", setOf("oak"))?.toSet() ?: setOf("oak")
        allowed = prefs.getStringSet("allowed", emptySet())?.toSet() ?: emptySet()
        strict = prefs.getBoolean("strict", true)
        tags = try {
            val a = JSONArray(prefs.getString("tags", null) ?: "[\"Çalışma\",\"Okuma\",\"Spor\",\"Proje\"]")
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) { listOf("Çalışma") }
        tag = prefs.getString("tag", "Çalışma") ?: "Çalışma"
        mode = prefs.getString("mode", "down") ?: "down"
        dur = prefs.getInt("dur", 25).coerceIn(1, 180)
        sound = prefs.getString("sound", "off") ?: "off"
        goal = prefs.getInt("goal", 120).coerceIn(15, 360)
        aStart = prefs.getLong("aStart", 0L)
        aDur = prefs.getInt("aDur", 25)
        aMode = prefs.getString("aMode", "down") ?: "down"
        aSp = prefs.getString("aSp", "oak") ?: "oak"
        aTag = prefs.getString("aTag", "") ?: ""
        aStrict = prefs.getBoolean("aStrict", true)
        trees = try {
            val a = JSONArray(prefs.getString("trees", "[]"))
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Tree(o.getLong("t"), o.getString("sp"), o.getInt("min"), o.getBoolean("ok"), o.optString("tag", ""))
            }
        } catch (e: Exception) { emptyList() }
    }

    fun save() {
        val a = JSONArray()
        trees.forEach {
            a.put(JSONObject().put("t", it.t).put("sp", it.sp).put("min", it.min).put("ok", it.ok).put("tag", it.tag))
        }
        val tg = JSONArray()
        tags.forEach { tg.put(it) }
        prefs.edit()
            .putInt("coins", coins).putString("sel", sel)
            .putStringSet("unlocked", HashSet(unlocked))
            .putStringSet("allowed", HashSet(allowed))
            .putBoolean("strict", strict).putString("trees", a.toString())
            .putString("tags", tg.toString()).putString("tag", tag)
            .putString("mode", mode).putInt("dur", dur).putString("sound", sound).putInt("goal", goal)
            .putLong("aStart", aStart).putInt("aDur", aDur).putString("aMode", aMode)
            .putString("aSp", aSp).putString("aTag", aTag).putBoolean("aStrict", aStrict)
            .apply()
    }

    fun resetAll() {
        prefs.edit().clear().apply()
        loadAll()
    }
}

fun hasUsageAccess(c: Context): Boolean {
    val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    @Suppress("DEPRECATION")
    val m = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
    return if (m == AppOpsManager.MODE_DEFAULT)
        c.checkCallingOrSelfPermission("android.permission.PACKAGE_USAGE_STATS") == PackageManager.PERMISSION_GRANTED
    else m == AppOpsManager.MODE_ALLOWED
}
