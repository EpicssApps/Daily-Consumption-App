package com.epicx.apps.dailyconsumptionformapp

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.epicx.apps.dailyconsumptionformapp.FormConstants.sskExportOrder
import com.epicx.apps.dailyconsumptionformapp.databinding.ActivitySummaryBinding
import com.epicx.apps.dailyconsumptionformapp.objects.SummaryEditDialog
import kotlinx.coroutines.launch
import java.io.File
import android.widget.SearchView
import com.epicx.apps.dailyconsumptionformapp.formActivityObjects.PendingRequestCache
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
        } else {
            labelStoreIssued.visibility = View.GONE
            findViewById<TextView>(R.id.btnCompile)?.visibility = View.GONE
            findViewById<TextView>(R.id.btnCompiledSummary)?.visibility = View.GONE
            findViewById<TextView>(R.id.btnSskFile)?.visibility = View.GONE
            findViewById<TextView>(R.id.btnMonthlyConsumption)?.visibility = View.GONE
            findViewById<TextView>(R.id.btnRollover)?.visibility = View.GONE
        }

        dataList = db.getAllMedicines()
        filteredList.addAll(dataList)
        val onlyShowBasicColumns = defaultVehicle == "RS-01"

        summaryAdapter = SummaryAdapter(filteredList, onlyShowBasicColumns) { item ->
            SummaryEditDialog.show(this, item) { updatedItem ->
                fun toIntSafe(s: String?): Int = s?.toDoubleOrNull()?.toInt() ?: 0

                val oldConsumption = toIntSafe(item.consumption)
                val newConsumption = toIntSafe(updatedItem.consumption)

                val oldEmergency = toIntSafe(item.totalEmergency)
                val newEmergency = toIntSafe(updatedItem.totalEmergency)

                val oldClosing = toIntSafe(item.closingBalance)
                val deltaConsumption = newConsumption - oldConsumption

                if (oldClosing == 0 && deltaConsumption > 0) {
                    AlertDialog.Builder(this@SummaryActivity)
                        .setTitle("Stock Zero")
                        .setMessage("Closing balance ZERO hai. Zyada consumption add nahi kar sakte.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@show
                }

                if (deltaConsumption > 0 && deltaConsumption > oldClosing) {
                    AlertDialog.Builder(this@SummaryActivity)
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

                dataList = db.getAllMedicines()
                filterList(binding.searchViewSummary.query.toString())
                Toast.makeText(this, "Changes saved!", Toast.LENGTH_SHORT).show()
            }
        }
        binding.recyclerSummary.layoutManager = LinearLayoutManager(this)
        binding.recyclerSummary.adapter = summaryAdapter

        binding.searchViewSummary.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                filterList(query); return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                filterList(newText); return true
            }
        })

        binding.btnRollover.setOnClickListener { showRolloverConfirm() }

        binding.btnCompile.setOnClickListener {
            binding.progressBar.visibility = View.VISIBLE
            lifecycleScope.launch {
                val result = GoogleSheetApi.getAllCurrentStock()
                binding.progressBar.visibility = View.GONE
                if (result.isSuccess) {
                    val list = result.getOrNull() ?: emptyList()
                    if (list.isEmpty()) {
                        Toast.makeText(this@SummaryActivity, "Koi data nahi mila!", Toast.LENGTH_LONG).show()
                        return@launch
                    }

                    val medicineNameMapping = mapOf(
                        "Polymyxin B Sulphate Skin (20 gm)" to "Polymyxin B Sulphate Skin Ointment with lignocaine (20 gm)",
                        "Neomycin Sulphate 0.5%" to "Neomycin Sulphate 0.5%, bacitracin zinc"
                    )

                    fun parseInt(s: String?): Int = s?.toDoubleOrNull()?.toInt() ?: 0
                    val grouped = list.groupBy {
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

                    val builder = AlertDialog.Builder(this@SummaryActivity)
                    val sb = StringBuilder()
                    sb.append("Medicine, Opening, Consumption, Emergency, Closing, Store Issued, Stock Available\n")
                    for (item in compiledList) {
                        sb.append("${item.medicineName}, ${item.totalOpening}, ${item.totalConsumption}, ${item.totalEmergency}, ${item.totalClosing}, ${item.totalStoreIssued}, ${item.stockAvailable}\n")
                    }
                    builder.setTitle("Compiled Summary (All Current Rows)")
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
                            startActivity(Intent.createChooser(shareIntent, "CSV file share karen..."))
                            Toast.makeText(this@SummaryActivity, "CSV export ho gaya: $fileName", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@SummaryActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                    builder.setNegativeButton("Close", null)
                    builder.show()
                } else {
                    Toast.makeText(
                        this@SummaryActivity,
                        "Failed to fetch data: ${result.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        binding.btnMonthlyConsumption.setOnClickListener {
            MonthlyConsumptionHelper.showMonthPickerAndReport(this@SummaryActivity, binding.progressBar, vehicleName = "RS-01")
        }

        binding.btnCompiledSummary.setOnClickListener {
            startActivity(Intent(this, CompiledSummaryActivity::class.java))
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
            val ok = CsvExportUtils.exportSskSelectedMedicinesCsv(file, sskExportOrder, compiledList)
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

        // Signature for Option A reuse
        val signature = "ROLLOVER|" + if (vehicle.isBlank()) "ALL" else vehicle
        val reuseId = PendingRequestCache.reuseIfSame(signature)

        GoogleSheetsClient.rolloverBalances(
            vehicle = vehicle,
            previousRequestId = reuseId
        ) { success, response, requestIdUsed ->
            binding.progressBar.visibility = View.GONE

            val duplicate = response?.duplicate == true
            if (!success && !duplicate) {
                // Store requestId for retry
                PendingRequestCache.store(signature, requestIdUsed)
                Toast.makeText(
                    this,
                    "Rollover failed: ${response?.error ?: "unknown"} (Retry will reuse same request)",
                    Toast.LENGTH_LONG
                ).show()
                return@rolloverBalances
            }

            // Success OR duplicate
            PendingRequestCache.clearIf(signature)

            // Local DB update:
            // 如果 vehicle blank => ALL
            val rowsToUpdate = if (vehicle.isBlank()) db.getAllMedicines()
            else db.getAllMedicines().filter { it.vehicleName == vehicle }

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

            dataList = db.getAllMedicines()
            filterList(binding.searchViewSummary.query.toString())

            val msg = if (duplicate) "Already rolled over earlier (duplicate prevented)."
            else "Rollover complete!"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun filterList(query: String?) {
        filteredList.clear()
        if (query.isNullOrEmpty()) {
            filteredList.addAll(dataList)
        } else {
            val lower = query.lowercase()
            filteredList.addAll(dataList.filter { it.medicineName.lowercase().contains(lower) })
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
                Toast.makeText(this, "Storage permission required to export file.", Toast.LENGTH_LONG).show()
            }
            pendingExport = null
        }
    }
}