package com.epicx.apps.dailyconsumptionformapp.formActivityObjects

import android.app.Activity
import android.app.ProgressDialog
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.epicx.apps.dailyconsumptionformapp.AppDatabase
import com.epicx.apps.dailyconsumptionformapp.GoogleSheetsClient
import com.epicx.apps.dailyconsumptionformapp.R
import com.epicx.apps.dailyconsumptionformapp.objects.MedicineStockUtils

object SyncFromServerHelper {

    fun syncFromServerForVehicle(
        activity: Activity,
        db: AppDatabase,
        vehicle: String,
        formatMedValue: (medicineName: String, value: String?) -> String,
        lastLoadedOpeningBalanceSetter: (Float) -> Unit,
        lastLoadedClosingBalanceSetter: (Float) -> Unit,
        showLoading: Boolean = false
    ) {
        // Show waiting dialog only when requested (e.g., first-time user)
        val progressDialog: ProgressDialog? = if (showLoading) {
            ProgressDialog(activity).apply {
                setMessage("Please wait, fetching your data...")
                setCancelable(false)
                show()
            }
        } else null

        GoogleSheetsClient.fetchBalances(vehicle) { success, resp ->
            if (!success || resp?.rows == null) {
                progressDialog?.dismiss()
                Toast.makeText(activity, "Sync failed: ${resp?.error ?: "network error"}", Toast.LENGTH_SHORT).show()
                return@fetchBalances
            }

            // Update DB for each medicine
            val current = db.getAllMedicines().filter { it.vehicleName == vehicle }
            resp.rows.forEach { row ->
                val existing = current.find { it.medicineName.trim() == row.medicine.trim() }
                val existingConsumption = existing?.consumption ?: "0"
                val existingEmergency = existing?.totalEmergency ?: "0"

                val openingStr = formatMedValue(row.medicine, row.opening.toString())
                val closingStr = formatMedValue(row.medicine, row.closing.toString())

                db.addOrUpdateMedicine(
                    vehicle = vehicle,
                    medicine = row.medicine,
                    opening = openingStr,
                    consumption = existingConsumption,
                    emergency = existingEmergency,
                    closing = closingStr
                )
            }

            // Refresh UI for selected medicine (if any)
            val medicineEdit = activity.findViewById<EditText>(R.id.edit_medicine)
            val textOpening = activity.findViewById<TextView>(R.id.text_opening)
            val textClosing = activity.findViewById<TextView>(R.id.text_closing)
            val editConsumption = activity.findViewById<EditText>(R.id.edit_consumption)
            val editEmergency = activity.findViewById<EditText>(R.id.edit_total_emergency)

            val medName = medicineEdit.text.toString()
            if (medName.isNotBlank()) {
                MedicineStockUtils.loadMedicineStock(
                    db = db,
                    vehicleName = vehicle,
                    medicineName = medName,
                    textOpening = textOpening,
                    textClosing = textClosing,
                    editConsumption = editConsumption,
                    editEmergency = editEmergency,
                    formatMedValue = formatMedValue,
                    lastLoadedOpeningBalanceSetter = lastLoadedOpeningBalanceSetter,
                    lastLoadedClosingBalanceSetter = lastLoadedClosingBalanceSetter
                )
            }

            progressDialog?.dismiss()
            Toast.makeText(activity, "Opening/Closing synced from sheet!", Toast.LENGTH_SHORT).show()
        }
    }
}