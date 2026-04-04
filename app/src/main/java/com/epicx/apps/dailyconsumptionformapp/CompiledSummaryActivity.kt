package com.epicx.apps.dailyconsumptionformapp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.epicx.apps.dailyconsumptionformapp.databinding.ActivityCompiledSummaryBinding

class CompiledSummaryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCompiledSummaryBinding
    private lateinit var archiveDb: ReportArchiveDatabase
    private lateinit var adapter: CompiledSummaryAdapter

    private var archiveMode: String? = null   // "first_half" | "second_half"
    private var yearArg: Int = -1
    private var monthArg: Int = -1
    private var isFirstHalf: Boolean = true

    private fun formatValue(value: Double): String = value.toInt().toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompiledSummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        archiveDb = ReportArchiveDatabase(this)

        // Require archive mode (first/second half) with year/month
        archiveMode = intent.getStringExtra("archive_mode")
        yearArg = intent.getIntExtra("year", -1)
        monthArg = intent.getIntExtra("month1", -1)

        if (archiveMode == null || yearArg <= 0 || monthArg !in 1..12) {
            Toast.makeText(this, "Invalid archive range. Please open via 15 Days buttons.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        isFirstHalf = archiveMode == "first_half"

        val aggregated = if (isFirstHalf)
            archiveDb.getAggregatedForFirstHalf(yearArg, monthArg)
        else
            archiveDb.getAggregatedForSecondHalf(yearArg, monthArg)

        val label = if (isFirstHalf) "%04d-%02d (1–15)".format(yearArg, monthArg)
        else "%04d-%02d (16–end)".format(yearArg, monthArg)

        var autoId = 1
        val summaryList = aggregated.map { row ->
            CompiledMedicineDataWithId(
                id = autoId++,
                date = label,
                medicineName = row.medicineName,
                totalOpening = row.totalOpening.toDouble(),
                totalConsumption = row.totalConsumption.toDouble(),
                totalEmergency = row.totalEmergency.toDouble(),
                totalClosing = row.totalClosing.toDouble(),
                totalStoreIssued = row.totalStoreIssued.toDouble(),
                stockAvailable = row.stockAvailable.toString()
            )
        }.toMutableList()

        adapter = CompiledSummaryAdapter(
            summaryList,
            onDeleteClicked = { item ->
                // Sirf UI se delete karein, database se nahi
                adapter.removeItem(item)
                Toast.makeText(this, "Item deleted from view. Data archive me safe hai.", Toast.LENGTH_LONG).show()
            },
            onItemClick = { item ->
                val intent = Intent(this, WebViewActivity::class.java)
                intent.putExtra("medicineName", item.medicineName)
                intent.putExtra("date", item.date)
                intent.putExtra("opening", formatValue(item.totalOpening))
                intent.putExtra("consumption", formatValue(item.totalConsumption))
                intent.putExtra("emergency", item.totalEmergency.toInt().toString())
                intent.putExtra("closing", formatValue(item.totalClosing))
                intent.putExtra("storeIssued", formatValue(item.totalStoreIssued))
                intent.putExtra("stockAvailable", item.stockAvailable)
                startActivity(intent)
            }
        )

        binding.recyclerCompiled.layoutManager = LinearLayoutManager(this)
        binding.recyclerCompiled.adapter = adapter

        binding.btnDeleteAll.setOnClickListener {
            // Sirf UI se clear karein, database se nahi
            adapter.clearAll()
            Toast.makeText(this, "All items deleted from view. Data archive me safe hai.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showConfirmDelete(title: String, message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}