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
        onClosingRecalculated: (Int) -> Unit
    ) {
        val vehicleName = defaultVehicle
        val medicineName = medicineEdit.text.toString()
        val opening = textOpening.text.toString()
        val inputConsumption = editConsumption.text.toString()
        val inputEmergency = editEmergency.text.toString()
        val uiClosing = textClosing.text.toString()

        if (inputConsumption.isBlank() || inputConsumption == "0") {
            AlertDialog.Builder(activity)
                .setTitle("Missing Consumption")
                .setMessage("Consumption ki value enter karein!")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val consumption = inputConsumption.toIntOrNull() ?: 0
        val emergency = inputEmergency.toIntOrNull() ?: 0

        val dbRecord = getAllMedicines().find {
            it.vehicleName == vehicleName && it.medicineName == medicineName
        }
        val currentDbClosing = dbRecord?.closingBalance?.toDoubleOrNull()?.toInt() ?: 0
        if (currentDbClosing == 0 && consumption > 0) {
            Toast.makeText(
                activity,
                "Bhai, pehle stock issue karwao, phir consumption kar sakte ho.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // ADDED: Only consumption limit check (emergency ignore)
        if (consumption > currentDbClosing) {
            AlertDialog.Builder(activity)
                .setTitle("Insufficient Balance")
                .setMessage(
                    "Current Closing: $currentDbClosing\n" +
                            "Entered Consumption: $consumption\n\n" +
                            "Itna stock available nahi. Pehle balance barhaen ya consumption kam karein."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        // END ADDED

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

        val numericInvalid =
            listOf(opening, uiClosing).any { it.toDoubleOrNull() == null } ||
                    inputConsumption.toIntOrNull() == null ||
                    (defaultVehicle != "RS-01" && inputEmergency.toIntOrNull() == null)

        if (numericInvalid) {
            errorText.text = "Numeric fields must be numbers"
            return
        }

        if (defaultVehicle != "RS-01") {
            if (consumption == 0 || emergency == 0) {
                val msg = when {
                    consumption > 0 && emergency == 0 -> "Bhai, jab consumption likho to total emergency bhi likhna zaroori hai!"
                    emergency > 0 && consumption == 0 -> "Bhai, jab emergency likho to total consumption bhi likhna zaroori hai!"
                    emergency == 0 && consumption == 0 -> "Bhai, emergency & consumption likhna zaroori hai!"
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

        val oldConsumption = dbRecord?.consumption?.toDoubleOrNull()?.toInt() ?: 0
        val oldEmergency = dbRecord?.totalEmergency?.toIntOrNull() ?: 0
        val sumConsumption = oldConsumption + consumption
        val sumEmergency = oldEmergency + emergency

        val recalculatedClosing = (currentDbClosing - consumption).coerceAtLeast(0)
        val closingToSave = formatMedValue(medicineName, recalculatedClosing.toString())

        addOrUpdateMedicine(
            vehicleName,
            medicineName,
            opening,
            sumConsumption.toString(),
            sumEmergency.toString(),
            closingToSave
        )

        onClosingRecalculated(recalculatedClosing)
        textClosing.text = closingToSave

        AlertDialog.Builder(activity)
            .setTitle("Saved")
            .setMessage("Saved locally! Upload when ready.")
            .setPositiveButton("OK", null)
            .show()

        editConsumption.setText("")
        editEmergency.setText("")
    }
}