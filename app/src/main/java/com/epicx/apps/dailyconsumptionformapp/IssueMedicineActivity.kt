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
import com.epicx.apps.dailyconsumptionformapp.formActivityObjects.UploadMenuHelper
import com.epicx.apps.dailyconsumptionformapp.objects.MedicineDialogUtils
import com.epicx.apps.dailyconsumptionformapp.tempStorage.TempRs01DailyStore

class IssueMedicineActivity : AppCompatActivity() {

    private lateinit var spVehicle: Spinner
    private lateinit var tvVehicleStatic: TextView
    private lateinit var etMedicine: EditText
    private lateinit var etQty: EditText
    private lateinit var btnSave: Button
    private lateinit var btnUpload: Button
    private lateinit var btnManage: Button
    private lateinit var tvPendingTitle: TextView
    private lateinit var tvPendingList: TextView
    private lateinit var btnSubmitRs01Consumption: Button
    private lateinit var btnMonthlyFromStore: Button

    private lateinit var issueDb: DBHelper
    private lateinit var mainDb: AppDatabase

    private var selectedVehicle: String = ""
    private var isRs01User: Boolean = false
    private var defaultVehicle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_issue_medicine)

        issueDb = DBHelper(this)
        mainDb = AppDatabase(this)

        defaultVehicle = intent.getStringExtra("defaultVehicle")?.trim().orEmpty()
        isRs01User = defaultVehicle.equals("RS-01", ignoreCase = true)

        bindViews()
        setupVehicleUi()
        setupMedicinePicker()
        setupListeners()
        refreshPendingList()
    }

    private fun bindViews() {
        spVehicle = findViewById(R.id.spVehicle)
        tvVehicleStatic = findViewById(R.id.tvVehicleStatic)
        etMedicine = findViewById(R.id.edit_medicine)
        etQty = findViewById(R.id.etQty)
        btnSave = findViewById(R.id.btnSave)
        btnUpload = findViewById(R.id.btnUpload)
        btnManage = findViewById(R.id.btnManage)
        tvPendingTitle = findViewById(R.id.tvPendingTitle)
        tvPendingList = findViewById(R.id.tvPendingList)
        btnSubmitRs01Consumption = findViewById(R.id.btnSubmitRs01Consumption)
        btnMonthlyFromStore = findViewById(R.id.btnMonthlyFromStore)

        tvPendingList.movementMethod = ScrollingMovementMethod()
        tvPendingList.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            false
        }
        etQty.filters = arrayOf(NumberInputFilter())
    }

    private fun setupVehicleUi() {
        spVehicle.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, vehicleList)

        if (isRs01User) {
            spVehicle.visibility = View.VISIBLE
            tvVehicleStatic.visibility = View.GONE
            if (defaultVehicle.isNotBlank()) {
                val idx = vehicleList.indexOfFirst { it.equals(defaultVehicle, true) }
                if (idx >= 0) spVehicle.setSelection(idx)
            }
            selectedVehicle = (spVehicle.selectedItem as? String)?.trim().orEmpty()
            updateUploadButtonText()

            spVehicle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    selectedVehicle = vehicleList[position]
                    updateUploadButtonText()
                    refreshPendingList()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            btnSubmitRs01Consumption.visibility = View.VISIBLE
            btnMonthlyFromStore.visibility = View.VISIBLE
        } else {
            spVehicle.visibility = View.GONE
            tvVehicleStatic.visibility = View.VISIBLE
            selectedVehicle = when {
                defaultVehicle.isNotBlank() -> defaultVehicle
                vehicleList.isNotEmpty() -> vehicleList.first()
                else -> ""
            }
            tvVehicleStatic.text = selectedVehicle.ifBlank { "Vehicle N/A" }
            updateUploadButtonText()
            btnSubmitRs01Consumption.visibility = View.GONE
            btnMonthlyFromStore.visibility = View.GONE
        }
    }

    private fun updateUploadButtonText() {
        btnUpload.text = "Upload to • $selectedVehicle"
        btnManage.text = "Delete or Edit Pending items"
    }

    private fun setupMedicinePicker() {
        etMedicine.isFocusable = false
        etMedicine.isClickable = true
        etMedicine.setOnClickListener {
            MedicineDialogUtils.showMedicineDialog(this, newMedicineList, etMedicine)
        }
    }

    private fun setupListeners() {
        btnSave.setOnClickListener { onSaveClicked() }
        btnUpload.setOnClickListener { uploadIssuesFlow() }
        btnManage.setOnClickListener { showManageDialog(currentVehicle()) }

        btnSubmitRs01Consumption.setOnClickListener {
            handleDailySubmitLikeFormActivity()
        }

        btnMonthlyFromStore.setOnClickListener {
            if (!isRs01User) {
                toast("Only RS-01 user.")
                return@setOnClickListener
            }
            UploadMenuHelper.Rs01UploadOnMonthlySheet(
                activity = this,
                db = mainDb,
                defaultVehicle = "RS-01",
                getShiftTag = { getShiftTag() }
            )
        }
    }

    private fun handleDailySubmitLikeFormActivity() {
        val vehicleForSubmit = if (isRs01User) "RS-01" else defaultVehicle
        if (vehicleForSubmit.isBlank()) {
            Toast.makeText(this, "Please select vehicle first.", Toast.LENGTH_SHORT).show()
            return
        }

        val all = mainDb.getAllMedicines().filter { it.vehicleName == vehicleForSubmit }
        fun toIntSafe(s: String?): Int = s?.toDoubleOrNull()?.toInt() ?: 0
        val isRs01 = vehicleForSubmit.equals("RS-01", ignoreCase = true)

        val toSend = all.mapNotNull { rec ->
            val cons = toIntSafe(rec.consumption)
            val emerg = toIntSafe(rec.totalEmergency)
            if (cons <= 0 && emerg <= 0) return@mapNotNull null

            if (isRs01) {
                val currentClosing = toIntSafe(rec.closingBalance)
                val mainStoreIssued = toIntSafe(rec.storeIssued)
                GoogleSheetsClient.SubmitItem(
                    medicine = rec.medicineName,
                    consumption = cons,
                    emergency = 0,
                    mainStoreIssued = mainStoreIssued,
                    stockAvailable = currentClosing
                )
            } else {
                GoogleSheetsClient.SubmitItem(
                    medicine = rec.medicineName,
                    consumption = cons,
                    emergency = emerg
                )
            }
        }

        if (toSend.isEmpty()) {
            Toast.makeText(this, "No changes to submit.", Toast.LENGTH_SHORT).show()
            return
        }

        SubmitPreviewDialogHelper.show(
            activity = this,
            vehicle = vehicleForSubmit,
            originalItems = toSend,
            onConfirm = { finalList ->
                if (finalList.isEmpty()) {
                    Toast.makeText(this, "No items left to submit.", Toast.LENGTH_LONG).show()
                } else {
                    performDaySubmit(
                        vehicle = vehicleForSubmit,
                        toSend = finalList
                    )
                }
            },
            onEditPersist = { med, newCons, newEmerg ->
                mainDb.applyEditedPending(
                    vehicle = vehicleForSubmit,
                    medicine = med,
                    newConsumption = newCons,
                    newEmergency = newEmerg
                )
            },
            onDeletePersist = { med ->
                mainDb.revertPendingForMedicine(vehicleForSubmit, med)
            }
        )
    }

    private fun performDaySubmit(
        vehicle: String,
        toSend: List<GoogleSheetsClient.SubmitItem>
    ) {
        val progress = showProgress("Submitting $vehicle...")
        val signature = PendingRequestCache.signatureForConsumption(vehicle, toSend)
        val reuseId = PendingRequestCache.reuseIfSame(signature)

        GoogleSheetsClient.submitConsumption(
            vehicle = vehicle,
            items = toSend,
            previousRequestId = reuseId
        ) { ok, resp, requestIdUsed ->
            progress.dismiss()
            val duplicate = resp?.duplicate == true
            if (!ok && !duplicate) {
                PendingRequestCache.store(signature, requestIdUsed)
                toastLong(resp?.error ?: "Upload failed (retry will reuse).")
                return@submitConsumption
            }
            PendingRequestCache.clearIf(signature)

            if (vehicle.equals("RS-01", true)) {
                val today = TempRs01DailyStore.todayDate()
                toSend.forEach { item ->
                    TempRs01DailyStore.addConsumption(
                        context = this,
                        date = today,
                        medicine = item.medicine,
                        add = item.consumption
                    )
                }
            }

            toSend.forEach { item ->
                mainDb.addOrUpdateMedicine(
                    vehicle = vehicle,
                    medicine = item.medicine,
                    opening = mainDb.getMedicineOpening(vehicle, item.medicine),
                    consumption = "0",
                    emergency = "0",
                    closing = mainDb.getMedicineClosing(vehicle, item.medicine)
                )
            }

            toastLong(
                if (duplicate) "Already submitted earlier."
                else "$vehicle consumption submitted."
            )
        }
    }

    private fun getShiftTag(): String? = null

    /* -------------------------------------------
       SAVE (LOCAL) with outward vs self distinction
     ------------------------------------------- */
    private fun onSaveClicked() {
        val vehicle = currentVehicle()
        val medicine = etMedicine.text?.toString()?.trim().orEmpty()
        val qtyStr = etQty.text?.toString()?.trim().orEmpty()

        if (vehicle.isEmpty()) { toast("Vehicle not set"); return }
        if (medicine.isEmpty()) { toast("Select medicine"); return }
        if (qtyStr.isEmpty()) { etQty.error = "Qty required"; return }
        val qty = qtyStr.toIntOrNull()
        if (qty == null || qty <= 0) { etQty.error = "Qty > 0"; return }

        val isSelfRs01Entry = isRs01User && vehicle.equals("RS-01", ignoreCase = true)
        val isOutwardIssueFromRs01 = isRs01User && !isSelfRs01Entry

        // RS-01 se kisi aur vehicle ko issue: stock validate
        if (isOutwardIssueFromRs01) {
            val rsMed = mainDb.getMedicineRecord("RS-01", medicine)
            val currentClosing = rsMed?.closingBalance?.toIntOrNull() ?: 0
            if (qty > currentClosing) {
                toast("RS-01 stock kam hai (Available: $currentClosing)")
                return
            }
        }

        // Insert issue row (self RS-01 entry bhi record ho jayegi agar aap history rakhna chahte hain)
        issueDb.insertIssue(vehicle, medicine, qty)

        if (isOutwardIssueFromRs01) {
            // Outward: pending adjust (consumption++, closing--)
            if (!adjustRs01Pending(medicine, qty)) {
                toast("Adjustment failed (stock issue)")
                return
            }
        } else if (isSelfRs01Entry) {
            // Self RS-01: sirf stock add (consumption touch nahi -> submit list me nahi aayega)
            addRs01Stock(medicine, qty)
            // NOTE: Agar aap yahan add ke bajaye adjust (plus/minus) chahte hain to input filter me minus allow karein
        }

        val msg = when {
            isOutwardIssueFromRs01 -> "Saved locally & RS-01 updated"
            isSelfRs01Entry -> "Saved (RS-01 stock +$qty)"
            else -> "Saved locally"
        }
        toast(msg)
        etQty.setText("")
        refreshPendingList()
    }

    /* -------------------------------------------
       Outward adjust (consumption add, closing minus)
     ------------------------------------------- */
    private fun adjustRs01Pending(medicine: String, deltaQty: Int): Boolean {
        val rec = mainDb.getMedicineRecord("RS-01", medicine)
        if (rec == null) {
            mainDb.addOrUpdateMedicine(
                vehicle = "RS-01",
                medicine = medicine,
                opening = "0",
                consumption = "0",
                emergency = "0",
                closing = "0"
            )
        }
        val r2 = mainDb.getMedicineRecord("RS-01", medicine)!!
        val opening = r2.openingBalance
        val oldCons = r2.consumption.toIntOrNull() ?: 0
        val oldEmerg = r2.totalEmergency.toIntOrNull() ?: 0
        val oldClosing = r2.closingBalance.toIntOrNull() ?: 0

        if (deltaQty > 0 && deltaQty > oldClosing) return false

        val newCons = (oldCons + deltaQty).coerceAtLeast(0)
        val newClosing = (oldClosing - deltaQty).coerceAtLeast(0)

        mainDb.addOrUpdateMedicine(
            vehicle = "RS-01",
            medicine = medicine,
            opening = opening,
            consumption = newCons.toString(),
            emergency = oldEmerg.toString(),
            closing = newClosing.toString()
        )
        return true
    }

    /* -------------------------------------------
       Self RS-01 stock addition (no consumption impact)
     ------------------------------------------- */
    private fun addRs01Stock(medicine: String, addQty: Int) {
        if (addQty <= 0) return
        val rec = mainDb.getMedicineRecord("RS-01", medicine)
        if (rec == null) {
            // New item: treat opening = 0, closing = addQty
            mainDb.addOrUpdateMedicine(
                vehicle = "RS-01",
                medicine = medicine,
                opening = "0",
                consumption = "0",
                emergency = "0",
                closing = addQty.toString()
            )
            return
        }
        val opening = rec.openingBalance
        val cons = rec.consumption
        val emerg = rec.totalEmergency
        val oldClosing = rec.closingBalance.toIntOrNull() ?: 0
        val newClosing = oldClosing + addQty
        mainDb.addOrUpdateMedicine(
            vehicle = "RS-01",
            medicine = medicine,
            opening = opening,
            consumption = cons,
            emergency = emerg,
            closing = newClosing.toString()
        )
    }

    private fun uploadIssuesFlow() {
        val vehicle = currentVehicle()
        if (vehicle.isEmpty()) { toast("Vehicle not set"); return }

        val unuploaded = issueDb.getUnuploadedByVehicle(vehicle).filterNotNull()
        if (unuploaded.isEmpty()) { toast("No pending items for $vehicle"); return }

        val aggregated = unuploaded
            .groupBy { (it.medicine ?: "").trim() }
            .filterKeys { it.isNotBlank() }
            .map { (med, rows) ->
                val sum = rows.fold(0) { acc, r -> acc + (r.qty ?: 0) }
                GoogleSheetsClient.MedQty(medicine = med, qty = sum)
            }
            .filter { it.qty > 0 }

        if (aggregated.isEmpty()) { toast("Nothing to upload"); return }

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
                    issueDb.markUploaded(ids)

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
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManageDialog(vehicle: String) {
        if (vehicle.isBlank()) { toast("Vehicle not set"); return }
        val rows = issueDb.getUnuploadedByVehicle(vehicle).filterNotNull()
        if (rows.isEmpty()) { toast("Nothing pending."); return }

        val listItems = rows.map { r ->
            val med = (r.medicine ?: "").trim().ifBlank { "(No name)" }
            val qty = r.qty ?: 0
            "$med — $qty"
        }

        val listView = ListView(this).apply {
            adapter = ArrayAdapter(
                this@IssueMedicineActivity,
                android.R.layout.simple_list_item_1,
                listItems
            )
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle("Manage Pending (${rows.size})")
            .setView(listView)
            .setNegativeButton("Close", null)
            .create()

        listView.setOnItemClickListener { _, _, pos, _ ->
            val row = rows[pos]
            val id = row.id
            if (id <= 0) return@setOnItemClickListener
            val oldQty = row.qty
            showEditDialog(id, row.medicine ?: "", oldQty) {
                if (isRs01User) {
                    val updatedRow = issueDb.getUnuploadedByVehicle(vehicle)
                        .filterNotNull()
                        .find { it.id == id }
                    val newQty = updatedRow?.qty ?: oldQty
                    val delta = newQty - oldQty
                    if (delta != 0) {
                        val isSelf = vehicle.equals("RS-01", true)
                        if (isSelf) {
                            if (delta > 0) addRs01Stock(row.medicine ?: "", delta)
                            else {
                                // Optional: negative delta on self edit -> reduce closing
                                adjustRs01ClosingOnly(row.medicine ?: "", delta)
                            }
                        } else {
                            adjustRs01Pending(row.medicine ?: "", delta)
                        }
                    }
                }
                dlg.dismiss()
                showManageDialog(vehicle)
                refreshPendingList()
            }
        }

        listView.setOnItemLongClickListener { _, _, pos, _ ->
            val row = rows[pos]
            val id = row.id
            val oldQty = row.qty
            AlertDialog.Builder(this)
                .setTitle("Delete Entry?")
                .setMessage("Medicine: ${row.medicine}\nQty: ${row.qty}\n\nDelete this pending entry?")
                .setPositiveButton("Delete") { d2, _ ->
                    d2.dismiss()
                    issueDb.deleteIssue(id)
                    if (isRs01User) {
                        val isSelf = vehicle.equals("RS-01", true)
                        if (isSelf) {
                            // Deleting a self-added stock: reduce closing by that qty
                            adjustRs01ClosingOnly(row.medicine ?: "", -oldQty)
                        } else {
                            // Deleting outward issue: revert consumption & restore closing
                            adjustRs01Pending(row.medicine ?: "", -oldQty)
                        }
                    }
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

    // Reduce or add only closing (used for self RS-01 edits/deletes)
    private fun adjustRs01ClosingOnly(medicine: String, delta: Int) {
        if (delta == 0) return
        val rec = mainDb.getMedicineRecord("RS-01", medicine) ?: return
        val opening = rec.openingBalance
        val cons = rec.consumption
        val emerg = rec.totalEmergency
        val oldClosing = rec.closingBalance.toIntOrNull() ?: 0
        val newClosing = (oldClosing + delta).coerceAtLeast(0)
        mainDb.addOrUpdateMedicine(
            vehicle = "RS-01",
            medicine = medicine,
            opening = opening,
            consumption = cons,
            emergency = emerg,
            closing = newClosing.toString()
        )
    }

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
                    issueDb.updateIssueQty(id, newVal)
                    toast("Updated")
                    onDone()
                }
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun currentVehicle(): String =
        if (isRs01User) (spVehicle.selectedItem as? String)?.trim().orEmpty()
        else selectedVehicle

    private fun refreshPendingList() {
        val vehicle = currentVehicle()
        tvPendingTitle.text =
            if (vehicle.isBlank()) "Pending items" else "Pending items • $vehicle"

        if (vehicle.isBlank()) {
            tvPendingList.text = "Select/Set vehicle to see pending items."
            return
        }

        val rows = issueDb.getUnuploadedByVehicle(vehicle).filterNotNull()
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
        btnManage.isEnabled = enabled
        btnSubmitRs01Consumption.isEnabled = enabled
        btnMonthlyFromStore.isEnabled = enabled
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