package com.epicx.apps.dailyconsumptionformapp.formActivityObjects

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.epicx.apps.dailyconsumptionformapp.AppDatabase
import com.epicx.apps.dailyconsumptionformapp.GoogleSheetApi
import kotlinx.coroutines.launch
import com.epicx.apps.dailyconsumptionformapp.tempStorage.TempRs01DailyStore

object UploadMenuHelper {

    fun handleRs01Upload(
        activity: AppCompatActivity,
        db: AppDatabase,
        defaultVehicle: String,
        getShiftTag: () -> String?
    ): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val shift = getShiftTag()
        val uploadKey = "${today}_${defaultVehicle}_${shift}_rs01"
        val prefs = activity.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        if (!defaultVehicle.equals("RS-01", true)) return true

        if (prefs.getBoolean(uploadKey, false)) {
            AlertDialog.Builder(activity)
                .setTitle("Already Uploaded")
                .setMessage("Is shift ka RS-01 upload pehle ho chuka hai.")
                .setPositiveButton("OK", null)
                .show()
            return true
        }

        // Saari medicines (RS-01)
        val allMedicines = db.getAllMedicines()
            .filter { it.vehicleName.equals("RS-01", true) }
            .sortedBy { it.medicineName.lowercase() }

        if (allMedicines.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("Database Empty")
                .setMessage("RS-01 database khali hai.")
                .setPositiveButton("OK", null)
                .show()
            return true
        }

        // Stored real consumption (sirf un medicines ke liye jin per day submit hua)
        val realMap = TempRs01DailyStore.getMap(activity, today)  // medicine -> cumulative consumption (Int)

        // BUILD LIST: Har medicine bhejna hai, chahe consumption 0 ho
        val uploadList = allMedicines.map { fd ->
            val realCons = realMap[fd.medicineName] ?: 0
            fd.copy(
                consumption = realCons.toString(),
                totalEmergency = "0"
            )
        }

        // Info stats (optional)
        val consumedCount = uploadList.count { it.consumption != "0" }
        val zeroCount = uploadList.size - consumedCount

        AlertDialog.Builder(activity)
            .setTitle("RS-01 Upload")
            .setMessage(
                buildString {
                    append("Total medicines: ${uploadList.size}\n")
                    append("With consumption: $consumedCount\n")
                    append("Zero consumption: $zeroCount\n\n")
                    append("Upload karein?")
                }
            )
            .setPositiveButton("Upload") { _, _ ->
                val progress = android.app.ProgressDialog(activity)
                progress.setMessage("Uploading RS-01 data...")
                progress.setCancelable(false)
                progress.show()

                activity.lifecycleScope.launch {
                    val result = GoogleSheetApi.rs01BulkUpload(today, uploadList)
                    progress.dismiss()
                    if (result.isSuccess) {
                        prefs.edit().putBoolean(uploadKey, true).apply()

                        // Local reset (safe, even if already zero)
                        db.resetConsumptionAndEmergency()

                        // Sirf real consumption store clear karo (taake next shift/day fresh ho)
                        TempRs01DailyStore.clearDate(activity, today)

                        AlertDialog.Builder(activity)
                            .setTitle("Success")
                            .setMessage("RS-01 upload successful.\n(consumed: $consumedCount, zero: $zeroCount)")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        android.widget.Toast.makeText(
                            activity,
                            "Upload failed: " + (result.exceptionOrNull()?.message ?: "Unknown error"),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        android.util.Log.e("RS01Upload", "Failed", result.exceptionOrNull())
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()

        return true
    }
}