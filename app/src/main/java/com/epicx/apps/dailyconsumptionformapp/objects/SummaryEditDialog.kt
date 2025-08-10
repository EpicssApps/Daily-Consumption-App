package com.epicx.apps.dailyconsumptionformapp.objects

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.*
import com.epicx.apps.dailyconsumptionformapp.FormData
import com.epicx.apps.dailyconsumptionformapp.R
import com.google.android.material.textfield.TextInputEditText

object SummaryEditDialog {
    fun show(context: Context, item: FormData, onSave: (FormData) -> Unit) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_summary_item, null)
        val tvVehicle = dialogView.findViewById<TextView>(R.id.tvVehicle)
        val tvMedicine = dialogView.findViewById<TextView>(R.id.tvMedicine)
        val editOpening = dialogView.findViewById<TextInputEditText>(R.id.editOpening)
        val editConsumption = dialogView.findViewById<TextInputEditText>(R.id.editConsumption)
        val editEmergency = dialogView.findViewById<TextInputEditText>(R.id.editEmergency)
        val editClosing = dialogView.findViewById<TextInputEditText>(R.id.editClosing)
        val editStoreIssued = dialogView.findViewById<TextInputEditText>(R.id.editStoreIssued)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)

        tvVehicle.text = item.vehicleName
        tvMedicine.text = item.medicineName
        editOpening.setText(item.openingBalance)
        editConsumption.setText(item.consumption)
        editEmergency.setText(item.totalEmergency)
        editClosing.setText(item.closingBalance)
        editStoreIssued.setText(item.storeIssued)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Edit Entry")
            .setView(dialogView)
            .create()

        btnSave.setOnClickListener {
            val updated = item.copy(
                openingBalance = editOpening.text.toString(),
                consumption = editConsumption.text.toString(),
                totalEmergency = editEmergency.text.toString(),
                closingBalance = editClosing.text.toString(),
                storeIssued = editStoreIssued.text.toString()
            )
            onSave(updated)
            dialog.dismiss()
        }

        dialog.show()
    }
}