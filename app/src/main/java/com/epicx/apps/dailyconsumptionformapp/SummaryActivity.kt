package com.epicx.apps.dailyconsumptionformapp

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import android.widget.SearchView
import com.epicx.apps.dailyconsumptionformapp.objects.CsvExportUtils
import com.epicx.apps.dailyconsumptionformapp.objects.MonthlyConsumptionHelper

class SummaryActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySummaryBinding
    private lateinit var db: AppDatabase
    private var dataList: List<FormData> = listOf()
    private var filteredList: MutableList<FormData> = mutableListOf()
    private lateinit var summaryAdapter: SummaryAdapter

    companion object {
        private const val REQUEST_WRITE_EXTERNAL_STORAGE = 1024
    }

    // For Import (file picker)
    private val importFileLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                val fileName = getFileNameFromUri(uri) ?: ""
                if (!fileName.lowercase(Locale.ROOT).endsWith(".csv")) {
                    Toast.makeText(this, "Sirf CSV file import ho sakti hai!", Toast.LENGTH_LONG)
                        .show()
                    return@let
                }
                val inputStream = contentResolver.openInputStream(uri)
                val tempFile = File.createTempFile("imported", ".csv", cacheDir)
                tempFile.outputStream().use { out -> inputStream?.copyTo(out) }
                val ok = db.importFromCSV(tempFile)
                Toast.makeText(
                    this,
                    if (ok) "Import Successful!" else "Import Failed!",
                    Toast.LENGTH_LONG
                ).show()
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
            else -> null
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
        val labelStoreIssued = findViewById<TextView>(R.id.label_summary_store_issued)
        if (defaultVehicle == "RS-01") {
            findViewById<TextView>(R.id.label_summary_emergency)?.visibility = View.GONE
        }else{
            labelStoreIssued.visibility = View.GONE
            findViewById<TextView>(R.id.btnCompile)?.visibility = View.GONE
            findViewById<TextView>(R.id.btnCompiledSummary)?.visibility = View.GONE
            findViewById<TextView>(R.id.btnSskFile)?.visibility = View.GONE
            findViewById<TextView>(R.id.btnMissingUploads)?.visibility = View.GONE
            findViewById<TextView>(R.id.btnMonthlyConsumption)?.visibility = View.GONE
        }


        dataList = db.getAllMedicines()
        filteredList.addAll(dataList)
        val onlyShowBasicColumns = defaultVehicle == "RS-01"

        summaryAdapter = SummaryAdapter(filteredList, onlyShowBasicColumns) { item ->
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
                dataList = db.getAllMedicines()
                filterList(binding.searchViewSummary.query.toString())
                Toast.makeText(this, "Changes saved!", Toast.LENGTH_SHORT).show()
            }
        }
        binding.recyclerSummary.layoutManager = LinearLayoutManager(this)
        binding.recyclerSummary.adapter = summaryAdapter

        // SearchView listener
        binding.searchViewSummary.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                filterList(query)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                filterList(newText)
                return true
            }
        })

        // Compile Button - fetch and compile previous day's data for all vehicles
        binding.btnCompile.setOnClickListener {
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
                        Toast.makeText(
                            this@SummaryActivity,
                            "Koi data nahi mila!",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                    val medicineNameMapping = mapOf(
                        "Cotton Bandages BPC 6.5 cm X 6 meter (2.5\")" to "Cotton Bandages BPC 6.5 cm X 6 meter (2.5 inch)"
                    )
                    val grouped = list.groupBy {
                        medicineNameMapping[it.medicineName.trim()] ?: it.medicineName.trim()
                    }
                    val compiledList = grouped.map { (medName, rows) ->
                        val totalOpening = rows.sumOf { it.openingBalance.toDoubleOrNull() ?: 0.0 }
                        val totalConsumption = rows.sumOf { it.consumption.toDoubleOrNull() ?: 0.0 }
                        val totalEmergency =
                            rows.sumOf { it.totalEmergency.toDoubleOrNull() ?: 0.0 }
                        val totalStoreIssued = rows.sumOf { it.storeIssued.toDoubleOrNull() ?: 0.0 }
                        val totalClosing = totalOpening - totalConsumption
                        val stockAvailable =
                            rows.find { it.vehicleName == "RS-01" }?.stockAvailable ?: ""
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

                    db.insertCompiledSummary(previousDate, compiledList)

                    val builder = AlertDialog.Builder(this@SummaryActivity)
                    val sb = StringBuilder()
                    sb.append("Medicine, Opening, Consumption, Emergency, Closing, Store Issued, Stock Available\n")
                    for (item in compiledList) {
                        sb.append("${item.medicineName}, ${item.totalOpening}, ${item.totalConsumption}, ${item.totalEmergency}, ${item.totalClosing}, ${item.totalStoreIssued}, ${item.stockAvailable}\n")
                    }
                    builder.setTitle("Compiled Summary")
                    builder.setMessage(sb.toString())
                    builder.setPositiveButton("Export as CSV") { _, _ ->
                        try {
                            val fileName = "compiled_medicines_${System.currentTimeMillis()}.csv"
                            val file = File(getExternalFilesDir(null), fileName)
                            file.printWriter().use { out ->
                                out.println("MedicineName,TotalOpening,TotalConsumption,TotalEmergency,TotalClosing,TotalStoreIssued,StockAvailable")
                                for (item in compiledList) {
                                    out.println("${item.medicineName},${item.totalOpening},${item.totalConsumption},${item.totalEmergency},${item.totalClosing},${item.totalStoreIssued},${item.stockAvailable}")
                                }
                            }
                            val uri = FileProvider.getUriForFile(
                                this@SummaryActivity,
                                "${applicationContext.packageName}.fileprovider",
                                file
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND)
                            shareIntent.type = "text/csv"
                            shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
                            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            startActivity(
                                Intent.createChooser(
                                    shareIntent,
                                    "CSV file share karen..."
                                )
                            )
                            Toast.makeText(
                                this@SummaryActivity,
                                "CSV export ho gaya: $fileName",
                                Toast.LENGTH_LONG
                            ).show()
                        } catch (e: Exception) {
                            Toast.makeText(
                                this@SummaryActivity,
                                "Export failed: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    builder.setNegativeButton("Close", null)
                    builder.show()
                } else {
                    Toast.makeText(
                        this@SummaryActivity,
                        "Failed to compile: ${result.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // NEW: Monthly Consumption (dialog only, no DB save)
        binding.btnMonthlyConsumption.setOnClickListener {
            MonthlyConsumptionHelper.showMonthPickerAndReport(this@SummaryActivity, binding.progressBar, vehicleName = "RS-01")
        }

        binding.btnMissingUploads.setOnClickListener {
            // Show dialog with two buttons: Yesterday & Today
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Select Date")
            builder.setMessage("Kis date ka missing uploads dekhna hai?")
            builder.setPositiveButton("Yesterday") { _, _ ->
                checkMissingUploads(dateType = "yesterday")
            }
            builder.setNegativeButton("Today") { _, _ ->
                checkMissingUploads(dateType = "today")
            }
            builder.show()
        }

        binding.btnExport.setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingExport = {
                        CsvExportUtils.exportCsv(
                            context = this,
                            db = db,
                            cacheDir = cacheDir,
                            getShiftTag = { getShiftTag() }
                        )
                    }
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        REQUEST_WRITE_EXTERNAL_STORAGE
                    )
                    return@setOnClickListener
                }
            }
            CsvExportUtils.exportCsv(
                context = this,
                db = db,
                cacheDir = cacheDir,
                getShiftTag = { getShiftTag() }
            )
        }

        binding.btnImport.setOnClickListener {
            importFileLauncher.launch("*/*")
        }

        binding.btnCompiledSummary.setOnClickListener {
            val intent = Intent(this, CompiledSummaryActivity::class.java)
            startActivity(intent)
        }

        binding.btnSskFile.setOnClickListener {
            val compiledList = db.getAllCompiledSummary()
            val latestDate = compiledList.firstOrNull()?.date
            if (latestDate == null) {
                Toast.makeText(this, "Koi compiled summary nahi mili!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val fileName = "ssk_file_${latestDate}.csv"
            val file = File(getExternalFilesDir(null), fileName)
            val ok =
                CsvExportUtils.exportSskSelectedMedicinesCsv(file, sskExportOrder, compiledList)
            if (!ok) {
                Toast.makeText(this, "Export Failed!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
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

    private fun checkMissingUploads(dateType: String) {
        val cal = Calendar.getInstance()
        if (dateType == "yesterday") {
            cal.add(Calendar.DATE, -1)
        }
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val selectedDate = sdf.format(cal.time)

        val skipBnbList = listOf(
            "BNB 02", "BNB 04", "BNB 06", "BNB 12", "BNB 13", "BNB 14", "BNB 16", "BNB 20","BNB 25", "BNB 27",
            "BNB 30", "BNB 31", "BNB 33", "BNB 34", "BNB 44", "BNB 45"
        )
        val allVehicles = listOf(
            "BNA 07 Night V09", "BNA 08 Night V09", "BNA 09 Night V09", "BNA 10 Night V09", "BNA 11 Night V09",
            "BNA 17 Night V09", "BNA 21 Night V09", "BNA 22 Night V09", "BNA 25 Night V09", "BNA 26 Night V09", "BNA 29 Night V09"
        ) + (1..50)
            .map { "BNB %02d".format(it) }
            .filter { it !in skipBnbList }

        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = GoogleSheetApi.getAllForDate(selectedDate)
            binding.progressBar.visibility = View.GONE
            if (result.isSuccess) {
                val list = result.getOrNull() ?: emptyList()
                val uploadedVehicles = list.map { it.vehicleName.trim() }.distinct()
                val missingVehicles = allVehicles.filter { refName ->
                    uploadedVehicles.none { uploadedName -> uploadedName.equals(refName, ignoreCase = true) }
                }

                if (missingVehicles.isEmpty()) {
                    Toast.makeText(this@SummaryActivity, "Sab ne upload kar diya hai!", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Prepare short names like "BNA 08" or "BNB 03"
                val missingShortNames = missingVehicles.map { v ->
                    val parts = v.split(" ")
                    if (parts.size >= 2) "${parts[0]} ${parts[1]}" else v
                }.sorted()

                val message = missingShortNames.joinToString(separator = "\n")

                AlertDialog.Builder(this@SummaryActivity)
                    .setTitle("These vehicle still not upload their data:")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                Toast.makeText(this@SummaryActivity, "Failed to fetch data: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun filterList(query: String?) {
        filteredList.clear()
        if (query.isNullOrEmpty()) {
            filteredList.addAll(dataList)
        } else {
            val lowerQuery = query.lowercase()
            filteredList.addAll(dataList.filter {
                it.medicineName.lowercase().contains(lowerQuery)
            })
        }
        summaryAdapter.notifyDataSetChanged()
    }

    override fun onResume() {
        super.onResume()
        dataList = db.getAllMedicines()
        filterList(binding.searchViewSummary.query.toString())
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
                Toast.makeText(
                    this,
                    "Storage permission required to export file.",
                    Toast.LENGTH_LONG
                ).show()
            }
            pendingExport = null
        }
    }
}