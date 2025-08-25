package com.epicx.apps.dailyconsumptionformapp.objects

import android.widget.EditText
import android.widget.TextView
import com.epicx.apps.dailyconsumptionformapp.AppDatabase

object MedicineStockUtils {

    fun loadMedicineStock(
        db: AppDatabase,
        vehicleName: String,
        medicineName: String,
        textOpening: TextView,
        textClosing: TextView,
        editConsumption: EditText,
        editEmergency: EditText,
        formatMedValue: (medicine: String, value: String?) -> String,
        lastLoadedOpeningBalanceSetter: (Int) -> Unit,
        lastLoadedClosingBalanceSetter: (Int) -> Unit
    ) {
        val medData = db.getAllMedicines().find { it.vehicleName == vehicleName && it.medicineName == medicineName }
        if (medData != null) {
            val opening = medData.openingBalance.toDoubleOrNull()?.toInt() ?: 0
            val closing = medData.closingBalance.toDoubleOrNull()?.toInt() ?: 0
            lastLoadedOpeningBalanceSetter(opening)
            lastLoadedClosingBalanceSetter(closing)
            textOpening.text = formatMedValue(medicineName, opening.toString())
            textClosing.text = formatMedValue(medicineName, closing.toString())
            editConsumption.setText("")
            if (vehicleName != "RS-01") editEmergency.setText("0")
        } else {
            lastLoadedOpeningBalanceSetter(0)
            lastLoadedClosingBalanceSetter(0)
            textOpening.text = ""
            textClosing.text = ""
            editConsumption.setText("")
            if (vehicleName != "RS-01") editEmergency.setText("")
        }
    }
}