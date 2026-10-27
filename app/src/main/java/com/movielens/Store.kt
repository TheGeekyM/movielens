package com.movielens

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Everything the app keeps on the phone: settings, IMDb cache, watchlist, history. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    var apiKey: String get() = p.getString("claude", "")!!; set(v) = p.edit().putString("claude", v).apply()
    var server: String get() = p.getString("server", "")!!; set(v) = p.edit().putString("server", v).apply()
    var minRating: Float get() = p.getFloat("minRating", 0f); set(v) = p.edit().putFloat("minRating", v).apply()
    var likes: String get() = p.getString("likes", "")!!; set(v) = p.edit().putString("likes", v).apply()
    var dislikes: String get() = p.getString("dislikes", "")!!; set(v) = p.edit().putString("dislikes", v).apply()
    var parental: Boolean get() = p.getBoolean("parental", false); set(v) = p.edit().putBoolean("parental", v).apply()

    fun list(name: String): MutableList<JSONObject> =
        JSONArray(p.getString(name, "[]")).let { a -> (0 until a.length()).map(a::getJSONObject).toMutableList() }

    fun save(name: String, l: List<JSONObject>) = p.edit().putString(name, JSONArray(l).toString()).apply()

    // ponytail: whole cache is one JSON blob in prefs; fine for a few hundred titles, move to a DB beyond that.
    private val cache by lazy { JSONObject(p.getString("cache", "{}")!!) }
    private val ttl = 3 * 24 * 3600 * 1000L

    fun cached(k: String): JSONObject? = cache.optJSONObject(k)?.takeIf { System.currentTimeMillis() - it.optLong("_at") < ttl }

    fun putCache(k: String, d: JSONObject) {
        cache.put(k, d.put("_at", System.currentTimeMillis()))
        p.edit().putString("cache", cache.toString()).apply()
    }

    /** Add to the front of a list, dropping any older entry with the same key; keep at most [cap]. */
    fun push(name: String, item: JSONObject, cap: Int) {
        val l = list(name).filter { it.optString("key") != item.optString("key") }.toMutableList()
        l.add(0, item)
        save(name, l.take(cap))
    }
}
