package com.epicx.apps.dailyconsumptionformapp

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.SearchView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.epicx.apps.dailyconsumptionformapp.FormConstants.sskExportOrder
import com.epicx.apps.dailyconsumptionformapp.databinding.ActivitySummaryBinding
import com.epicx.apps.dailyconsumptionformapp.formActivityObjects.PendingRequestCache
import com.epicx.apps.dailyconsumptionformapp.objects.CsvExportUtils
import com.epicx.apps.dailyconsumptionformapp.objects.MonthlyConsumptionHelper
import com.epicx.apps.dailyconsumptionformapp.objects.SummaryEditDialog
import com.epicx.apps.dailyconsumptionformapp.summaryActivityObjects.StockCsvExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SummaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySummaryBinding
    private lateinit var db: AppDatabase

    // Current selected vehicle (updated from intent or prefs)
    private var currentVehicle: String = ""

    // Full list (for currentVehicle only)
    private var dataList: List<FormData> = emptyList()
    private val filteredList: MutableList<FormData> = mutableListOf()
    private lateinit var summaryAdapter: SummaryAdapter

    companion object {
        private const val REQUEST_WRITE_EXTERNAL_STORAGE = 1024
    }

    private var pendingExport: (() -> Unit)? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase(this)

        // 1) Resolve current vehicle (intent extra preferred, fallback prefs)
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        currentVehicle = intent.getStringExtra("vehicle")
            ?: prefs.getString("default_vehicle", "") ?: ""

        // 2) Setup adapter (onlyShowBasicColumns depends on RS-01)
        summaryAdapter = SummaryAdapter(
            list = filteredList,
            onlyShowBasicColumns = currentVehicle.equals("RS-01", true)
        ) { item ->
            // Edit dialog logic
            SummaryEditDialog.show(this, item) { updatedItem ->
                fun toIntSafe(s: String?): Int = s?.toDoubleOrNull()?.toInt() ?: 0

                val oldConsumption = toIntSafe(item.consumption)
                val newConsumption = toIntSafe(updatedItem.consumption)
                val oldEmergency = toIntSafe(item.totalEmergency)
                val newEmergency = toIntSafe(updatedItem.totalEmergency)
                val oldClosing = toIntSafe(item.closingBalance)
                val deltaConsumption = newConsumption - oldConsumption

                if (oldClosing == 0 && deltaConsumption > 0) {
                    AlertDialog.Builder(this)
                        .setTitle("Stock Zero")
                        .setMessage("Closing balance ZERO hai. Zyada consumption add nahi kar sakte.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@show
                }
                if (deltaConsumption > 0 && deltaConsumption > oldClosing) {
                    AlertDialog.Builder(this)
                        .setTitle("Insufficient Stock")
                        .setMessage(
                            "Available Closing: $oldClosing\n" +
                                    "Additional Consumption: $deltaConsumption\n\n" +
                                    "Itna stock available nahi."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                    return@show
                }
                val newClosing = (oldClosing - deltaConsumption).coerceAtLeast(0)

                db.addOrUpdateMedicine(
                    updatedItem.vehicleName,
                    updatedItem.medicineName,
                    updatedItem.openingBalance,
                    newConsumption.toString(),
                    newEmergency.toString(),
                    newClosing.toString()
                )
                db.updateStoreIssued(
                    updatedItem.vehicleName,
                    updatedItem.medicineName,
                    toIntSafe(updatedItem.storeIssued).toString()
                )

                reloadData()
                applySearchFilter(binding.searchViewSummary.query?.toString())
                Toast.makeText(this, "Changes saved!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.recyclerSummary.layoutManager = LinearLayoutManager(this)
        binding.recyclerSummary.adapter = summaryAdapter

        // 3) Apply initial vehicle-specific UI visibility
        applyVehicleUiMode()

        // 4) Initial load of data for that vehicle
        reloadData()
        applySearchFilter(null)

        // Search handling
        binding.searchViewSummary.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                applySearchFilter(query); return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                applySearchFilter(newText); return true
            }
        })

        // Download full sheet CSV (INTENTIONALLY NOT FILTERED – if you want filter, uncomment filter line)
        binding.btnDownloadCsv.setOnClickListener {
            binding.progressBar.visibility = View.VISIBLE
            lifecycleScope.launch {
                val apiResult = GoogleSheetApi.getAllCurrentStock()
                if (!apiResult.isSuccess) {
                    Toast.makeText(
                        this@SummaryActivity,
                        "Download fail: ${apiResult.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                val list = apiResult.getOrNull().orEmpty()

                // If you want only currentVehicle rows:
                // val working = list.filter { it.vehicleName.equals(currentVehicle, true) }
                val working = list

                val rows = working.map { fd ->
                    StockCsvExporter.StockRow(
                        fd.vehicleName,
                        fd.medicineName,
                        fd.openingBalance,
                        fd.consumption,
                        fd.totalEmergency,
                        fd.closingBalance,
                        fd.storeIssued,
                        fd.stockAvailable
                    )
                }

                when (val export = StockCsvExporter.export(rows, this@SummaryActivity)) {
                    is StockCsvExporter.ExportResult.Success -> {
                        Toast.makeText(
                            this@SummaryActivity,
                            "Saved: ${export.fileName}",
                            Toast.LENGTH_LONG
                        ).show()
                        binding.progressBar.visibility = View.GONE
                        StockCsvExporter.share(this@SummaryActivity, export.uri)
                    }
                    is StockCsvExporter.ExportResult.Error -> {
                        Toast.makeText(
                            this@SummaryActivity,
                            export.message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        binding.btnRollover.setOnClickListener { showRolloverConfirm() }

        binding.btnCompile.setOnClickListener {
            compileCurrentSheet()
        }

        binding.btnMonthlyConsumption.setOnClickListener {
            // Monthly consumption logically RS-01 only, but passing currentVehicle if RS-01 selected
            MonthlyConsumptionHelper.showMonthPickerAndReport(
                this@SummaryActivity,
                binding.progressBar,
                vehicleName = if (currentVehicle.isBlank()) "RS-01" else currentVehicle
            )
        }

        binding.btnCompiledSummary.setOnClickListener {
            startActivity(Intent(this, CompiledSummaryActivity::class.java))
        }

        binding.btnSskFile.setOnClickListener {
            exportSskFile()
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val latest = prefs.getString("default_vehicle", "") ?: ""
        if (!latest.equals(currentVehicle, true)) {
            // Vehicle changed while we were away
            currentVehicle = latest
            applyVehicleUiMode()
            // Adapter columns may need re-init if RS-01 status flipped
            summaryAdapter = SummaryAdapter(
                list = filteredList,
                onlyShowBasicColumns = currentVehicle.equals("RS-01", true)
            ) { item ->
                // Reuse same click logic to avoid duplication
                SummaryEditDialog.show(this, item) { updatedItem ->
                    fun toIntSafe(s: String?): Int = s?.toDoubleOrNull()?.toInt() ?: 0
                    val oldConsumption = toIntSafe(item.consumption)
                    val newConsumption = toIntSafe(updatedItem.consumption)
                    val oldEmergency = toIntSafe(item.totalEmergency)
                    val newEmergency = toIntSafe(updatedItem.totalEmergency)
                    val oldClosing = toIntSafe(item.closingBalance)
                    val deltaConsumption = newConsumption - oldConsumption
                    if (oldClosing == 0 && deltaConsumption > 0) {
                        AlertDialog.Builder(this)
                            .setTitle("Stock Zero")
                            .setMessage("Closing balance ZERO hai. Zyada consumption add nahi kar sakte.")
                            .setPositiveButton("OK", null)
                            .show()
                        return@show
                    }
                    if (deltaConsumption > 0 && deltaConsumption > oldClosing) {
                        AlertDialog.Builder(this)
                            .setTitle("Insufficient Stock")
                            .setMessage(
                                "Available Closing: $oldClosing\n" +
                                        "Additional Consumption: $deltaConsumption\n\n" +
                                        "Itna stock available nahi."
                            )
                            .setPositiveButton("OK", null)
                            .show()
                        return@show
                    }
                    val newClosing = (oldClosing - deltaConsumption).coerceAtLeast(0)
                    db.addOrUpdateMedicine(
                        updatedItem.vehicleName,
                        updatedItem.medicineName,
                        updatedItem.openingBalance,
                        newConsumption.toString(),
                        newEmergency.toString(),
                        newClosing.toString()
                    )
                    db.updateStoreIssued(
                        updatedItem.vehicleName,
                        updatedItem.medicineName,
                        toIntSafe(updatedItem.storeIssued).toString()
                    )
                    reloadData()
                    applySearchFilter(binding.searchViewSummary.query?.toString())
                    Toast.makeText(this, "Changes saved!", Toast.LENGTH_SHORT).show()
                }
            }
            binding.recyclerSummary.adapter = summaryAdapter
            reloadData()
            applySearchFilter(binding.searchViewSummary.query?.toString())
        } else {
            // Same vehicle – just refresh data in case changed
            reloadData()
            applySearchFilter(binding.searchViewSummary.query?.toString())
        }
    }

    private fun applyVehicleUiMode() {
        val labelStoreIssued = findViewById<TextView>(R.id.label_summary_store_issued)
        val btnCompile = binding.btnCompile
        val btnCompiledSummary = binding.btnCompiledSummary
        val btnSskFile = binding.btnSskFile
        val btnMonthlyConsumption = binding.btnMonthlyConsumption
        val btnRollover = binding.btnRollover
        val btnDownload = binding.btnDownloadCsv
        val labelEmergency = findViewById<TextView>(R.id.label_summary_emergency)

        val isRs01 = currentVehicle.equals("RS-01", true)

        // RS-01 special: emergency hidden, store issued visible
        if (isRs01) {
            labelEmergency?.visibility = View.GONE
            labelStoreIssued?.visibility = View.VISIBLE
            btnCompile.visibility = View.VISIBLE
            btnCompiledSummary.visibility = View.VISIBLE
            btnSskFile.visibility = View.VISIBLE
            btnMonthlyConsumption.visibility = View.VISIBLE
            btnRollover.visibility = View.VISIBLE
            btnDownload.visibility = View.VISIBLE
        } else {
            labelEmergency?.visibility = View.VISIBLE
            labelStoreIssued?.visibility = View.GONE
            // Non RS-01: hide special buttons
            btnCompile.visibility = View.GONE
            btnCompiledSummary.visibility = View.GONE
            btnSskFile.visibility = View.GONE
            btnMonthlyConsumption.visibility = View.GONE
            btnRollover.visibility = View.GONE
            btnDownload.visibility = View.GONE
        }
    }

    private fun reloadData() {
        dataList = if (currentVehicle.isBlank()) {
            db.getAllMedicines()
        } else {
            // Prefer direct vehicle query if added (see AppDatabase helper below)
            db.getMedicinesForVehicle(currentVehicle)
        }
    }

    private fun applySearchFilter(query: String?) {
        val search = query?.trim().orEmpty().lowercase()
        filteredList.clear()
        val base = dataList
        if (search.isEmpty()) {
            filteredList.addAll(base)
        } else {
            filteredList.addAll(
                base.filter {
                    it.medicineName.lowercase().contains(search)
                            || it.vehicleName.lowercase().contains(search)
                }
            )
        }
        summaryAdapter.notifyDataSetChanged()
    }

    private fun showRolloverConfirm() {
        AlertDialog.Builder(this)
            .setTitle("Global Rollover (ALL Vehicles)")
            .setMessage(
                "Ye action sare vehicles par apply hoga:\n\n" +
                        "Opening = Closing\nConsumption = 0\nEmergency = 0\n\nProceed?"
            )
            .setPositiveButton("YES") { _, _ -> performRollover("") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performRollover(vehicle: String) {
        binding.progressBar.visibility = View.VISIBLE
        val signature = "ROLLOVER|" + if (vehicle.isBlank()) "ALL" else vehicle
        val reuseId = PendingRequestCache.reuseIfSame(signature)

        GoogleSheetsClient.rolloverBalances(
            vehicle = vehicle,
            previousRequestId = reuseId
        ) { success, response, requestIdUsed ->
            binding.progressBar.visibility = View.GONE
            val duplicate = response?.duplicate == true
            if (!success && !duplicate) {
                PendingRequestCache.store(signature, requestIdUsed)
                Toast.makeText(
                    this,
                    "Rollover failed: ${response?.error ?: "unknown"} (Retry will reuse same request)",
                    Toast.LENGTH_LONG
                ).show()
                return@rolloverBalances
            }
            PendingRequestCache.clearIf(signature)

            val rowsToUpdate = if (vehicle.isBlank()) db.getAllMedicines()
            else db.getMedicinesForVehicle(vehicle)

            rowsToUpdate.forEach { item ->
                val newOpening = item.closingBalance
                db.addOrUpdateMedicine(
                    item.vehicleName,
                    item.medicineName,
                    newOpening,
                    "0",
                    "0",
                    item.closingBalance
                )
            }

            reloadData()
            applySearchFilter(binding.searchViewSummary.query?.toString())
            val msg = if (duplicate) "Already rolled over earlier (duplicate prevented)." else "Rollover complete!"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun compileCurrentSheet() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = GoogleSheetApi.getAllCurrentStock()
            binding.progressBar.visibility = View.GONE
            if (!result.isSuccess) {
                Toast.makeText(
                    this@SummaryActivity,
                    "Failed to fetch data: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val list = result.getOrNull() ?: emptyList()
            if (list.isEmpty()) {
                Toast.makeText(this@SummaryActivity, "Koi data nahi mila!", Toast.LENGTH_LONG).show()
                return@launch
            }

            // (Optional) Filter to currentVehicle if you want per-vehicle compile
            // val source = if (currentVehicle.isBlank()) list else list.filter { it.vehicleName.equals(currentVehicle, true) }
            val source = list

            val medicineNameMapping = mapOf(
                "Polymyxin B Sulphate Skin (20 gm)" to "Polymyxin B Sulphate Skin Ointment with lignocaine (20 gm)",
                "Neomycin Sulphate 0.5%" to "Neomycin Sulphate 0.5%, bacitracin zinc"
            )
            fun parseInt(s: String?): Int = s?.toDoubleOrNull()?.toInt() ?: 0

            val grouped = source.groupBy {
                medicineNameMapping[it.medicineName.trim()] ?: it.medicineName.trim()
            }

            val compiledList = grouped.map { (medName, rows) ->
                val totalOpening = rows.sumOf { parseInt(it.openingBalance) }
                val totalConsumption = rows.sumOf { parseInt(it.consumption) }
                val totalEmergency = rows.sumOf { parseInt(it.totalEmergency) }
                val totalStoreIssued = rows.sumOf { parseInt(it.storeIssued) }
                val totalClosing = totalOpening - totalConsumption
                val stockAvailable = rows
                    .filter { it.vehicleName.equals("RS-01", ignoreCase = true) }
                    .map { parseInt(it.stockAvailable) }
                    .firstOrNull() ?: 0

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

            val pseudoDate = "CURRENT"
            db.insertCompiledSummary(pseudoDate, compiledList)

            AlertDialog.Builder(this@SummaryActivity)
                .setTitle("Compiled Summary (All Current Rows)")
                .setMessage(buildString {
                    append("Medicine, Opening, Consumption, Emergency, Closing, Store Issued, Stock Available\n")
                    compiledList.forEach {
                        append("${it.medicineName}, ${it.totalOpening}, ${it.totalConsumption}, ${it.totalEmergency}, ${it.totalClosing}, ${it.totalStoreIssued}, ${it.stockAvailable}\n")
                    }
                })
                .setPositiveButton("Export as CSV") { _, _ ->
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
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, "CSV file share karen..."))
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
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun exportSskFile() {
        val compiledList = db.getAllCompiledSummary()
        val latestDate = compiledList.firstOrNull()?.date
        if (latestDate == null) {
            Toast.makeText(this, "Koi compiled summary nahi mili!", Toast.LENGTH_LONG).show()
            return
        }
        val fileName = "ssk_file_${latestDate}.csv"
        val file = File(getExternalFilesDir(null), fileName)
        val ok = CsvExportUtils.exportSskSelectedMedicinesCsv(file, sskExportOrder, compiledList)
        if (!ok) {
            Toast.makeText(this, "Export Failed!", Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share CSV via"))
        Toast.makeText(this, "SSK file export ho gayi: $fileName", Toast.LENGTH_LONG).show()
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