package com.epicx.apps.dailyconsumptionformapp

import CheckInternetConnection.observeNetworkConnectivity
import com.epicx.apps.dailyconsumptionformapp.FormConstants.specialDecimalMeds
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.epicx.apps.dailyconsumptionformapp.databinding.ActivityFormBinding
import com.epicx.apps.dailyconsumptionformapp.formActivityObjects.AddStockHelper
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
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFormBinding
    private val vehicleList = FormConstants.vehicleList
    private val medicineList = FormConstants.medicineList
    private lateinit var db: AppDatabase
    private var alertDialog: AlertDialog? = null
    private lateinit var defaultVehicle: String
    private lateinit var layoutStoreIssued: LinearLayout

    // For UI & calculations
    private var lastLoadedClosingBalance: Float = 0f
    private var lastLoadedOpeningBalance: Float = 0f
    private var appVersionName: String? = null
    private var executor: ExecutorService? = null
    private var handler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        val vehicleAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, vehicleList)
        vehicleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        vehicleSpinner.adapter = vehicleAdapter
        NetworkMonitor.init(applicationContext)

        getAppVersionName()
        checkUpdate()

        val bandageKey = "bandage_deleted_once"
        if (!prefs.getBoolean(bandageKey, false)) {
            val allVehicles = FormConstants.vehicleList
            for (vehicle in allVehicles) {
                db.deleteMedicineByName(vehicle, "Cotton Bandages BPC 6.5 cm X 6 meter (2.5\")")
            }
            prefs.edit().putBoolean(bandageKey, true).apply()
        }

        NetworkMonitor.observe(this) { isConnected ->
            if (!isConnected) {
                if (alertDialog?.isShowing != true) {
                    showAlertDialog()
                }
            } else {
                alertDialog?.dismiss()
            }
        }

        btnSubmitDay.setOnClickListener {
            if (defaultVehicle.isBlank()) {
                Toast.makeText(this, "Please select vehicle first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Collect all changed items for this vehicle
            val all = db.getAllMedicines().filter { it.vehicleName == defaultVehicle }
            val toSend = all.mapNotNull { rec ->
                val cons = rec.consumption.toDoubleOrNull() ?: 0.0
                val emerg = rec.totalEmergency.toDoubleOrNull() ?: 0.0
                if (cons > 0.0 || emerg > 0.0) {
                    GoogleSheetsClient.SubmitItem(
                        medicine = rec.medicineName,
                        consumption = cons,
                        emergency = emerg
                    )
                } else null
            }

            if (toSend.isEmpty()) {
                Toast.makeText(this, "No changes to submit.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val pd = ProgressDialog(this).apply {
                setMessage("Submitting day data...")
                setCancelable(false)
                show()
            }

            GoogleSheetsClient.submitConsumption(defaultVehicle, toSend) { ok, resp ->
                pd.dismiss()
                if (!ok) {
                    Toast.makeText(this, "Submit failed: ${resp?.error ?: "network error"}", Toast.LENGTH_LONG).show()
                    return@submitConsumption
                }
                // Optional: reset local consumption/emergency after successful upload
                toSend.forEach { item ->
                    db.addOrUpdateMedicine(
                        vehicle = defaultVehicle,
                        medicine = item.medicine,
                        opening = db.getMedicineOpening(defaultVehicle, item.medicine), // you may have your own getter; else pass existing opening
                        consumption = "0",
                        emergency = "0",
                        closing = db.getMedicineClosing(defaultVehicle, item.medicine) // keep existing local closing
                    )
                }
                // Refresh UI if a medicine is already selected
                val medName = findViewById<EditText>(R.id.edit_medicine).text.toString()
                if (medName.isNotBlank()) {
                    MedicineStockUtils.loadMedicineStock(
                        db = db,
                        vehicleName = defaultVehicle,
                        medicineName = medName,
                        textOpening = findViewById(R.id.text_opening),
                        textClosing = findViewById(R.id.text_closing),
                        editConsumption = findViewById(R.id.edit_consumption),
                        editEmergency = findViewById(R.id.edit_total_emergency),
                        formatMedValue = ::formatMedValue,
                        lastLoadedOpeningBalanceSetter = { lastLoadedOpeningBalance = it },
                        lastLoadedClosingBalanceSetter = { lastLoadedClosingBalance = it }
                    )
                }
                Toast.makeText(this, "Day submitted successfully!", Toast.LENGTH_LONG).show()
            }
        }

        issueToVehicles.setOnClickListener {
            val intent = Intent(this, IssueMedicineActivity::class.java)
            startActivity(intent)
        }
        // Vehicle select logic
        if (defaultVehicle.isBlank()) {
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
                    SyncFromServerHelper.syncFromServerForVehicle(
                        activity = this,
                        db = db,
                        vehicle = defaultVehicle,
                        formatMedValue = ::formatMedValue,
                        lastLoadedOpeningBalanceSetter = { lastLoadedOpeningBalance = it },
                        lastLoadedClosingBalanceSetter = { lastLoadedClosingBalance = it },
                        showLoading = true // Show waiting dialog only for new user initial sync
                    )
                }
            )
        } else {
            val index = vehicleList.indexOf(defaultVehicle)
            vehicleSpinner.setSelection(if (index >= 0) index else 0)
            vehicleSpinner.isEnabled = false
            db.prepopulateMedicinesForVehicle(defaultVehicle, FormConstants.medicineList)
            EmergencyVisibilityHelper.update(
                editEmergency = editEmergency,
                textEmergencyLabel = textEmergencyLabel,
                vehicle = defaultVehicle,
                layoutStoreIssued = layoutStoreIssued,
                issueToVehicles = issueToVehicles
            )
            SyncFromServerHelper.syncFromServerForVehicle(
                activity = this,
                db = db,
                vehicle = defaultVehicle,
                formatMedValue = ::formatMedValue,
                lastLoadedOpeningBalanceSetter = { lastLoadedOpeningBalance = it },
                lastLoadedClosingBalanceSetter = { lastLoadedClosingBalance = it },
                showLoading = false // For existing users, no waiting dialog
            )
        }

        // Medicine picker
        medicineEdit.isFocusable = false

        medicineEdit.setOnClickListener {
            MedicineDialogUtils.showMedicineDialog(this, medicineList, medicineEdit)
        }
        // Load Store Issued on medicine change
        fun loadStoreIssued(medicine: String) {
            val data = db.getAllMedicines()
                .find { it.vehicleName == defaultVehicle && it.medicineName == medicine }
            textStoreIssued.text = data?.storeIssued ?: "0"
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

        // Store Issued dialog logic
        textStoreIssued.setOnClickListener {
            StoreIssuedHelper.showDialog(activity = this, defaultVehicle = defaultVehicle, medicineEdit = medicineEdit, textStoreIssued = textStoreIssued) { vehicle, med, value ->
                db.updateStoreIssued(vehicle, med, value)
            }
        }

        // Consumption field update (sirf UI update; yahan stock-issue block hata diya)
        editConsumption.addTextChangedListener {
            val consumption = editConsumption.text.toString().toFloatOrNull() ?: 0f
            val baseClosing = lastLoadedClosingBalance
            val newClosing = (baseClosing - consumption).coerceAtLeast(0f)
            val medicineName = medicineEdit.text.toString()
            textClosing.text = formatMedValue(medicineName, newClosing.toString())
        }

        val snackbar = Snackbar.make(
            binding.root,
            "Weak or No internet connection",
            Snackbar.LENGTH_INDEFINITE
        )
        snackbar(snackbar)
        lifecycleScope.launch {
            observeNetworkConnectivity(snackbar, applicationContext).collect { isConnected ->
                if (isConnected) {
                    snackbar.dismiss()
                } else {
                    snackbar.show()
                }
            }
        }

        btnSubmit.setOnClickListener {
            MedicineSubmitHelper.handleSubmit(activity = this, defaultVehicle = defaultVehicle, medicineEdit = medicineEdit, textOpening = textOpening, editConsumption = editConsumption, editEmergency = editEmergency, textClosing = textClosing, errorText = errorText, getAllMedicines = {
                // Aapke DB model ko helper ke model me map karein
                db.getAllMedicines().map {
                    MedicineSubmitHelper.DbMedRecord(vehicleName = it.vehicleName, medicineName = it.medicineName, closingBalance = it.closingBalance, consumption = it.consumption, totalEmergency = it.totalEmergency)
                }
            },
                addOrUpdateMedicine = { v, m, o, c, e, cl ->
                    db.addOrUpdateMedicine(v, m, o, c, e, cl)
                },
                formatMedValue = { med, value ->
                    formatMedValue(med, value)
                },
                onClosingRecalculated = { value ->
                    lastLoadedClosingBalance = value
                }
            )
        }

        btnSummary.setOnClickListener {
            val intent = Intent(this, SummaryActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

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
                            goToUpdate(appVersion, updateInfo)
                        }
                    }
                }
                // Handle case where expected element is not found
                handler?.post {
                    // Notify user or log an error
                }
            } catch (e: IOException) {
                e.printStackTrace()
                // Handle connection or parsing errors
                handler?.post {
                    // Notify user or log an error
                }
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

    private fun showAlertDialog() {
        if (!isFinishing && !isDestroyed) {
            alertDialog = AlertDialog.Builder(this)
                .setTitle("No Internet Connection")
                .setMessage("Please connect to continue.")
                .setCancelable(false)
                .setPositiveButton("Exit") { _, _ -> showAlertDialog() }
                .show()
        }
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

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_form, menu)
        val rs01MenuItem = menu?.findItem(R.id.action_upload_rs01)
        val uploadMenuItem = menu?.findItem(R.id.action_upload)

        if (defaultVehicle == "RS-01") {
            rs01MenuItem?.isVisible = true
            uploadMenuItem?.isVisible = true
        } else {
            rs01MenuItem?.isVisible = false
            uploadMenuItem?.isVisible = true
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_upload -> {
                UploadMenuHelper.handleNormalUpload(activity = this, db = db, defaultVehicle = defaultVehicle, getShiftTag = { getShiftTag() })
            }
            R.id.action_upload_rs01 -> {
                UploadMenuHelper.handleRs01Upload(activity = this, db = db, defaultVehicle = defaultVehicle, getShiftTag = { getShiftTag() })
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun formatMedValue(medicineName: String, value: String?): String {
        val isSpecial = specialDecimalMeds.contains(medicineName.trim())
        val doubleValue = value?.toDoubleOrNull() ?: 0.0
        return if (isSpecial) "%.2f".format(doubleValue) else doubleValue.toInt().toString()
    }
}