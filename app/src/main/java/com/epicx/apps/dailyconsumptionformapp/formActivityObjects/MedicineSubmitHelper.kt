package com.epicx.apps.dailyconsumptionformapp.formActivityObjects

import android.app.Activity
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

object MedicineSubmitHelper {

    data class DbMedRecord(
        val vehicleName: String,
        val medicineName: String,
        val closingBalance: String?,
        val consumption: String?,
        val totalEmergency: String?
    )

    fun handleSubmit(
        activity: Activity,
        defaultVehicle: String,
        medicineEdit: EditText,
        textOpening: TextView,
        editConsumption: EditText,
        editEmergency: EditText,
        textClosing: TextView,
        errorText: TextView,
        getAllMedicines: () -> List<DbMedRecord>,
        addOrUpdateMedicine: (
            vehicle: String,
            medicine: String,
            opening: String,
            consumption: String,
            totalEmergency: String,
            closing: String
        ) -> Unit,
        formatMedValue: (medicineName: String, value: String) -> String,
        onClosingRecalculated: (Float) -> Unit
    ) {
        val vehicleName = defaultVehicle
        val medicineName = medicineEdit.text.toString()
        val opening = textOpening.text.toString()
        val inputConsumption = editConsumption.text.toString()
        val inputEmergency = editEmergency.text.toString()
        val uiClosing = textClosing.text.toString()

        // 1) Consumption must not be blank or zero
        if (inputConsumption.isBlank() || inputConsumption == "0" || inputConsumption == "0.0") {
            AlertDialog.Builder(activity)
                .setTitle("Missing Consumption")
                .setMessage("Consumption ki value enter karein!")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        // Parse user input
        val consumption = inputConsumption.toFloatOrNull() ?: 0f
        val emergency = inputEmergency.toIntOrNull() ?: 0

        // 2) Stock zero check using DB closing (before save)
        val dbRecord = getAllMedicines().find {
            it.vehicleName == vehicleName && it.medicineName == medicineName
        }
        val currentDbClosing = dbRecord?.closingBalance?.toFloatOrNull() ?: 0f
        if (currentDbClosing == 0f && consumption > 0f) {
            Toast.makeText(
                activity,
                "Bhai, pehle stock issue karwao, phir consumption kar sakte ho.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // 3) Required fields
        if (vehicleName.isBlank() || medicineName.isBlank()) {
            errorText.text = "Vehicle & medicine required"
            return
        }
        if (defaultVehicle != "RS-01") {
            if (inputConsumption.isBlank() || inputEmergency.isBlank()) {
                errorText.text = "consumption & emergency fields are required"
                return
            }
        }

        // 4) Numeric validation
        val numericInvalid =
            listOf(opening, uiClosing).any { it.toFloatOrNull() == null } ||
                    inputConsumption.toFloatOrNull() == null ||
                    (defaultVehicle != "RS-01" && inputEmergency.toIntOrNull() == null)

        if (numericInvalid) {
            errorText.text = "Numeric fields must be numbers"
            return
        }

        // 5) Business Rule for non RS-01
        if (defaultVehicle != "RS-01") {
            if (consumption == 0f || emergency == 0) {
                val msg = when {
                    consumption > 0f && emergency == 0 -> "Bhai, jab consumption likho to total emergency bhi likhna zaroori hai!"
                    emergency > 0 && consumption == 0f -> "Bhai, jab emergency likho to total consumption bhi likhna zaroori hai!"
                    emergency == 0 && consumption == 0f -> "Bhai, emergency & consumption likhna zaroori hai!"
                    else -> "Fields required!"
                }
                AlertDialog.Builder(activity)
                    .setTitle("Validation Error")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
        }

        errorText.text = ""

        // Old data sums
        val oldConsumption = dbRecord?.consumption?.toFloatOrNull() ?: 0f
        val oldEmergency = dbRecord?.totalEmergency?.toIntOrNull() ?: 0
        val sumConsumption = oldConsumption + consumption
        val sumEmergency = oldEmergency + emergency

        // Recalculate closing from DB closing (not going below 0)
        val recalculatedClosing = (currentDbClosing - consumption).coerceAtLeast(0f)
        val closingToSave = formatMedValue(medicineName, recalculatedClosing.toString())

        // Save to DB
        addOrUpdateMedicine(
            vehicleName,
            medicineName,
            opening,
            sumConsumption.toString(),
            sumEmergency.toString(),
            closingToSave
        )

        // Update state
        onClosingRecalculated(recalculatedClosing)
        textClosing.text = closingToSave

        AlertDialog.Builder(activity)
            .setTitle("Saved")
            .setMessage("Saved locally! Upload when ready.")
            .setPositiveButton("OK", null)
            .show()

        // Clear inputs
        editConsumption.setText("")
        editEmergency.setText("")
    }
}