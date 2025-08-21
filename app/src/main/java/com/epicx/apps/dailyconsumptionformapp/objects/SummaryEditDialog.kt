package com.epicx.apps.dailyconsumptionformapp.objects

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import com.epicx.apps.dailyconsumptionformapp.FormData
import com.epicx.apps.dailyconsumptionformapp.R
import com.google.android.material.textfield.TextInputEditText

object SummaryEditDialog {
    // Sirf Consumption aur Emergency editable
    fun show(context: Context, item: FormData, onSave: (FormData) -> Unit) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_summary_item, null)

        val tvVehicle = dialogView.findViewById<TextView>(R.id.tvVehicle)
        val tvMedicine = dialogView.findViewById<TextView>(R.id.tvMedicine)
        val editConsumption = dialogView.findViewById<TextInputEditText>(R.id.editConsumption)
        val editEmergency = dialogView.findViewById<TextInputEditText>(R.id.editEmergency)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)

        tvVehicle.text = item.vehicleName
        tvMedicine.text = item.medicineName
        editConsumption.setText(item.consumption)
        editEmergency.setText(item.totalEmergency)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Edit Consumption/Emergency")
            .setView(dialogView)
            .create()

        btnSave.setOnClickListener {
            // Sirf yeh 2 fields update hongi; opening/closing/storeIssued as-is rahenge
            val updated = item.copy(
                consumption = editConsumption.text?.toString().orEmpty(),
                totalEmergency = editEmergency.text?.toString().orEmpty()
            )
            onSave(updated)
            dialog.dismiss()
        }

        dialog.show()
    }
}