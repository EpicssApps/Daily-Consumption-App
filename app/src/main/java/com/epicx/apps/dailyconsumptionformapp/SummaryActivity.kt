package com.epicx.apps.dailyconsumptionformapp

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
import com.epicx.apps.dailyconsumptionformapp.databinding.ActivitySummaryBinding
import com.epicx.apps.dailyconsumptionformapp.formActivityObjects.PendingRequestCache
import com.epicx.apps.dailyconsumptionformapp.objects.SummaryEditDialog
import com.epicx.apps.dailyconsumptionformapp.summaryActivityObjects.StockCsvExporter
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SummaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySummaryBinding
    private lateinit var db: AppDatabase
    private lateinit var archiveDb: ReportArchiveDatabase

    private var currentVehicle: String = ""
    private var dataList: List<FormData> = emptyList()
    private val filteredList: MutableList<FormData> = mutableListOf()
    private lateinit var summaryAdapter: SummaryAdapter


    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase(this)
        archiveDb = ReportArchiveDatabase(this)

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        currentVehicle = intent.getStringExtra("vehicle")
            ?: prefs.getString("default_vehicle", "") ?: ""

        summaryAdapter = SummaryAdapter(
            list = filteredList,
            onlyShowBasicColumns = currentVehicle.equals("RS-01", true)
        ) { item ->
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

        applyVehicleUiMode()

        reloadData()
        applySearchFilter(null)

        binding.searchViewSummary.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                applySearchFilter(query); return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                applySearchFilter(newText); return true
            }
        })

        // DAILY compile + queue DB accumulate + ARCHIVE snapshot + CSV
        binding.btnDailyConsumption.setOnClickListener {
            setupDailyConsumptionButton()
        }

        binding.btnReportFirstHalf.setOnClickListener {
            val (y, m) = currentYearMonth()
            val intent = Intent(this, CompiledSummaryActivity::class.java).apply {
                putExtra("archive_mode", "first_half")
                putExtra("year", y)
                putExtra("month1", m) // 1-based month
            }
            startActivity(intent)
        }

        // NEW: Second half (16–end) of current month -> open CompiledSummaryActivity in half-month mode
        binding.btnReportSecondHalf.setOnClickListener {
            val (y, m) = currentYearMonth()
            val intent = Intent(this, CompiledSummaryActivity::class.java).apply {
                putExtra("archive_mode", "second_half")
                putExtra("year", y)
                putExtra("month1", m) // 1-based month
            }
            startActivity(intent)
        }

        // NEW: Full monthly report (current month)
        binding.btnReportMonthly.setOnClickListener {
            exportArchiveReportMonthlyPreviousMonth()
        }

        // Replace your btnDownloadCsv click handler with this version to ONLY share the CSV (no "Saved" toast, no local download message).
        binding.btnDownloadCsv.setOnClickListener {
            binding.progressBar.visibility = View.VISIBLE
            lifecycleScope.launch {
                val apiResult = GoogleSheetApi.getAllCurrentStock()
                binding.progressBar.visibility = View.GONE
                if (!apiResult.isSuccess) {
                    Toast.makeText(
                        this@SummaryActivity,
                        "Download fail: ${apiResult.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val list = apiResult.getOrNull().orEmpty()
                if (list.isEmpty()) {
                    Toast.makeText(this@SummaryActivity, "Koi data nahi mila!", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val rows = list.map { fd ->
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

                val csv = buildStockCsv(rows)
                val fileName = "Current_Stock_${todayDate()}.csv"

                // Share from cache (no saved toast, no persistent storage)
                shareCsvFromCache(fileName, csv)
            }
        }

        binding.btnRollover.setOnClickListener { showRolloverConfirm() }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val latest = prefs.getString("default_vehicle", "") ?: ""
        if (!latest.equals(currentVehicle, true)) {
            currentVehicle = latest
            applyVehicleUiMode()
            summaryAdapter = SummaryAdapter(
                list = filteredList,
                onlyShowBasicColumns = currentVehicle.equals("RS-01", true)
            ) { item ->
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
            reloadData()
            applySearchFilter(binding.searchViewSummary.query?.toString())
        }
    }

    private fun applyVehicleUiMode() {
        val labelStoreIssued = findViewById<TextView>(R.id.label_summary_store_issued)
        val btnSskFile = binding.btnDailyConsumption
        val btnRollover = binding.btnRollover
        val btnDownload = binding.btnDownloadCsv

        // NEW buttons
        val btnFirstHalf = binding.btnReportFirstHalf
        val btnSecondHalf = binding.btnReportSecondHalf
        val btnMonthly = binding.btnReportMonthly

        val labelEmergency = findViewById<TextView>(R.id.label_summary_emergency)
        val isRs01 = currentVehicle.equals("RS-01", true)

        if (isRs01) {
            labelEmergency?.visibility = View.GONE
            labelStoreIssued?.visibility = View.VISIBLE
            btnSskFile.visibility = View.VISIBLE
            btnRollover.visibility = View.VISIBLE
            btnDownload.visibility = View.VISIBLE
            btnFirstHalf.visibility = View.VISIBLE
            btnSecondHalf.visibility = View.VISIBLE
            btnMonthly.visibility = View.VISIBLE
        } else {
            labelEmergency?.visibility = View.VISIBLE
            labelStoreIssued?.visibility = View.GONE
            btnSskFile.visibility = View.GONE
            btnRollover.visibility = View.GONE
            btnDownload.visibility = View.GONE
            btnFirstHalf.visibility = View.GONE
            btnSecondHalf.visibility = View.GONE
            btnMonthly.visibility = View.GONE
        }
    }

    private fun reloadData() {
        dataList = if (currentVehicle.isBlank()) {
            db.getAllMedicines()
        } else {
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

    private fun exportArchiveReportMonthlyPreviousMonth() {
        try {
            // Compute previous calendar month
            val cal = java.util.Calendar.getInstance()
            val currentYear = cal.get(java.util.Calendar.YEAR)
            val currentMonth1 = cal.get(java.util.Calendar.MONTH) + 1 // 1..12

            val (targetYear, targetMonth1) = if (currentMonth1 == 1) {
                (currentYear - 1) to 12
            } else {
                currentYear to (currentMonth1 - 1)
            }

            // Read monthly totals from monthly_archive
            val monthly = archiveDb.getMonthlyAggregated(targetYear, targetMonth1)
            if (monthly.isEmpty()) {
                android.widget.Toast.makeText(
                    this,
                    "Monthly archive me data nahi mila (${monthName(targetMonth1)}-${targetYear}).",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return
            }

            // Mapping same as your monthlyConsumptionReport
            val medicineNameMapping = mapOf(
                "Polymyxin B Sulphate Skin (20 gm)" to "Polymyxin B Sulphate Skin Ointment with lignocaine (20 gm)",
                "Neomycin Sulphate 0.5%" to "Neomycin Sulphate",
                "Sterilized Gauze Pieces 10 cm x 10 cm 1 box" to "Sterilized Gauze Pieces 10 cm x 10 cm 1 box having 10 packs of 10 pieces",
                "Cotton Bandages BPC 6.5 cm X 6 meter (2.5 inch)" to "Cotton Bandages BPC 6.5 cm X 6 meter",
                "Cotton Bandages BPC 10 cm X 6 meter (4 inch)" to "Cotton Bandages BPC 10 cm X 6 meter"
            )

            val order = FormConstants.rs01MonthlyConsumptionList
            val allowedSet = order.toSet()

            fun normalizeToExportName(raw: String): String? {
                val name = raw.trim()
                val mapped = medicineNameMapping[name] ?: name
                return if (allowedSet.contains(mapped)) mapped else null
            }

            // Build normalized map
            val exportMap = monthly.mapNotNull { row ->
                val normalized = normalizeToExportName(row.medicineName ?: "")
                if (normalized != null) normalized to row else null
            }.toMap()

            // Filename: RS-01 {MonthName}-{Year}.csv
            val fileName = "RS-01 ${monthName(targetMonth1)}-${targetYear}.csv"
            val file = java.io.File(getExternalFilesDir(null), fileName)

            fun csvEscape(value: String): String {
                return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                    "\"" + value.replace("\"", "\"\"") + "\""
                } else value
            }

            // Write CSV in your order. "StockAvailable" column uses totalClosing (as per your original behavior).
            file.printWriter().use { out ->
                out.println("MedicineName,TotalConsumption,TotalEmergency,StockAvailable")
                for (name in order) {
                    val row = exportMap[name]
                    if (row != null) {
                        out.println("${csvEscape(name)},${row.totalConsumption},${row.totalEmergency},${row.totalClosing}")
                    } else {
                        out.println("${csvEscape(name)},,,")
                    }
                }
            }

            // Share CSV
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(shareIntent, "CSV file share karen..."))
            android.widget.Toast.makeText(this, "CSV export ho gaya: $fileName", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Monthly export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun monthName(month1: Int): String = when (month1) {
        1 -> "January"
        2 -> "February"
        3 -> "March"
        4 -> "April"
        5 -> "May"
        6 -> "June"
        7 -> "July"
        8 -> "August"
        9 -> "September"
        10 -> "October"
        11 -> "November"
        12 -> "December"
        else -> ""
    }

    // Utility to build CSV contents from current stock list
    private fun buildStockCsv(rows: List<StockCsvExporter.StockRow>): String {
        val sb = StringBuilder()
        sb.appendLine("Vehicle,Medicine,Opening,Consumption,Emergency,Closing,StoreIssued,StockAvailable")
        rows.forEach { r ->
            fun esc(s: String?): String {
                val v = s ?: ""
                return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                    "\"" + v.replace("\"", "\"\"") + "\""
                } else v
            }
            sb.appendLine(
                listOf(
                    esc(r.vehicleName),
                    esc(r.medicineName),
                    esc(r.openingBalance),
                    esc(r.consumption),
                    esc(r.totalEmergency),
                    esc(r.closingBalance),
                    esc(r.storeIssued),
                    esc(r.stockAvailable)
                ).joinToString(",")
            )
        }
        return sb.toString()
    }

    // Create a temp CSV in app cache dir and share it (no persistent download)
    private fun shareCsvFromCache(fileName: String, csvContent: String) {
        // Write to internal cache (gets cleaned by system; not shown in File Manager downloads)
        val cacheFile = File(cacheDir, fileName)
        cacheFile.writeText(csvContent)

        val uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            cacheFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "CSV file share karen..."))

        // Optional: schedule deletion after share (best-effort)
        // cacheFile.delete() // Uncomment if you want to delete immediately after launching the share sheet
    }

    private fun currentYearMonth(): Pair<Int, Int> {
        val cal = Calendar.getInstance()
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1 // 1-based
        return y to m
    }

    fun todayDate(): String =
        SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())

    private fun todayIsoDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    // Use the SAME date for DB saves as the report date (previous calendar day).
// This replaces only the date parts inside setupDailyConsumptionButton().

    private fun setupDailyConsumptionButton() {
        binding.btnDailyConsumption.setOnClickListener {
            binding.progressBar.visibility = View.VISIBLE
            lifecycleScope.launch {
                try {
                    // 1) Fetch latest stock from Google Sheets
                    val apiResult = GoogleSheetApi.getAllCurrentStock()
                    if (!apiResult.isSuccess) {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(
                            this@SummaryActivity,
                            "Failed to fetch data: ${apiResult.exceptionOrNull()?.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                    val list = apiResult.getOrNull().orEmpty()
                    if (list.isEmpty()) {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this@SummaryActivity, "Koi data nahi mila!", Toast.LENGTH_LONG).show()
                        return@launch
                    }

                    // 2) Compile (group + sums)
                    val medicineNameMapping = mapOf(
                        "Polymyxin B Sulphate Skin (20 gm)" to "Polymyxin B Sulphate Skin Ointment with lignocaine (20 gm)",
                        "Neomycin Sulphate 0.5%" to "Neomycin Sulphate 0.5%, bacitracin zinc"
                    )
                    fun parseInt(s: String?): Int = s?.toDoubleOrNull()?.toInt() ?: 0

                    val grouped = list.groupBy { r ->
                        medicineNameMapping[r.medicineName.trim()] ?: r.medicineName.trim()
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

                    // Use previous-day dates for BOTH DB and file (report date = previous day)
                    val prevHuman = previousHumanDate()   // dd-MM-yyyy (for compiled_summary + upload_flags + filename)
                    val prevISO = previousIsoDate()       // yyyy-MM-dd (for archive_compiled + monthly_archive)

                    // 3) DAILY GUARD: Only save once per report date (previous day)
                    val alreadySaved = db.hasDailyCompileAdded(prevHuman)
                    if (!alreadySaved) {
                        db.insertOrAccumulateCompiledSummary(prevHuman, compiledList)   // compiled_summary.date = prevHuman
                        archiveDb.insertOrAccumulateSnapshot(prevISO, compiledList)     // archive_compiled.date = prevISO
                        archiveDb.insertOrAccumulateMonthlyTotals(prevISO, compiledList)// monthly_archive(year,month) from prevISO
                        db.markDailyCompileAdded(prevHuman)                              // upload_flags.date = prevHuman
                    } else {
                        Toast.makeText(
                            this@SummaryActivity,
                            "Is report date ka compiled data pehle se saved hai. Ab sirf export hoga.",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    // 4) Export CSV with previous date in file name
                    val fileName = "RS-01_Daily_Consumption_${prevHuman}.csv"
                    val file = java.io.File(getExternalFilesDir(null), fileName)
                    file.printWriter().use { out ->
                        out.println("MedicineName,TotalOpening,TotalConsumption,TotalClosing")
                        for (item in compiledList) {
                            out.println("${item.medicineName},${item.totalOpening},${item.totalConsumption},${item.totalClosing}")
                        }
                    }

                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@SummaryActivity,
                        "${applicationContext.packageName}.fileprovider",
                        file
                    )
                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(android.content.Intent.createChooser(shareIntent, "CSV file share karen..."))

                    Toast.makeText(
                        this@SummaryActivity,
                        "CSV export ho gaya: $fileName",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@SummaryActivity,
                        "Daily compile/export error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                } finally {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    // Helpers for previous-day dates
    private fun previousHumanDate(): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        return java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.US).format(cal.time)
    }

    private fun previousIsoDate(): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
    }
}