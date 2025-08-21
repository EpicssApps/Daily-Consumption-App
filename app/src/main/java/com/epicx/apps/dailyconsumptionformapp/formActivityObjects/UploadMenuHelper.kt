package com.epicx.apps.dailyconsumptionformapp.formActivityObjects

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.epicx.apps.dailyconsumptionformapp.AppDatabase
import com.epicx.apps.dailyconsumptionformapp.GoogleSheetApi
import com.epicx.apps.dailyconsumptionformapp.MyUploadActivity
import com.epicx.apps.dailyconsumptionformapp.R
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.app.ProgressDialog

object UploadMenuHelper {

    fun handleNormalUpload(
        activity: AppCompatActivity,
        db: AppDatabase,
        defaultVehicle: String,
        getShiftTag: () -> String?
    ): Boolean {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val shift = getShiftTag()
        val uploadKey = "${today}_${defaultVehicle}_${shift}_normal"
        val prefs = activity.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        if (prefs.getBoolean(uploadKey, false)) {
            AlertDialog.Builder(activity)
                .setTitle("Already Uploaded")
                .setMessage("Aap is shift ka normal data upload kar chuke hain. Agli shift me upload karein.")
                .setPositiveButton("OK", null)
                .show()
            return true
        }

        val allMedicines = db.getAllMedicines()
        if (allMedicines.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("Database Empty")
                .setMessage("Aap ka database khali hai, upload nahi ho sakta.")
                .setPositiveButton("OK", null)
                .show()
            return true
        }

        val uploadList = if (defaultVehicle == "RS-01") {
            allMedicines.map { fd ->
                fd.copy(
                    consumption = "0",
                    totalEmergency = "0"
                )
            }
        } else {
            allMedicines
        }

        AlertDialog.Builder(activity)
            .setTitle("Upload Warning")
            .setMessage("Yeh data aik shift me sirf 1 dafa send ho sakta hai. Upload hone ke baad data delete/edit nahi ho sakta. Continue?")
            .setPositiveButton("Upload") { _, _ ->
                val progress = ProgressDialog(activity)
                progress.setMessage("Uploading to Google Sheet...")
                progress.setCancelable(false)
                progress.show()

                activity.lifecycleScope.launch {
                    val result = GoogleSheetApi.bulkUpload(today, uploadList)
                    progress.dismiss()

                    val intent = Intent(activity, MyUploadActivity::class.java).apply {
                        putExtra("vehicle_name", defaultVehicle)
                        putExtra("date", today)
                    }

                    if (result.isSuccess) {
                        prefs.edit().putBoolean(uploadKey, true).apply()

                        if (defaultVehicle != "RS-01") {
                            db.resetConsumptionAndEmergency()
                        }
                        Toast.makeText(activity, "Upload Successful", Toast.LENGTH_SHORT).show()
                        activity.startActivity(intent)
                    } else {
                        Toast.makeText(
                            activity,
                            "Upload failed: " + (result.exceptionOrNull()?.message ?: "Unknown error"),
                            Toast.LENGTH_LONG
                        ).show()
                        activity.startActivity(intent)
                        Log.e("failedToLoad", "Upload failed", result.exceptionOrNull())
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()

        return true
    }

    fun handleRs01Upload(
        activity: AppCompatActivity,
        db: AppDatabase,
        defaultVehicle: String,
        getShiftTag: () -> String?
    ): Boolean {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val shift = getShiftTag()
        val uploadKey = "${today}_${defaultVehicle}_${shift}_rs01"
        val prefs = activity.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        if (defaultVehicle != "RS-01") return true

        if (prefs.getBoolean(uploadKey, false)) {
            AlertDialog.Builder(activity)
                .setTitle("Already Uploaded")
                .setMessage("Aap is shift ka RS-01 special data upload kar chuke hain. Agli shift me upload karein.")
                .setPositiveButton("OK", null)
                .show()
            return true
        }

        val allMedicines = db.getAllMedicines()
        if (allMedicines.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("Database Empty")
                .setMessage("Aap ka database khali hai, upload nahi ho sakta.")
                .setPositiveButton("OK", null)
                .show()
            return true
        }

        AlertDialog.Builder(activity)
            .setTitle("Upload Warning")
            .setMessage("Yeh data aik shift me sirf 1 dafa send ho sakta hai. Upload hone ke baad data delete/edit nahi ho sakta. Continue?")
            .setPositiveButton("Upload") { _, _ ->
                val progress = ProgressDialog(activity)
                progress.setMessage("Uploading to Google Sheet...")
                progress.setCancelable(false)
                progress.show()

                activity.lifecycleScope.launch {
                    val result = GoogleSheetApi.rs01BulkUpload(today, allMedicines)
                    progress.dismiss()
                    if (result.isSuccess) {
                        prefs.edit().putBoolean(uploadKey, true).apply()
                        db.resetConsumptionAndEmergency()
                        AlertDialog.Builder(activity)
                            .setTitle("Upload Successful")
                            .setMessage("Aap ka RS-01 special data upload ho chuka hai successfully.")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        Toast.makeText(
                            activity,
                            "Upload failed: " + result.exceptionOrNull()?.message,
                            Toast.LENGTH_LONG
                        ).show()
                        Log.e("failedToLoad", "Upload failed", result.exceptionOrNull())
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()

        return true
    }
}