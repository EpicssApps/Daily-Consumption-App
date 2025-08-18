@file:Suppress("SetTextI18n")
package com.epicx.apps.dailyconsumptionformapp.objects

import com.epicx.apps.dailyconsumptionformapp.GoogleSheetApi
import com.epicx.apps.dailyconsumptionformapp.FormConstants
import android.app.DatePickerDialog
import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object MonthlyConsumptionHelper {

    data class Row(
        val medicineName: String,
        val totalConsumption: Double,
        val stockBalance: Double
    )

    private fun cleanDouble(s: String?): Double {
        if (s == null) return 0.0
        val cleaned = s.replace("\u00A0", "").replace(",", "").trim()
        return cleaned.toDoubleOrNull() ?: 0.0
    }

    private fun keyOf(name: String): String {
        return name.lowercase(Locale.US)
            .replace("\u00A0", " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun showMonthPickerAndReport(
        activity: AppCompatActivity,
        progressView: View,
        vehicleName: String = "RS-01"
    ) {
        val now = Calendar.getInstance()
        DatePickerDialog(
            activity,
            { _, year, month0, _ ->
                // Selected month range
                val startCal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month0)
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val endCal = (startCal.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                }
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val monthLabel = SimpleDateFormat("yyyy-MM", Locale.US).format(startCal.time)

                progressView.visibility = View.VISIBLE
                activity.lifecycleScope.launchWhenStarted {
                    try {
                        // Heavy work off main thread
                        val rows: List<Row> = withContext(Dispatchers.IO) {
                            // Build date list for the month
                            val dates = mutableListOf<String>()
                            val cursor = startCal.clone() as Calendar
                            while (!cursor.after(endCal)) {
                                dates.add(sdf.format(cursor.time))
                                cursor.add(Calendar.DAY_OF_MONTH, 1)
                            }

                            // Limit parallel calls to avoid hitting quotas
                            val semaphore = Semaphore(permits = 4)

                            // Fetch all days (limited concurrency)
                            val results = coroutineScope {
                                dates.map { dateStr ->
                                    async {
                                        semaphore.withPermit {
                                            val res = GoogleSheetApi.getAllForDateOfRS01(dateStr)
                                            dateStr to res
                                        }
                                    }
                                }.awaitAll()
                            }.sortedBy { it.first } // sort by date ascending

                            // Aggregation structures
                            val sumConsumptionByKey = mutableMapOf<String, Double>()
                            var latestDateWithData: String? = null
                            val closingOnLatestDateByKey = mutableMapOf<String, Double>()
                            val displayNameByKey = mutableMapOf<String, String>()

                            // Process results
                            for ((dateStr, result) in results) {
                                if (result.isSuccess) {
                                    val rowsForDate = result.getOrNull().orEmpty()
                                    val rsRows = rowsForDate.filter {
                                        it.vehicleName.trim().equals(vehicleName, ignoreCase = true)
                                    }
                                    if (rsRows.isNotEmpty()) {
                                        // Monthly sum
                                        for (r in rsRows) {
                                            val medName = r.medicineName.trim()
                                            val key = keyOf(medName)
                                            val cons = cleanDouble(r.consumption)
                                            sumConsumptionByKey[key] = (sumConsumptionByKey[key] ?: 0.0) + cons
                                            if (displayNameByKey[key].isNullOrEmpty()) {
                                                displayNameByKey[key] = medName
                                            }
                                        }
                                        // Track latest available date with closings
                                        if (latestDateWithData == null || dateStr > latestDateWithData!!) {
                                            latestDateWithData = dateStr
                                            closingOnLatestDateByKey.clear()
                                            for (r in rsRows) {
                                                val medName = r.medicineName.trim()
                                                val key = keyOf(medName)
                                                val closing = cleanDouble(r.closingBalance)
                                                closingOnLatestDateByKey[key] = closing
                                                displayNameByKey[key] = medName // prefer latest label
                                            }
                                        }
                                    }
                                }
                            }

                            if (sumConsumptionByKey.isEmpty() && closingOnLatestDateByKey.isEmpty()) {
                                emptyList()
                            } else {
                                val allKeys = (sumConsumptionByKey.keys + closingOnLatestDateByKey.keys).toSortedSet()
                                allKeys.map { key ->
                                    val name = displayNameByKey[key] ?: key
                                    val sumCons = sumConsumptionByKey[key] ?: 0.0
                                    val lastClosing = closingOnLatestDateByKey[key] ?: 0.0
                                    val totalConsOut = if (sumCons == 0.0) 0.0 else abs(sumCons - lastClosing)
                                    Row(name, totalConsOut, lastClosing)
                                }.sortedBy { it.medicineName.lowercase(Locale.ROOT) }
                            }
                        }

                        if (rows.isEmpty()) {
                            Toast.makeText(activity, "Is month ka data nahi mila!", Toast.LENGTH_LONG).show()
                            return@launchWhenStarted
                        }

                        showDialogAndShare(activity, rows, monthLabel)
                    } finally {
                        progressView.visibility = View.GONE
                    }
                }
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            1 // day ignored
        ).apply {
            setTitle("Select Month & Year")
        }.show()
    }


    private fun showDialogAndShare(
        activity: AppCompatActivity,
        rows: List<Row>,
        monthLabel: String
    ) {
        // Arrange output rows according to monthlyOrderConsumptionList order
        val exportOrder = FormConstants.monthlyOrderConsumptionList
        val orderedRows = exportOrder.map { medName ->
            rows.find { it.medicineName.trim().equals(medName.trim(), ignoreCase = true) }
                ?: Row(medName, 0.0, 0.0)
        }

        // Dialog output
        val builder = AlertDialog.Builder(activity)
        val sb = StringBuilder()
        sb.append("Medicine, totalConsumption, Stock Balance\n")
        orderedRows.forEach { r ->
            sb.append("${r.medicineName}, ${r.totalConsumption}, ${r.stockBalance}\n")
        }
        builder.setTitle("Monthly Summary ($monthLabel)")
        builder.setMessage(sb.toString())
        builder.setPositiveButton("Share CSV") { _, _ ->
            try {
                val fileName = "RS-01_monthly_consumption_${monthLabel}.csv"
                val file = File(activity.getExternalFilesDir(null), fileName)
                file.printWriter().use { out ->
                    out.println("MedicineName,totalConsumption,Stock Balance")
                    orderedRows.forEach { r ->
                        out.println("${r.medicineName},${r.totalConsumption},${r.stockBalance}")
                    }
                }
                val uri = FileProvider.getUriForFile(
                    activity,
                    "${activity.applicationContext.packageName}.fileprovider",
                    file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(shareIntent, "Share Monthly CSV"))
                Toast.makeText(activity, "CSV ready: $fileName", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(activity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        builder.setNegativeButton("Close", null)
        builder.show()
    }
}