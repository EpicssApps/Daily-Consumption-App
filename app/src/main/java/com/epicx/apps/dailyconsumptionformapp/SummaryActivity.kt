package com.epicx.apps.dailyconsumptionformapp

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.epicx.apps.dailyconsumptionformapp.FormConstants.sskExportOrder
import com.epicx.apps.dailyconsumptionformapp.databinding.ActivitySummaryBinding
import com.epicx.apps.dailyconsumptionformapp.objects.SummaryEditDialog
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SummaryActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySummaryBinding
    private lateinit var db: AppDatabase
    private val sskMedicineList = FormConstants.sskMedicineList
    companion object {
        private const val REQUEST_WRITE_EXTERNAL_STORAGE = 1024
    }

    // For Import (file picker)
    private val importFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val fileName = getFileNameFromUri(uri) ?: ""
            if (!fileName.lowercase(Locale.ROOT).endsWith(".csv")) {
                Toast.makeText(this, "Sirf CSV file import ho sakti hai!", Toast.LENGTH_LONG).show()
                return@let
            }
            val inputStream = contentResolver.openInputStream(uri)
            val tempFile = File.createTempFile("imported", ".csv", cacheDir)
            tempFile.outputStream().use { out -> inputStream?.copyTo(out) }
            val ok = db.importFromCSV(tempFile)
            Toast.makeText(this, if (ok) "Import Successful!" else "Import Failed!", Toast.LENGTH_LONG).show()
            recreate()
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    result = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result.substring(cut + 1)
            }
        }
        return result
    }

    private fun getShiftTag(): String? {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        return when {
            hour in 8 until 15 -> "Morning"
            hour in 15 until 22 -> "Evening"
            hour in 22..23 -> "Night"
            hour in 0 until 8 -> "Early Morning"
            else -> null // Should never happen, but just in case
        }
    }

    private var pendingExport: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase(this)
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val defaultVehicle = prefs.getString("default_vehicle", "") ?: ""
        if (defaultVehicle == "RS-01") {
            findViewById<TextView>(R.id.label_summary_consumption)?.visibility = View.GONE
            findViewById<TextView>(R.id.label_summary_emergency)?.visibility = View.GONE
        }
        val labelStoreIssued = findViewById<TextView>(R.id.label_summary_store_issued)
        if (defaultVehicle != "RS-01") {
            labelStoreIssued.visibility = View.GONE
            findViewById<TextView>(R.id.btnCompile)?.visibility = View.GONE
            findViewById<TextView>(R.id.btnCompiledSummary)?.visibility = View.GONE
            findViewById<TextView>(R.id.btnSskFile)?.visibility = View.GONE
        }
        val dataList = db.getAllMedicines()

        // Setup summary recycler
        val onlyShowBasicColumns = defaultVehicle == "RS-01"
        binding.recyclerSummary.layoutManager = LinearLayoutManager(this)
        binding.recyclerSummary.layoutManager = LinearLayoutManager(this)
        binding.recyclerSummary.adapter = SummaryAdapter(dataList, onlyShowBasicColumns) { item ->
            SummaryEditDialog.show(this, item) { updatedItem ->
                db.addOrUpdateMedicine(
                    updatedItem.vehicleName,
                    updatedItem.medicineName,
                    updatedItem.openingBalance,
                    updatedItem.consumption,
                    updatedItem.totalEmergency,
                    updatedItem.closingBalance
                )
                db.updateStoreIssued(
                    updatedItem.vehicleName,
                    updatedItem.medicineName,
                    updatedItem.storeIssued
                )
                val newList = db.getAllMedicines()
                binding.recyclerSummary.adapter = SummaryAdapter(newList, onlyShowBasicColumns) { i ->
                    SummaryEditDialog.show(this, i) { /* same code as above */ }
                }
                Toast.makeText(this, "Changes saved!", Toast.LENGTH_SHORT).show()
            }
        }

        // Compile Button - fetch and compile previous day's data for all vehicles
        binding.btnCompile.setOnClickListener {
            // Calculate previous date in yyyy-MM-dd format
            val cal = Calendar.getInstance()
            cal.add(Calendar.DATE, -1)
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val previousDate = sdf.format(cal.time)

            binding.progressBar.visibility = View.VISIBLE
            lifecycleScope.launch {
                val result = GoogleSheetApi.getAllForDate(previousDate)
                binding.progressBar.visibility = View.GONE
                if (result.isSuccess) {
                    val list = result.getOrNull() ?: emptyList()
                    if (list.isEmpty()) {
                        Toast.makeText(this@SummaryActivity, "Koi data nahi mila!", Toast.LENGTH_LONG).show()
                        return@launch
                    }

                    // Mapping for similar medicine names
                    val medicineNameMapping = mapOf(
                        "Cotton Bandages BPC 6.5 cm X 6 meter (2.5\")" to "Cotton Bandages BPC 6.5 cm X 6 meter (2.5 inch)"
                        // Add more mappings if needed
                    )
                    val grouped = list.groupBy {
                        medicineNameMapping[it.medicineName.trim()] ?: it.medicineName.trim()
                    }
                    val compiledList = grouped.map { (medName, rows) ->
                        val totalOpening = rows.sumOf { it.openingBalance.toDoubleOrNull() ?: 0.0 }
                        val totalConsumption = rows.sumOf { it.consumption.toDoubleOrNull() ?: 0.0 }
                        val totalEmergency = rows.sumOf { it.totalEmergency.toDoubleOrNull() ?: 0.0 }
                        val totalStoreIssued = rows.sumOf { it.storeIssued.toDoubleOrNull() ?: 0.0 }
                        val totalClosing = totalOpening - totalConsumption
                        val stockAvailable = rows.find { it.vehicleName == "RS-01" }?.stockAvailable ?: ""
                        AppDatabase.CompiledMedicineData(
                            medicineName = medName,
                            totalConsumption = totalConsumption,
                            totalEmergency = totalEmergency,
                            totalOpening = totalOpening,
                            totalClosing = totalClosing,
                            totalStoreIssued = totalStoreIssued,
                            stockAvailable = stockAvailable
                        )
                    }

                    // Save compiled summary to database
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    db.insertCompiledSummary(today, compiledList)

                    // Show compiled summary to user in a dialog, and option to export
                    val builder = AlertDialog.Builder(this@SummaryActivity)
                    val sb = StringBuilder()
                    sb.append("Medicine, Opening, Consumption, Emergency, Closing, Store Issued, Stock Available\n")
                    for (item in compiledList) {
                        sb.append("${item.medicineName}, ${item.totalOpening}, ${item.totalConsumption}, ${item.totalEmergency}, ${item.totalClosing}, ${item.totalStoreIssued}, ${item.stockAvailable}\n")
                    }
                    builder.setTitle("Compiled Summary")
                    builder.setMessage(sb.toString())
                    builder.setPositiveButton("Export as CSV") { _, _ ->
                        // Export code:
                        try {
                            val fileName = "compiled_medicines_${System.currentTimeMillis()}.csv"
                            val file = File(getExternalFilesDir(null), fileName)
                            file.printWriter().use { out ->
                                out.println("MedicineName,TotalOpening,TotalConsumption,TotalEmergency,TotalClosing,TotalStoreIssued,StockAvailable")
                                for (item in compiledList) {
                                    out.println("${item.medicineName},${item.totalOpening},${item.totalConsumption},${item.totalEmergency},${item.totalClosing},${item.totalStoreIssued},${item.stockAvailable}")
                                }
                            }
                            // Share or notify
                            val uri = FileProvider.getUriForFile(
                                this@SummaryActivity,
                                "${applicationContext.packageName}.fileprovider",
                                file
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND)
                            shareIntent.type = "text/csv"
                            shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
                            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            startActivity(Intent.createChooser(shareIntent, "CSV file share karen..."))
                            Toast.makeText(this@SummaryActivity, "CSV export ho gaya: $fileName", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@SummaryActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                    builder.setNegativeButton("Close", null)
                    builder.show()

                } else {
                    Toast.makeText(this@SummaryActivity, "Failed to compile: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Export Button
        binding.btnExport.setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingExport = { exportCsv() }
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        REQUEST_WRITE_EXTERNAL_STORAGE
                    )
                    return@setOnClickListener
                }
            }
            exportCsv()
        }

        // Import Button
        binding.btnImport.setOnClickListener {
            importFileLauncher.launch("*/*")
        }

        // Button to open compiled summary activity (add a button in XML and set its id as btnCompiledSummary)
        binding.btnCompiledSummary.setOnClickListener {
            val intent = Intent(this, CompiledSummaryActivity::class.java)
            startActivity(intent)
        }
        binding.btnSskFile.setOnClickListener {
            val compiledList = db.getAllCompiledSummary() // yeh database ka compiled summary hai
            val latestDate = compiledList.firstOrNull()?.date
            if (latestDate == null) {
                Toast.makeText(this, "Koi compiled summary nahi mili!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val fileName = "ssk_file_${latestDate}.csv"
            val file = File(getExternalFilesDir(null), fileName)
            val ok = exportSskSelectedMedicinesCsv(file, sskExportOrder, compiledList)
            if (!ok) {
                Toast.makeText(this, "Export Failed!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            // Share intent
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND)
            shareIntent.type = "text/csv"
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(shareIntent, "Share CSV via"))
            Toast.makeText(this, "SSK file export ho gayi: $fileName", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WRITE_EXTERNAL_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingExport?.invoke()
            } else {
                Toast.makeText(this, "Storage permission required to export file.", Toast.LENGTH_LONG).show()
            }
            pendingExport = null
        }
    }

    fun exportCompiledCsv(compiledList: List<AppDatabase.CompiledMedicineData>, file: File): Boolean {
        return try {
            file.printWriter().use { out ->
                out.println("MedicineName,TotalOpening,TotalConsumption,TotalEmergency,TotalClosing,TotalStoreIssued,StockAvailable")
                for (item in compiledList) {
                    out.println("${item.medicineName},${item.totalOpening},${item.totalConsumption},${item.totalEmergency},${item.totalClosing},${item.totalStoreIssued},${item.stockAvailable}")
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun exportCsv() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
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
            Toast.makeText(this, "Export Failed!", Toast.LENGTH_LONG).show()
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
                val resolver = contentResolver
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
                    this,
                    "${applicationContext.packageName}.fileprovider",
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
            Toast.makeText(this, "Exported to Downloads/$fileName", Toast.LENGTH_LONG).show()
            AlertDialog.Builder(this)
                .setTitle("Export Successful")
                .setMessage("File export ho gayi hai. Ab aap is file ko share bhi kar sakte hain.")
                .setPositiveButton("Share") { dialog2, _ ->
                    val shareIntent = Intent(Intent.ACTION_SEND)
                    shareIntent.type = mimeType
                    shareIntent.putExtra(Intent.EXTRA_STREAM, outputUri)
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(shareIntent, "CSV file share karen..."))
                    dialog2.dismiss()
                }
                .setNegativeButton("Close", null)
                .show()
        } else {
            Toast.makeText(this, "Export Failed!", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportSskSelectedMedicinesCsv(
        file: File,
        medicineList: List<String>,
        compiledSummary: List<CompiledMedicineDataWithId>
    ): Boolean {
        return try {
            file.printWriter().use { out ->
                out.println("Medicine Name,Opening Balance,Consumption,Closing Balance")
                medicineList.forEach { medName ->
                    val entry = compiledSummary.firstOrNull { it.medicineName.trim() == medName.trim() }
                    val opening = entry?.totalOpening ?: 0.0
                    val consumption = entry?.totalConsumption ?: 0.0
                    val closing = entry?.totalClosing ?: 0.0
                    val safeMedName = if (medName.contains(",")) "\"$medName\"" else medName
                    // Debug print:
                    println("Exporting: $safeMedName | $opening | $consumption | $closing")
                    out.println("%s,%.2f,%.2f,%.2f".format(safeMedName, opening, consumption, closing))
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}