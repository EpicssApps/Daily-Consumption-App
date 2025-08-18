package com.epicx.apps.dailyconsumptionformapp.objects

import java.io.File
import com.epicx.apps.dailyconsumptionformapp.CompiledMedicineDataWithId
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.epicx.apps.dailyconsumptionformapp.AppDatabase
import java.text.SimpleDateFormat
import java.util.*

object CsvExportUtils {
    fun exportSskSelectedMedicinesCsv(
        file: File,
        medicineList: List<String>,
        compiledSummary: List<CompiledMedicineDataWithId>
    ): Boolean {
        return try {
            file.printWriter().use { out ->
                out.println("Medicine Name,Opening Balance,Consumption,Closing Balance")
                medicineList.forEach { medName ->
                    val entry = compiledSummary.firstOrNull { it.medicineName.trim() == medName.trim() }
                    var opening = entry?.totalOpening ?: 0.0
                    var consumption = entry?.totalConsumption ?: 0.0
                    var closing = entry?.totalClosing ?: 0.0
                    val safeMedName = if (medName.contains(",")) "\"$medName\"" else medName

                    if (medName.trim().equals("Examination Gloves", ignoreCase = true)) {
                        opening = (opening / 2).toInt().toDouble()
                        consumption = (consumption / 2).toInt().toDouble()
                        closing = (closing / 2).toInt().toDouble()
                        out.println("%s,%.0f,%.0f,%.0f".format(safeMedName, opening, consumption, closing))
                    } else {
                        out.println("%s,%.2f,%.2f,%.2f".format(safeMedName, opening, consumption, closing))
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun exportCsv(
        context: Context,
        db: AppDatabase,
        cacheDir: File,
        getShiftTag: () -> String?
    ) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val vehicleName =
            prefs.getString("default_vehicle", "UnknownVehicle")?.replace(" ", "") ?: "UnknownVehicle"
        val shiftTag = getShiftTag()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val time = SimpleDateFormat("hh-mm a", Locale.US).format(Date())
        val fileName = "${vehicleName}_${shiftTag}_$date" +
                "_$time.csv"

        val tempExportFile = File.createTempFile("export", ".csv", cacheDir)
        val ok = db.exportToCSV(tempExportFile)
        if (!ok) {
            Toast.makeText(context, "Export Failed!", Toast.LENGTH_LONG).show()
            return
        }

        val mimeType = "text/csv"
        var outputUri: Uri? = null
        var exportSuccess = false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val collection =
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, contentValues)
                if (itemUri != null) {
                    resolver.openOutputStream(itemUri)?.use { outputStream ->
                        tempExportFile.inputStream().copyTo(outputStream)
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(itemUri, contentValues, null, null)
                    outputUri = itemUri
                    exportSuccess = true
                }
            } else {
                val downloadsDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = File(downloadsDir, fileName)
                tempExportFile.copyTo(file, overwrite = true)
                outputUri = FileProvider.getUriForFile(
                    context,
                    "${context.applicationContext.packageName}.fileprovider",
                    file
                )
                exportSuccess = true
            }
        } catch (e: Exception) {
            exportSuccess = false
        } finally {
            tempExportFile.delete()
        }

        if (exportSuccess && outputUri != null) {
            Toast.makeText(context, "Exported to Downloads/$fileName", Toast.LENGTH_LONG).show()
            AlertDialog.Builder(context)
                .setTitle("Export Successful")
                .setMessage("File export ho gayi hai. Ab aap is file ko share bhi kar sakte hain.")
                .setPositiveButton("Share") { dialog2, _ ->
                    val shareIntent = Intent(Intent.ACTION_SEND)
                    shareIntent.type = mimeType
                    shareIntent.putExtra(Intent.EXTRA_STREAM, outputUri)
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(shareIntent, "CSV file share karen..."))
                    dialog2.dismiss()
                }
                .setNegativeButton("Close", null)
                .show()
        } else {
            Toast.makeText(context, "Export Failed!", Toast.LENGTH_LONG).show()
        }
    }
}