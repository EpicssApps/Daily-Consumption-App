package com.epicx.apps.dailyconsumptionformapp.tempStorage

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

object TempRs01DailyStore {

    private const val PREF_NAME = "rs01_temp_store"
    private const val KEY_PREFIX = "rs01_day_"   // final key: rs01_day_2025-08-25

    private fun keyForDate(date: String) = KEY_PREFIX + date

    fun todayDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /**
     * Add (increment) consumption for given date+medicine.
     */
    fun addConsumption(context: Context, date: String, medicine: String, add: Int) {
        if (add <= 0) return
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val key = keyForDate(date)
        val currentJson = prefs.getString(key, "{}") ?: "{}"
        val obj = JSONObject(currentJson)
        val oldVal = obj.optInt(medicine, 0)
        obj.put(medicine, oldVal + add)
        prefs.edit().putString(key, obj.toString()).apply()
    }

    /**
     * Get map for date (medicine -> consumption).
     */
    fun getMap(context: Context, date: String): Map<String, Int> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val key = keyForDate(date)
        val json = prefs.getString(key, null) ?: return emptyMap()
        val obj = JSONObject(json)
        val result = mutableMapOf<String, Int>()
        val names = obj.keys()
        while (names.hasNext()) {
            val med = names.next()
            result[med] = obj.optInt(med, 0)
        }
        return result
    }

    /**
     * Clear stored data for that date after monthly upload success.
     */
    fun clearDate(context: Context, date: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(keyForDate(date)).apply()
    }

    /**
     * (Optional) Clear all (if ever needed).
     */
    fun clearAll(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }.forEach { remove(it) }
        }.apply()
    }
}