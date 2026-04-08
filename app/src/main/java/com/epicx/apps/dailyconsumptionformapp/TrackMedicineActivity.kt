package com.epicx.apps.dailyconsumptionformapp

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.epicx.apps.dailyconsumptionformapp.FormConstants.vehicleList
import com.epicx.apps.dailyconsumptionformapp.objects.PdfShareHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrackMedicineActivity : AppCompatActivity() {

    private lateinit var spVehicle: Spinner
    private lateinit var etIndent: EditText
    private lateinit var btnSearch: Button
    private lateinit var tvResultInfo: TextView
    private lateinit var tvResultList: TextView
    private lateinit var btnSharePdf: Button
    private lateinit var issueDb: DBHelper

    private var currentResults: List<SubmittedIssue> = emptyList()
    private var currentVehicle: String = ""
    private var currentIndent: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_track_medicine)

        issueDb = DBHelper(this)

        spVehicle = findViewById(R.id.spTrackVehicle)
        etIndent = findViewById(R.id.etTrackIndent)
        btnSearch = findViewById(R.id.btnSearch)
        tvResultInfo = findViewById(R.id.tvResultInfo)
        tvResultList = findViewById(R.id.tvResultList)
        btnSharePdf = findViewById(R.id.btnSharePdf)

        // Setup vehicle spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, vehicleList)
        spVehicle.adapter = adapter

        // Pre-select vehicle if passed
        val passedVehicle = intent.getStringExtra("defaultVehicle") ?: ""
        if (passedVehicle.isNotBlank()) {
            val idx = vehicleList.indexOfFirst { it.equals(passedVehicle, true) }
            if (idx >= 0) spVehicle.setSelection(idx)
        }

        btnSearch.setOnClickListener { performSearch() }
        btnSharePdf.setOnClickListener { sharePdf() }
    }

    private fun performSearch() {
        val vehicle = (spVehicle.selectedItem as? String)?.trim().orEmpty()
        val indent = etIndent.text?.toString()?.trim().orEmpty()

        if (vehicle.isBlank()) {
            Toast.makeText(this, "Please select a vehicle.", Toast.LENGTH_SHORT).show()
            return
        }
        if (indent.isBlank()) {
            etIndent.error = "Indent number required"
            etIndent.requestFocus()
            return
        }

        currentVehicle = vehicle
        currentIndent = indent
        currentResults = issueDb.getSubmittedIssues(vehicle, indent)

        if (currentResults.isEmpty()) {
            tvResultInfo.visibility = View.VISIBLE
            tvResultInfo.text = "No records found for $vehicle with Indent# $indent"
            tvResultList.visibility = View.GONE
            btnSharePdf.visibility = View.GONE
            return
        }

        // Show results
        val totalQty = currentResults.sumOf { it.qty }
        val dateStr = if (currentResults.isNotEmpty()) {
            val ts = currentResults.maxOf { it.submittedAt }
            SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.US).format(Date(ts))
        } else ""

        tvResultInfo.visibility = View.VISIBLE
        tvResultInfo.text = "Vehicle: $vehicle  |  Indent: $indent\nDate: $dateStr  |  Items: ${currentResults.size}  |  Total Qty: $totalQty"

        val sb = StringBuilder()
        currentResults.forEachIndexed { idx, item ->
            sb.append("${idx + 1}. ${item.medicine} — ${item.qty}\n")
        }
        tvResultList.visibility = View.VISIBLE
        tvResultList.text = sb.toString().trimEnd()

        btnSharePdf.visibility = View.VISIBLE
    }

    private fun sharePdf() {
        if (currentResults.isEmpty()) {
            Toast.makeText(this, "No data to share.", Toast.LENGTH_SHORT).show()
            return
        }

        val entries = currentResults.map {
            PdfShareHelper.MedicineEntry(medicine = it.medicine, qty = it.qty)
        }
        val timestamp = currentResults.maxOf { it.submittedAt }

        PdfShareHelper.generateAndSharePdf(
            context = this,
            vehicle = currentVehicle,
            indent = currentIndent,
            items = entries,
            timestamp = timestamp
        )
    }
}


