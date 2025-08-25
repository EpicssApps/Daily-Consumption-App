package com.epicx.apps.dailyconsumptionformapp

import android.os.Bundle
import android.text.InputFilter
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.epicx.apps.dailyconsumptionformapp.FormConstants.newMedicineList
import com.epicx.apps.dailyconsumptionformapp.FormConstants.vehicleList
import com.epicx.apps.dailyconsumptionformapp.formActivityObjects.PendingRequestCache
import com.epicx.apps.dailyconsumptionformapp.objects.MedicineDialogUtils

class IssueMedicineActivity : AppCompatActivity() {

    private lateinit var spVehicle: Spinner
    private lateinit var tvVehicleStatic: TextView
    private lateinit var etMedicine: EditText
    private lateinit var etQty: EditText
    private lateinit var btnSave: Button
    private lateinit var btnUpload: Button
    private lateinit var btnManage: Button  // NEW
    private lateinit var tvPendingTitle: TextView
    private lateinit var tvPendingList: TextView

    private lateinit var db: DBHelper
    private var selectedVehicle: String = ""
    private var isRs01User: Boolean = false
    private var defaultVehicle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_issue_medicine)

        db = DBHelper(this)

        spVehicle = findViewById(R.id.spVehicle)
        tvVehicleStatic = findViewById(R.id.tvVehicleStatic)
        etMedicine = findViewById(R.id.edit_medicine)
        etQty = findViewById(R.id.etQty)
        btnSave = findViewById(R.id.btnSave)
        btnUpload = findViewById(R.id.btnUpload)
        btnManage = findViewById(R.id.btnManage) // NEW
        tvPendingTitle = findViewById(R.id.tvPendingTitle)
        tvPendingList = findViewById(R.id.tvPendingList)

        defaultVehicle = intent.getStringExtra("defaultVehicle")?.trim().orEmpty()
        isRs01User = defaultVehicle.equals("RS-01", ignoreCase = true)

        tvPendingList.movementMethod = ScrollingMovementMethod()
        tvPendingList.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            false
        }

        etQty.filters = arrayOf(NumberInputFilter())

        spVehicle.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, vehicleList)

        if (isRs01User) {
            spVehicle.visibility = View.VISIBLE
            tvVehicleStatic.visibility = View.GONE

            if (defaultVehicle.isNotBlank()) {
                val idx = vehicleList.indexOfFirst { it.equals(defaultVehicle, ignoreCase = true) }
                if (idx >= 0) spVehicle.setSelection(idx)
            }
            selectedVehicle =
                (spVehicle.selectedItem as? String)?.trim().orEmpty().ifBlank {
                    vehicleList.firstOrNull().orEmpty()
                }
            btnUpload.text = "Upload to • $selectedVehicle"
            btnManage.text = "Delete or Edit Pending items"

            spVehicle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    selectedVehicle = vehicleList[position]
                    btnUpload.text = "Upload to • $selectedVehicle"
                    btnManage.text = "Delete or Edit Pending items"
                    refreshPendingList()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        } else {
            spVehicle.visibility = View.GONE
            tvVehicleStatic.visibility = View.VISIBLE
            selectedVehicle = when {
                defaultVehicle.isNotBlank() -> defaultVehicle
                vehicleList.isNotEmpty() -> vehicleList.first()
                else -> ""
            }
            tvVehicleStatic.text = selectedVehicle.ifBlank { "Vehicle N/A" }
            btnUpload.text = "Upload to • $selectedVehicle"
            btnManage.text = "Delete or Edit Pending items"
        }

        etMedicine.isFocusable = false
        etMedicine.isClickable = true
        etMedicine.setOnClickListener {
            MedicineDialogUtils.showMedicineDialog(
                this,
                newMedicineList,
                etMedicine
            )
        }

        btnSave.setOnClickListener {
            val vehicle = currentVehicle()
            val medicine = etMedicine.text?.toString()?.trim().orEmpty()
            val qtyStr = etQty.text?.toString()?.trim().orEmpty()

            if (vehicle.isEmpty()) {
                toast("Vehicle not set")
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
            refreshPendingList()
        }

        btnUpload.setOnClickListener { uploadIssuesFlow() }

        // NEW: Manage button
        btnManage.setOnClickListener {
            val vehicle = currentVehicle()
            if (vehicle.isBlank()) {
                toast("Vehicle not set")
                return@setOnClickListener
            }
            showManageDialog(vehicle)
        }

        refreshPendingList()
    }

    private fun uploadIssuesFlow() {
        val vehicle = currentVehicle()
        if (vehicle.isEmpty()) {
            toast("Vehicle not set")
            return
        }
        val unuploadedNullable = db.getUnuploadedByVehicle(vehicle)
        val unuploaded = unuploadedNullable.filterNotNull()
        if (unuploaded.isEmpty()) {
            toast("No pending items for $vehicle")
            return
        }

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
            return
        }

        val totalItems = aggregated.size
        val totalQty = aggregated.sumOf { it.qty }
        val previewLimit = 8
        val preview = aggregated
            .sortedBy { it.medicine.lowercase() }
            .take(previewLimit)
            .joinToString("\n") { "• ${it.medicine} — ${it.qty}" }
        val extra = if (totalItems > previewLimit) "\n...and ${totalItems - previewLimit} more." else ""

        val message = buildString {
            append("Vehicle: $vehicle\n")
            append("Unique medicines: $totalItems\n")
            append("Total quantity: $totalQty\n\n")
            append("Preview:\n$preview$extra\n\n")
            append("Upload now?")
        }

        AlertDialog.Builder(this)
            .setTitle("Confirm Upload")
            .setMessage(message)
            .setPositiveButton("Upload") { d, _ ->
                d.dismiss()
                val progress = showProgress("Uploading...")
                setUiEnabled(false)

                val signature = PendingRequestCache.signatureForIssue(vehicle, aggregated)
                val reuseId = PendingRequestCache.reuseIfSame(signature)

                GoogleSheetsClient.uploadIssues(
                    context = this,
                    vehicle = vehicle,
                    items = aggregated,
                    previousRequestId = reuseId
                ) { success, resp, requestIdUsed ->
                    progress.dismiss()
                    setUiEnabled(true)

                    val duplicate = resp?.duplicate == true
                    if (!success && !duplicate) {
                        PendingRequestCache.store(signature, requestIdUsed)
                        toastLong(resp?.error ?: "Upload failed. Retry will reuse same request.")
                        return@uploadIssues
                    }
                    PendingRequestCache.clearIf(signature)

                    val ids: MutableList<Long?> = unuploaded.map { it.id as Long? }.toMutableList()
                    db.markUploaded(ids)

                    val updatedCount = resp?.updated?.size ?: 0
                    val notFoundCount = resp?.notFound?.size ?: 0
                    val msg = buildString {
                        if (duplicate) append("Already uploaded earlier.\n") else append("Uploaded successfully.\n")
                        append("Updated: $updatedCount")
                        if (notFoundCount > 0) append("\nNot found: $notFoundCount")
                    }
                    toastLong(msg)
                    refreshPendingList()
                }
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }

    // NEW: Manage raw (unaggregated) pending rows
    private fun showManageDialog(vehicle: String) {
        val rows = db.getUnuploadedByVehicle(vehicle).filterNotNull()
        if (rows.isEmpty()) {
            toast("Nothing pending.")
            return
        }

        val listItems = rows.map { r ->
            val med = (r.medicine ?: "").trim().ifBlank { "(No name)" }
            val qty = r.qty ?: 0
            "$med — $qty"
        }

        val listView = ListView(this).apply {
            adapter = ArrayAdapter(this@IssueMedicineActivity, android.R.layout.simple_list_item_1, listItems)
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle("Manage Pending (${rows.size})")
            .setView(listView)
            .setNegativeButton("Close", null)
            .create()

        listView.setOnItemClickListener { _, _, pos, _ ->
            val row = rows[pos]
            val id = row.id ?: return@setOnItemClickListener
            showEditDialog(id, row.medicine ?: "", row.qty ?: 0) {
                dlg.dismiss()
                showManageDialog(vehicle)
                refreshPendingList()
            }
        }

        listView.setOnItemLongClickListener { _, _, pos, _ ->
            val row = rows[pos]
            val id = row.id ?: return@setOnItemLongClickListener true
            AlertDialog.Builder(this)
                .setTitle("Delete Entry?")
                .setMessage("Medicine: ${row.medicine}\nQty: ${row.qty}\n\nDelete this pending entry?")
                .setPositiveButton("Delete") { d2, _ ->
                    d2.dismiss()
                    db.deleteIssue(id)
                    toast("Deleted")
                    dlg.dismiss()
                    showManageDialog(vehicle)
                    refreshPendingList()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        dlg.show()
    }

    // NEW: Single row quantity edit
    private fun showEditDialog(id: Long, medicine: String, oldQty: Int, onDone: () -> Unit) {
        val input = EditText(this).apply {
            setText(oldQty.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(NumberInputFilter())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Edit Quantity")
            .setMessage(medicine)
            .setView(input)
            .setPositiveButton("Save") { d, _ ->
                val newVal = input.text.toString().toIntOrNull()
                if (newVal == null || newVal <= 0) {
                    toast("Invalid qty")
                } else {
                    db.updateIssueQty(id, newVal)
                    toast("Updated")
                    onDone()
                }
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun currentVehicle(): String =
        if (isRs01User) (spVehicle.selectedItem as? String)?.trim().orEmpty() else selectedVehicle

    private fun refreshPendingList() {
        val vehicle = currentVehicle()
        tvPendingTitle.text =
            if (vehicle.isBlank()) "Pending items" else "Pending items • $vehicle"

        if (vehicle.isBlank()) {
            tvPendingList.text = "Select/Set vehicle to see pending items."
            return
        }

        val rows = db.getUnuploadedByVehicle(vehicle).filterNotNull()
        if (rows.isEmpty()) {
            tvPendingList.text = "No pending items."
            return
        }

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
        tvPendingList.scrollTo(0, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun toastLong(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun setUiEnabled(enabled: Boolean) {
        if (isRs01User) spVehicle.isEnabled = enabled
        etMedicine.isEnabled = enabled
        etQty.isEnabled = enabled
        btnSave.isEnabled = enabled
        btnUpload.isEnabled = enabled
        btnManage.isEnabled = enabled // NEW
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
                    (dest?.substring(dend, dest.length) ?: "")
            if (result.isEmpty()) return null
            return if (result.matches(Regex("^[1-9][0-9]*$"))) null else ""
        }
    }
}