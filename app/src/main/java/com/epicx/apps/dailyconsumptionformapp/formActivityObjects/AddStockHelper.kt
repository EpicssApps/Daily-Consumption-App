package com.epicx.apps.dailyconsumptionformapp.formActivityObjects

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.epicx.apps.dailyconsumptionformapp.AppDatabase

object AddStockHelper {

    fun showAddStockDialog(
        activity: Activity,
        db: AppDatabase,
        defaultVehicle: String,
        medicineEdit: EditText,
        textOpening: TextView,
        textClosing: TextView,
        editConsumption: EditText,
        editEmergency: EditText,
        formatMedValue: (medicineName: String, value: String?) -> String,
        getLastLoadedOpening: () -> Float,
        setLastLoadedOpening: (Float) -> Unit,
        getLastLoadedClosing: () -> Float,
        setLastLoadedClosing: (Float) -> Unit
    ) {
        val medicineName = medicineEdit.text.toString()
        if (medicineName.isBlank()) {
            Toast.makeText(activity, "Select medicine first!", Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Enter quantity"
        }

        AlertDialog.Builder(activity)
            .setTitle("Add New Stock")
            .setMessage("How much new stock for $medicineName?")
            .setView(input)
            .setPositiveButton("Add") { dialog, _ ->
                val addValue = input.text.toString().toFloatOrNull() ?: 0f
                if (addValue > 0f) {
                    val newClosing = getLastLoadedClosing() + addValue
                    val newOpening = getLastLoadedOpening() + addValue

                    setLastLoadedClosing(newClosing)
                    setLastLoadedOpening(newOpening)

                    textClosing.text = formatMedValue(medicineName, newClosing.toString())
                    textOpening.text = formatMedValue(medicineName, newOpening.toString())

                    db.addOrUpdateMedicine(
                        defaultVehicle,
                        medicineName,
                        textOpening.text.toString(),
                        editConsumption.text.toString(),
                        editEmergency.text.toString(),
                        textClosing.text.toString()
                    )

                    Toast.makeText(activity, "Stock added successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Quantity must be greater than 0.", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}