package com.epicx.apps.dailyconsumptionformapp.objects

import android.widget.EditText
import android.widget.TextView
import com.epicx.apps.dailyconsumptionformapp.AppDatabase

object MedicineStockUtils {

    /**
     * Loads medicine stock values and updates provided UI fields.
     *
     * @param db The database instance to fetch medicine data.
     * @param vehicleName The vehicle name for filtering medicine.
     * @param medicineName The medicine name to load.
     * @param textOpening TextView for displaying opening balance.
     * @param textClosing TextView for displaying closing balance.
     * @param editConsumption EditText for consumption (reset).
     * @param editEmergency EditText for emergency (reset, or set "0" for non-RS-01).
     * @param formatMedValue Function for formatting medicine value (e.g. 2 decimal for some).
     * @param lastLoadedOpeningBalanceSetter Function to update lastLoadedOpeningBalance in the Activity.
     * @param lastLoadedClosingBalanceSetter Function to update lastLoadedClosingBalance in the Activity.
     */
    fun loadMedicineStock(
        db: AppDatabase,
        vehicleName: String,
        medicineName: String,
        textOpening: TextView,
        textClosing: TextView,
        editConsumption: EditText,
        editEmergency: EditText,
        formatMedValue: (medicine: String, value: String?) -> String,
        lastLoadedOpeningBalanceSetter: (Float) -> Unit,
        lastLoadedClosingBalanceSetter: (Float) -> Unit
    ) {
        val medData = db.getAllMedicines().find { it.vehicleName == vehicleName && it.medicineName == medicineName }
        if (medData != null) {
            val opening = medData.openingBalance.toFloatOrNull() ?: 0f
            val closing = medData.closingBalance.toFloatOrNull() ?: 0f
            lastLoadedOpeningBalanceSetter(opening)
            lastLoadedClosingBalanceSetter(closing)
            textOpening.text = formatMedValue(medicineName, medData.openingBalance)
            textClosing.text = formatMedValue(medicineName, medData.closingBalance)
            editConsumption.setText("")
            if (vehicleName != "RS-01") editEmergency.setText("0")
        } else {
            lastLoadedOpeningBalanceSetter(0f)
            lastLoadedClosingBalanceSetter(0f)
            textOpening.text = ""
            textClosing.text = ""
            editConsumption.setText("")
            if (vehicleName != "RS-01") editEmergency.setText("")
        }
    }
}