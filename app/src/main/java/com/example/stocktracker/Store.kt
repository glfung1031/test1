package com.example.stocktracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Item(
    val id: String = UUID.randomUUID().toString(),
    val name: String, val url: String, val maxPrice: Double,
    val enabled: Boolean = true,
    val lastPrice: Double? = null, val inStock: Boolean? = null,
    val lastChecked: Long = 0, val note: String = "", val alerted: Boolean = false
)

data class Settings(
    val intervalSec: Int = 60, val jitterSec: Int = 0, val emailOn: Boolean = false,
    val smtpUser: String = "", val smtpPass: String = "", val emailTo: String = ""
)

object Store {
    private fun sp(c: Context) = c.getSharedPreferences("tracker", Context.MODE_PRIVATE)

    @Synchronized fun items(c: Context): List<Item> {
        val a = JSONArray(sp(c).getString("items", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Item(o.getString("id"), o.getString("name"), o.getString("url"), o.getDouble("max"),
                o.getBoolean("en"),
                if (o.isNull("p")) null else o.getDouble("p"),
                if (o.isNull("s")) null else o.getBoolean("s"),
                o.getLong("t"), o.optString("n"), o.optBoolean("a"))
        }
    }

    @Synchronized fun mutate(c: Context, f: (List<Item>) -> List<Item>) {
        val a = JSONArray()
        f(items(c)).forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)
                .put("max", it.maxPrice).put("en", it.enabled)
                .put("p", it.lastPrice ?: JSONObject.NULL).put("s", it.inStock ?: JSONObject.NULL)
                .put("t", it.lastChecked).put("n", it.note).put("a", it.alerted))
        }
        sp(c).edit().putString("items", a.toString()).apply()
    }

    fun settings(c: Context): Settings {
        val s = sp(c)
        val sec = if (s.contains("iv_sec")) s.getInt("iv_sec", 60) else s.getInt("iv", 15) * 60
        return Settings(sec, s.getInt("jt", 0), s.getBoolean("eo", false),
            s.getString("su", "")!!, s.getString("sp", "")!!, s.getString("to", "")!!)
    }

    fun saveSettings(c: Context, s: Settings) {
        sp(c).edit().putInt("iv_sec", s.intervalSec).putInt("jt", s.jitterSec).putBoolean("eo", s.emailOn)
            .putString("su", s.smtpUser).putString("sp", s.smtpPass).putString("to", s.emailTo).apply()
    }
}
