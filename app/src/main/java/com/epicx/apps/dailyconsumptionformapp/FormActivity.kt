package com.epicx.apps.dailyconsumptionformapp

import CheckInternetConnection.observeNetworkConnectivity
import androidx.appcompat.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.epicx.apps.dailyconsumptionformapp.databinding.ActivityFormBinding
import com.epicx.apps.dailyconsumptionformapp.formActivityObjects.EmergencyVisibilityHelper
import com.epicx.apps.dailyconsumptionformapp.formActivityObjects.MedicineSubmitHelper
import com.epicx.apps.dailyconsumptionformapp.formActivityObjects.StoreIssuedHelper
import com.epicx.apps.dailyconsumptionformapp.formActivityObjects.SyncFromServerHelper
import com.epicx.apps.dailyconsumptionformapp.formActivityObjects.UploadMenuHelper
import com.epicx.apps.dailyconsumptionformapp.formActivityObjects.VehicleSelectHelper
import com.epicx.apps.dailyconsumptionformapp.objects.MedicineDialogUtils
import com.epicx.apps.dailyconsumptionformapp.objects.MedicineStockUtils
import com.epicx.apps.dailyconsumptionformapp.objects.NetworkMonitor
import com.epicx.apps.dailyconsumptionformapp.objects.RollOverUtils
import com.epicx.apps.dailyconsumptionformapp.objects.SnackbarManager.snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import java.io.IOException
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.core.content.edit
import com.epicx.apps.dailyconsumptionformapp.formActivityObjects.PendingRequestCache
import com.epicx.apps.dailyconsumptionformapp.tempStorage.TempRs01DailyStore

class FormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFormBinding
    private val vehicleList = FormConstants.vehicleList
    private val newMedicineList = FormConstants.newMedicineList
    private lateinit var db: AppDatabase
    private var alertDialog: AlertDialog? = null

    private lateinit var defaultVehicle: String
    private lateinit var layoutStoreIssued: LinearLayout

    // Balances
    private var lastLoadedClosingBalance: Int = 0
    private var lastLoadedOpeningBalance: Int = 0

    // App version helpers
    private var appVersionName: String? = null
    private var executor: ExecutorService? = null
    private var handler: Handler? = null

    // Sync control
    private var syncBlockDialog: AlertDialog? = null
    private var currentNetworkConnected: Boolean = false
    private var initialSyncTried: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        installSplashScreen()

        // Hold splash until we set isFormActivityReady = true
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    return if (isFormActivityReady) {
                        content.viewTreeObserver.removeOnPreDrawListener(this)
                        true
                    } else {
                        false
                    }
                }
            }
        )

        binding = ActivityFormBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase(this)
        RollOverUtils.rollOverBalancesIfDateChanged(this, db)
        executor = Executors.newSingleThreadExecutor()
        handler = Handler(Looper.getMainLooper())

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        defaultVehicle = prefs.getString("default_vehicle", null) ?: ""

        layoutStoreIssued = findViewById(R.id.layout_store_issued)
        val vehicleSpinner = findViewById<Spinner>(R.id.spinner_vehicle)
        val medicineEdit = findViewById<EditText>(R.id.edit_medicine)
        val textOpening = findViewById<TextView>(R.id.text_opening)
        val textClosing = findViewById<TextView>(R.id.text_closing)
        val editConsumption = findViewById<EditText>(R.id.edit_consumption)
        val editEmergency = findViewById<EditText>(R.id.edit_total_emergency)
        val errorText = findViewById<TextView>(R.id.text_error)
        val btnSubmit = findViewById<Button>(R.id.btn_submit)
        val btnSummary = findViewById<Button>(R.id.btn_summary)
        val btnSubmitDay = findViewById<Button>(R.id.btn_submit_day)
        val textEmergencyLabel = findViewById<TextView>(R.id.text_emergency_label)
        val textStoreIssued = findViewById<TextView>(R.id.text_store_issued)
        val issueToVehicles = findViewById<Button>(R.id.btnIssue)
        val btnSendToMonthly = findViewById<Button>(R.id.btnSendToMonthly)

        val vehicleAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, vehicleList)
        vehicleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        vehicleSpinner.adapter = vehicleAdapter

        NetworkMonitor.init(applicationContext)

        getAppVersionName()
        checkUpdate()

        // One-time deletion example
        val bandageKey = "bandage_deleted_once"
        if (!prefs.getBoolean(bandageKey, false)) {
            val allVehicles = FormConstants.vehicleList
            for (vehicle in allVehicles) {
                db.deleteMedicineByName(vehicle, "Cotton Bandages BPC 6.5 cm X 6 meter (2.5\")")
            }
            prefs.edit { putBoolean(bandageKey, true) }
        }

        // Lightweight connectivity (just sets flag)
        NetworkMonitor.observe(this) { isConnected ->
            currentNetworkConnected = isConnected
            if (!isConnected) {
                if (alertDialog?.isShowing != true) {
                    // optional simple alert if you want
                }
            } else {
                alertDialog?.dismiss()
            }
            enableRetryIfPossible()
        }

        val snackbar = Snackbar.make(
            binding.root,
            "Weak or No internet connection",
            Snackbar.LENGTH_INDEFINITE
        )
        snackbar(snackbar)

        // Strong connectivity observer (validated internet)
        lifecycleScope.launch {
            observeNetworkConnectivity(snackbar, applicationContext).collect { isConnected ->
                currentNetworkConnected = isConnected
                if (isConnected) {
                    snackbar.dismiss()
                    enableRetryIfPossible()
                } else {
                    snackbar.show()
                    if (!isFormActivityReady && initialSyncTried) {
                        isFormActivityReady = true
                    }
                    if (initialSyncTried && !isDataFetchSuccessfull) {
                        showSyncRequiredDialog()
                    }
                }
            }
        }

        btnSubmitDay.setOnClickListener {
            if (defaultVehicle.isBlank()) {
                Toast.makeText(this, "Please select vehicle first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val all = db.getAllMedicines().filter { it.vehicleName == defaultVehicle }
            fun toIntSafe(s: String?): Int = s?.toDoubleOrNull()?.toInt() ?: 0
            val isRs01 = defaultVehicle.equals("RS-01", ignoreCase = true)

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
                return@setOnClickListener
            }

            SubmitPreviewDialogHelper.show(
                activity = this,
                vehicle = defaultVehicle,
                originalItems = toSend,
                onConfirm = { finalList ->
                    if (finalList.isEmpty()) {
                        Toast.makeText(this, "No items left to submit.", Toast.LENGTH_LONG).show()
                    } else {
                        performDaySubmit(
                            vehicle = defaultVehicle,
                            toSend = finalList,
                            medicineEdit = findViewById(R.id.edit_medicine),
                            textOpening = findViewById(R.id.text_opening),
                            textClosing = findViewById(R.id.text_closing),
                            editConsumption = findViewById(R.id.edit_consumption),
                            editEmergency = findViewById(R.id.edit_total_emergency)
                        )
                    }
                },
                onEditPersist = { med, newCons, newEmerg ->
                    db.applyEditedPending(
                        vehicle = defaultVehicle,
                        medicine = med,
                        newConsumption = newCons,
                        newEmergency = newEmerg
                    )
                },
                onDeletePersist = { med ->
                    db.revertPendingForMedicine(defaultVehicle, med)
                }
            )
        }

        btnSendToMonthly.setOnClickListener {
            UploadMenuHelper.handleRs01Upload(activity = this, db = db, defaultVehicle = defaultVehicle, getShiftTag = { getShiftTag() })
        }

        issueToVehicles.setOnClickListener {
            val intent = Intent(this, IssueMedicineActivity::class.java)
            intent.putExtra("defaultVehicle", defaultVehicle)
            startActivity(intent)
        }

        if (defaultVehicle.isBlank()) {
            isFormActivityReady = true
            VehicleSelectHelper.showDialog(
                activity = this,
                prefs = prefs,
                vehicleList = vehicleList,
                vehicleSpinner = vehicleSpinner,
                db = db,
                editEmergency = editEmergency,
                textEmergencyLabel = textEmergencyLabel,
                layoutStoreIssued = layoutStoreIssued,
                issueToVehicles = issueToVehicles,
                onVehicleSelected = { selected -> defaultVehicle = selected },
                invalidateOptionsMenu = { invalidateOptionsMenu() },
                onAfterSelection = { selected ->
                    db.prepopulateMedicinesForVehicle(selected, newMedicineList)
                    EmergencyVisibilityHelper.update(
                        editEmergency = editEmergency,
                        textEmergencyLabel = textEmergencyLabel,
                        vehicle = defaultVehicle,
                        layoutStoreIssued = layoutStoreIssued,
                        issueToVehicles = issueToVehicles,
                        btnSendToMonthly = btnSendToMonthly
                    )
                    attemptInitialSync(showLoading = true)
                }
            )
        } else {
            val index = vehicleList.indexOf(defaultVehicle)
            vehicleSpinner.setSelection(if (index >= 0) index else 0)
            vehicleSpinner.isEnabled = false
            db.prepopulateMedicinesForVehicle(defaultVehicle, newMedicineList)
            EmergencyVisibilityHelper.update(
                editEmergency = editEmergency,
                textEmergencyLabel = textEmergencyLabel,
                vehicle = defaultVehicle,
                layoutStoreIssued = layoutStoreIssued,
                issueToVehicles = issueToVehicles,
                btnSendToMonthly = btnSendToMonthly
            )
            attemptInitialSync(showLoading = false)
        }

        medicineEdit.isFocusable = false
        medicineEdit.setOnClickListener {
            MedicineDialogUtils.showMedicineDialog(this, newMedicineList, medicineEdit)
        }

        fun loadStoreIssued(medicine: String) {
            val data = db.getAllMedicines()
                .find { it.vehicleName == defaultVehicle && it.medicineName == medicine }
            textStoreIssued.text = formatMedValue(medicine, data?.storeIssued)
        }

        medicineEdit.addTextChangedListener {
            val medicineName = medicineEdit.text.toString()
            if (medicineName.isNotBlank()) {
                MedicineStockUtils.loadMedicineStock(
                    db = db,
                    vehicleName = defaultVehicle,
                    medicineName = medicineName,
                    textOpening = textOpening,
                    textClosing = textClosing,
                    editConsumption = editConsumption,
                    editEmergency = editEmergency,
                    formatMedValue = ::formatMedValue,
                    lastLoadedOpeningBalanceSetter = { lastLoadedOpeningBalance = it },
                    lastLoadedClosingBalanceSetter = { lastLoadedClosingBalance = it }
                )
                loadStoreIssued(medicineName)
            }
        }

        textStoreIssued.setOnClickListener {
            StoreIssuedHelper.showDialog(
                activity = this,
                defaultVehicle = defaultVehicle,
                medicineEdit = medicineEdit,
                textStoreIssued = textStoreIssued
            ) { vehicle, med, value ->
                db.updateStoreIssued(vehicle, med, value)
            }
        }

        editConsumption.addTextChangedListener {
            val consumption = editConsumption.text.toString().toIntOrNull() ?: 0
            val baseClosing = lastLoadedClosingBalance
            val newClosing = (baseClosing - consumption).coerceAtLeast(0)
            val medicineName = medicineEdit.text.toString()
            textClosing.text = formatMedValue(medicineName, newClosing.toString())
        }

        btnSubmit.setOnClickListener {
            // ADDED: Pre-check only consumption vs lastLoadedClosingBalance
            val consVal = editConsumption.text.toString().trim().toIntOrNull() ?: 0
            if (consVal > lastLoadedClosingBalance) {
                MaterialAlertDialogBuilder(this, R.style.AlertDialogTheme)
                    .setTitle("Insufficient Balance")
                    .setMessage(
                        "Current Closing Balance: $lastLoadedClosingBalance\n" +
                                "Entered Consumption: $consVal\n\n" +
                                "Itna stock available nahi. Pehle balance barhaen ya consumption kam karein."
                    )
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }
            // END ADDED

            MedicineSubmitHelper.handleSubmit(
                activity = this,
                defaultVehicle = defaultVehicle,
                medicineEdit = medicineEdit,
                textOpening = textOpening,
                editConsumption = editConsumption,
                editEmergency = editEmergency,
                textClosing = textClosing,
                errorText = errorText,
                getAllMedicines = {
                    db.getAllMedicines().map {
                        MedicineSubmitHelper.DbMedRecord(
                            vehicleName = it.vehicleName,
                            medicineName = it.medicineName,
                            closingBalance = it.closingBalance,
                            consumption = it.consumption,
                            totalEmergency = it.totalEmergency
                        )
                    }
                },
                addOrUpdateMedicine = { v, m, o, c, e, cl ->
                    db.addOrUpdateMedicine(v, m, o, c, e, cl)
                },
                formatMedValue = { med, value -> formatMedValue(med, value) },
                onClosingRecalculated = { value -> lastLoadedClosingBalance = value }
            )
        }

        btnSummary.setOnClickListener {
            val intent = Intent(this, SummaryActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    private fun isDeviceOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return hasInternet && validated
    }

    private fun attemptInitialSync(showLoading: Boolean) {
        if (defaultVehicle.isBlank()) return
        initialSyncTried = true

        // NEW: Skip server fetch if there are local unsynced consumption/emergency changes
        if (hasLocalUnsyncedChanges(defaultVehicle)) {
            // Mark as ready so splash release ho jaye
            isFormActivityReady = true
            isDataFetchSuccessfull = true  // treat as “ok” so warning dialog na aaye
            return
        }

        val onlineNow = isDeviceOnline()
        if (!onlineNow) {
            if (!isFormActivityReady) isFormActivityReady = true
            showSyncRequiredDialog()
            return
        }

        SyncFromServerHelper.syncFromServerForVehicle(
            activity = this,
            db = db,
            vehicle = defaultVehicle,
            formatMedValue = ::formatMedValue,
            lastLoadedOpeningBalanceSetter = { lastLoadedOpeningBalance = it },
            lastLoadedClosingBalanceSetter = { lastLoadedClosingBalance = it },
            showLoading = showLoading,
            onFailure = {
                if (!isFormActivityReady) isFormActivityReady = true
                showSyncRequiredDialog()
            },
            onSuccess = {
                syncBlockDialog?.dismiss()
            }
        )
    }

    private fun showSyncRequiredDialog() {
        if (isDeviceOnline()) {
            attemptInitialSync(showLoading = true)
            return
        }
        if (isFinishing || isDestroyed) return
        if (syncBlockDialog?.isShowing == true) {
            enableRetryIfPossible()
            return
        }

        syncBlockDialog = MaterialAlertDialogBuilder(this, R.style.AlertDialogTheme)
            .setIcon(R.drawable.ic_update)
            .setTitle("Internet Required")
            .setMessage(
                "Aap ka internet weak ya disconnect hai. Online data fetch nahi ho saka. " +
                        "Internet theek karke Retry dabayen. Is data ke baghair app usable nahi."
            )
            .setCancelable(false)
            .setPositiveButton("Retry", null)
            .create()

        syncBlockDialog?.setOnShowListener {
            val btn = syncBlockDialog!!.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.isEnabled = isDeviceOnline() || currentNetworkConnected
            btn.setOnClickListener {
                btn.isEnabled = false
                attemptInitialSync(showLoading = true)
            }
        }
        syncBlockDialog?.show()
    }

    private fun enableRetryIfPossible() {
        val btn = syncBlockDialog?.getButton(AlertDialog.BUTTON_POSITIVE)
        btn?.isEnabled = isDeviceOnline() || currentNetworkConnected
    }

    private fun checkUpdate() {
        var appVersion: String?
        executor?.execute {
            try {
                val document =
                    Jsoup.connect("https://sites.google.com/view/dailyconsumption/dailyconumption")
                        .get()
                val elements = document.getElementsByClass("C9DxTc")
                appVersion = elements[2].text()
                val isUpdateAvailable = elements[4].text()
                val updateInfo = elements[6].text()
                handler?.post {
                    if (isUpdateAvailable == "true") {
                        if (appVersionName != appVersion) {
                            goToUpdate(appVersion!!, updateInfo)
                        }
                    }
                }
            } catch (_: IOException) {
                handler?.post { }
            }
        }
    }

    private fun goToUpdate(appVersion: String, updateInfo: String) {
        MaterialAlertDialogBuilder(this, R.style.AlertDialogTheme)
            .setIcon(R.drawable.ic_update)
            .setTitle(resources.getString(R.string.Update))
            .setMessage(updateInfo)
            .setCancelable(false)
            .setPositiveButton(resources.getString(R.string.updateNow)) { dialog, _ ->
                dialog.dismiss()
                if (appVersionName != appVersion) {
                    goToUpdate(appVersion, updateInfo)
                }
            }.show()
    }

    private fun getAppVersionName() {
        try {
            val packageInfo = this.packageManager.getPackageInfo(this.packageName, 0)
            appVersionName = packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
    }


    override fun onResume() {
        super.onResume()
        val medicineName = findViewById<EditText>(R.id.edit_medicine).text.toString()
        if (medicineName.isNotBlank()) {
            MedicineStockUtils.loadMedicineStock(
                db = db,
                vehicleName = defaultVehicle,
                medicineName = medicineName,
                textOpening = findViewById(R.id.text_opening),
                textClosing = findViewById(R.id.text_closing),
                editConsumption = findViewById(R.id.edit_consumption),
                editEmergency = findViewById(R.id.edit_total_emergency),
                formatMedValue = ::formatMedValue,
                lastLoadedOpeningBalanceSetter = { lastLoadedOpeningBalance = it },
                lastLoadedClosingBalanceSetter = { lastLoadedClosingBalance = it })
        }
    }

    private fun formatMedValue(medicineName: String, value: String?): String {
        val v = value?.toDoubleOrNull() ?: 0.0
        return v.toInt().toString()
    }

    private fun showSubmitPreviewDialog(
        vehicle: String,
        items: List<GoogleSheetsClient.SubmitItem>,
        onConfirm: () -> Unit
    ) {
        val previewText = buildString {
            append("Vehicle: ").append(vehicle).append('\n')
            append("Total Medicines: ").append(items.size).append("\n\n")
            items.sortedBy { it.medicine.lowercase() }.forEachIndexed { index, item ->
                append("${index + 1}. ${item.medicine}\n")
                append("   Consumption: ${item.consumption}   Emergency: ${item.emergency}\n")
            }
        }

        val tv = TextView(this).apply {
            text = previewText
            setPadding(32, 24, 32, 0)
            textSize = 14f
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val scroll = ScrollView(this).apply {
            addView(tv)
        }

       MaterialAlertDialogBuilder(this, R.style.AlertDialogTheme)
            .setIcon(R.drawable.ic_update)
            .setTitle("Confirm Submission")
            .setView(scroll)
            .setPositiveButton("Upload") { d, _ ->
                d.dismiss()
                onConfirm()
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
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
    /* ONLY the performDaySubmit function changed to add signature & reuse logic.
   Replace your existing performDaySubmit in FormActivity with this version.
   (Everything else in the activity remains the same as your current file.)
*/
    private fun performDaySubmit(
        vehicle: String,
        toSend: List<GoogleSheetsClient.SubmitItem>,
        medicineEdit: EditText,
        textOpening: TextView,
        textClosing: TextView,
        editConsumption: EditText,
        editEmergency: EditText
    ) {
        val pd = ProgressDialog(this).apply {
            setMessage("Submitting Consumption data...")
            setCancelable(false)
            show()
        }

        // Signature for this payload
        val signature = PendingRequestCache.signatureForConsumption(vehicle, toSend)
        val reuseRequestId = PendingRequestCache.reuseIfSame(signature)

        GoogleSheetsClient.submitConsumption(
            vehicle = vehicle,
            items = toSend,
            previousRequestId = reuseRequestId
        ) { ok, resp, requestIdUsed ->
            pd.dismiss()

            val duplicate = resp?.duplicate == true

            if (!ok && !duplicate) {
                // Store requestId for reuse if user retries
                PendingRequestCache.store(signature, requestIdUsed)
                Toast.makeText(
                    this,
                    "Submit failed: ${resp?.error ?: "network error"} (You can retry)",
                    Toast.LENGTH_LONG
                ).show()
                return@submitConsumption
            }

            // Success or duplicate success -> clear cache
            PendingRequestCache.clearIf(signature)

            if (vehicle.equals("RS-01", true)) {
                val today = TempRs01DailyStore.todayDate()
                toSend.forEach { item ->
                    // item.consumption = jo aap ne server ko bheji
                    TempRs01DailyStore.addConsumption(
                        context = this,
                        date = today,
                        medicine = item.medicine,
                        add = item.consumption
                    )
                }
            }

            // Update local DB (treat duplicate same as success)
            toSend.forEach { item ->
                db.addOrUpdateMedicine(
                    vehicle = vehicle,
                    medicine = item.medicine,
                    opening = db.getMedicineOpening(vehicle, item.medicine),
                    consumption = "0",
                    emergency = "0",
                    closing = db.getMedicineClosing(vehicle, item.medicine)
                )
            }

            val medName = medicineEdit.text.toString()
            if (medName.isNotBlank()) {
                MedicineStockUtils.loadMedicineStock(
                    db = db,
                    vehicleName = vehicle,
                    medicineName = medName,
                    textOpening = textOpening,
                    textClosing = textClosing,
                    editConsumption = editConsumption,
                    editEmergency = editEmergency,
                    formatMedValue = ::formatMedValue,
                    lastLoadedOpeningBalanceSetter = { lastLoadedOpeningBalance = it },
                    lastLoadedClosingBalanceSetter = { lastLoadedClosingBalance = it }
                )
            }

            val msg = if (duplicate)
                "Already submitted earlier (duplicate prevented)."
            else
                "Day submitted successfully!"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }
    private fun hasLocalUnsyncedChanges(vehicle: String): Boolean {
        return db.getAllMedicines().any { rec ->
            rec.vehicleName == vehicle &&
                    (
                            (rec.consumption?.toIntOrNull() ?: 0) > 0 ||
                                    (rec.totalEmergency?.toIntOrNull() ?: 0) > 0
                            )
        }
    }
    companion object {
        var isFormActivityReady = false
        var isDataFetchSuccessfull = false
    }
}