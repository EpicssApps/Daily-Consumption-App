package com.epicx.apps.dailyconsumptionformapp.formActivityObjects

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

object StoreIssuedHelper {

    fun showDialog(
        activity: Activity,
        defaultVehicle: String,
        medicineEdit: EditText,
        textStoreIssued: TextView,
        updateStoreIssued: (vehicle: String, medicine: String, value: String) -> Unit
    ) {
        if (defaultVehicle != "RS-01") return

        val medName = medicineEdit.text.toString().trim()
        if (medName.isBlank()) {
            Toast.makeText(activity, "Pehle medicine select karein!", Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Enter issued value"
            val current = textStoreIssued.text?.toString().orEmpty()
            setText(if (current == "0" || current.isBlank()) "" else current)
        }

        AlertDialog.Builder(activity)
            .setTitle("Store Issued")
            .setMessage("Set issued value for $medName:")
            .setView(input)
            .setPositiveButton("Save") { dialog, _ ->
                val value = input.text.toString().toIntOrNull() ?: 0
                updateStoreIssued(defaultVehicle, medName, value.toString())
                textStoreIssued.text = value.toString()
                Toast.makeText(activity, "Store Issued value updated!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}