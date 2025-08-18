package com.epicx.apps.dailyconsumptionformapp.objects

import android.content.Context
import com.epicx.apps.dailyconsumptionformapp.AppDatabase
import java.text.SimpleDateFormat
import java.util.*

object RollOverUtils {
    fun rollOverBalancesIfDateChanged(context: Context, db: AppDatabase) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val lastDate = prefs.getString("last_rollover_date", null)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (lastDate != today) {
            val allMeds = db.getAllMedicines()
            for (item in allMeds) {
                db.addOrUpdateMedicine(
                    item.vehicleName,
                    item.medicineName,
                    item.closingBalance, // opening = last closing
                    "0", // consumption
                    "0", // emergency
                    item.closingBalance // closing = opening (NOT ZERO)
                )
            }
            prefs.edit().putString("last_rollover_date", today).apply()
        }
    }
}