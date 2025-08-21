package com.epicx.apps.dailyconsumptionformapp

import android.os.Bundle
import android.text.InputFilter
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.view.MotionEvent
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.epicx.apps.dailyconsumptionformapp.FormConstants.newMedicineList
import com.epicx.apps.dailyconsumptionformapp.FormConstants.vehicleList

class IssueMedicineActivity : AppCompatActivity() {

    private lateinit var spVehicle: Spinner
    private lateinit var spMedicine: Spinner
    private lateinit var etQty: EditText
    private lateinit var btnSave: Button
    private lateinit var btnUpload: Button
    private lateinit var tvPendingTitle: TextView
    private lateinit var tvPendingList: TextView

    private lateinit var db: DBHelper
    private var selectedVehicle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_issue_medicine)

        db = DBHelper(this)

        spVehicle = findViewById(R.id.spVehicle)
        spMedicine = findViewById(R.id.spMedicine)
        etQty = findViewById(R.id.etQty)
        btnSave = findViewById(R.id.btnSave)
        btnUpload = findViewById(R.id.btnUpload)
        tvPendingTitle = findViewById(R.id.tvPendingTitle)
        tvPendingList = findViewById(R.id.tvPendingList)

        // Make inner TextView scrollable
        tvPendingList.movementMethod = ScrollingMovementMethod()
        tvPendingList.setOnTouchListener { v, event ->
            // allow inner view to consume vertical scroll
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            false
        }

        // Optional filter to avoid leading zeros/invalid qty input
        etQty.filters = arrayOf(NumberInputFilter())

        spVehicle.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, vehicleList)
        spMedicine.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, newMedicineList)

        spVehicle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedVehicle = vehicleList[position]
                btnUpload.text = "Upload to Google Sheet • $selectedVehicle"
                refreshPendingList() // refresh when vehicle changes
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSave.setOnClickListener {
            val vehicle = (spVehicle.selectedItem as? String)?.trim().orEmpty()
            val medicine = (spMedicine.selectedItem as? String)?.trim().orEmpty()
            val qtyStr = etQty.text?.toString()?.trim().orEmpty()

            if (vehicle.isEmpty()) {
                toast("Select vehicle")
                return@setOnClickListener
            }
            if (medicine.isEmpty()) {
                toast("Select medicine")
                return@setOnClickListener
            }
            if (qtyStr.isEmpty()) {
                etQty.error = "Qty required"
                return@setOnClickListener
            }
            val qty = qtyStr.toIntOrNull()
            if (qty == null || qty <= 0) {
                etQty.error = "Qty should be > 0"
                return@setOnClickListener
            }

            db.insertIssue(vehicle, medicine, qty)
            toast("Saved locally")
            etQty.setText("")
            refreshPendingList() // reflect newly saved item
        }

        btnUpload.setOnClickListener {
            val vehicle = (spVehicle.selectedItem as? String)?.trim().orEmpty()
            if (vehicle.isEmpty()) {
                toast("Select vehicle")
                return@setOnClickListener
            }

            // DBHelper returns MutableList<IssueItem?> — handle nullables
            val unuploadedNullable = db.getUnuploadedByVehicle(vehicle) // MutableList<IssueItem?>
            val unuploaded: List<IssueItem> = unuploadedNullable.filterNotNull()
            if (unuploaded.isEmpty()) {
                toast("No pending items for $vehicle")
                return@setOnClickListener
            }

            // Aggregate quantities by medicine (null-safe)
            val aggregated: List<GoogleSheetsClient.MedQty> = unuploaded
                .groupBy { (it.medicine ?: "").trim() }
                .filterKeys { it.isNotBlank() }
                .map { (med, rows) ->
                    val sum = rows.fold(0) { acc, r -> acc + (r.qty ?: 0) }
                    GoogleSheetsClient.MedQty(medicine = med, qty = sum)
                }
                .filter { it.qty > 0 }

            if (aggregated.isEmpty()) {
                toast("Nothing to upload")
                return@setOnClickListener
            }

            val progress = showProgress("Uploading to Google Sheet...")
            setUiEnabled(false)

            GoogleSheetsClient.uploadIssues(
                context = this,
                vehicle = vehicle,
                items = aggregated
            ) { success, resp ->
                progress.dismiss()
                setUiEnabled(true)

                if (success) {
                    // DBHelper.markUploaded expects MutableList<Long?>?
                    val ids: MutableList<Long?> = unuploaded.map { it.id as Long? }.toMutableList()
                    db.markUploaded(ids)

                    val updatedCount = resp?.updated?.size ?: 0
                    val notFoundCount = resp?.notFound?.size ?: 0
                    val msg = buildString {
                        append("Uploaded successfully.")
                        append("\nUpdated: $updatedCount")
                        if (notFoundCount > 0) append("\nNot found: $notFoundCount")
                    }
                    toastLong(msg)
                    refreshPendingList() // clear list after upload
                } else {
                    val err = resp?.error ?: "Upload failed. Please try again."
                    toastLong(err)
                }
            }
        }

        // Initial state
        if (vehicleList.isNotEmpty()) {
            selectedVehicle = vehicleList.first()
            refreshPendingList()
        }
    }

    private fun refreshPendingList() {
        val vehicle = (spVehicle.selectedItem as? String)?.trim().orEmpty()
        tvPendingTitle.text = if (vehicle.isBlank()) "Pending items" else "Pending items • $vehicle"

        if (vehicle.isBlank()) {
            tvPendingList.text = "Select vehicle to see pending items."
            return
        }

        val rowsNullable = db.getUnuploadedByVehicle(vehicle)
        val rows = rowsNullable.filterNotNull()
        if (rows.isEmpty()) {
            tvPendingList.text = "No pending items."
            return
        }

        // Aggregate by medicine for a clean summary (same as upload logic)
        val summary = rows
            .groupBy { (it.medicine ?: "").trim() }
            .filterKeys { it.isNotBlank() }
            .map { (med, list) -> med to list.sumOf { it.qty ?: 0 } }
            .sortedBy { it.first.lowercase() }

        val sb = StringBuilder()
        summary.forEachIndexed { idx, (med, qty) ->
            sb.append("${idx + 1}. $med — $qty\n")
        }
        tvPendingList.text = sb.toString().trimEnd()
        // scroll to top every refresh
        tvPendingList.scrollTo(0, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun toastLong(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun setUiEnabled(enabled: Boolean) {
        spVehicle.isEnabled = enabled
        spMedicine.isEnabled = enabled
        etQty.isEnabled = enabled
        btnSave.isEnabled = enabled
        btnUpload.isEnabled = enabled
        // TextViews remain enabled
    }

    private fun showProgress(message: String): AlertDialog {
        val pb = ProgressBar(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 48, 48, 48)
            addView(pb)
            addView(TextView(this@IssueMedicineActivity).apply {
                text = message
                setPadding(32, 0, 0, 0)
            })
        }
        return AlertDialog.Builder(this)
            .setView(container)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private class NumberInputFilter : InputFilter {
        override fun filter(
            source: CharSequence?,
            start: Int,
            end: Int,
            dest: Spanned?,
            dstart: Int,
            dend: Int
        ): CharSequence? {
            val result = (dest?.substring(0, dstart) ?: "") +
                    (source?.substring(start, end) ?: "") +
                    (dest?.substring(dend, dest?.length ?: 0) ?: "")
            if (result.isEmpty()) return null
            // Allow only positive integers
            return if (result.matches(Regex("^[1-9][0-9]*$"))) null else ""
        }
    }
}