package com.epicx.apps.dailyconsumptionformapp.issueMedicineObjects

import android.content.Context
import com.epicx.apps.dailyconsumptionformapp.GoogleSheetsClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.core.content.edit

object LastBatchStore {
    private const val PREF = "rs01_last_batch_pref"
    private const val KEY = "last_batch_json"
    private val gson = Gson()

    fun save(context: Context, list: List<GoogleSheetsClient.SubmitItem>) {
        val json = gson.toJson(list)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit {
                putString(KEY, json)
            }
    }

    fun load(context: Context): List<GoogleSheetsClient.SubmitItem> {
        val json = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<GoogleSheetsClient.SubmitItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit {
                remove(KEY)
            }
    }
}