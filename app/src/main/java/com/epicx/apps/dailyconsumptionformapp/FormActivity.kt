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
    private var appVersionName :String? = null
    private var executor: ExecutorService? = null
    private var handler : Handler? = null

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
        val btnAddStock = findViewById<Button>(R.id.btn_add_stock)
        val textEmergencyLabel = findViewById<TextView>(R.id.text_emergency_label)
        val textStoreIssued = findViewById<TextView>(R.id.text_store_issued)

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

        btnAddStock.setOnClickListener {
            val medicineName = medicineEdit.text.toString()
            if (medicineName.isBlank()) {
                Toast.makeText(this, "Select medicine first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val input = EditText(this)
            input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            input.hint = "Enter quantity"
            AlertDialog.Builder(this)
                .setTitle("Add New Stock")
                .setMessage("How much new stock for $medicineName?")
                .setView(input)
                .setPositiveButton("Add") { dialog, _ ->
                    val addValue = input.text.toString().toFloatOrNull() ?: 0f
                    if (addValue > 0f) {
                        lastLoadedClosingBalance += addValue
                        lastLoadedOpeningBalance += addValue
                        textClosing.text = formatMedValue(medicineName, lastLoadedClosingBalance.toString())
                        textOpening.text = formatMedValue(medicineName, lastLoadedOpeningBalance.toString())
                        db.addOrUpdateMedicine(
                            defaultVehicle,
                            medicineName,
                            textOpening.text.toString(),
                            editConsumption.text.toString(),
                            editEmergency.text.toString(),
                            textClosing.text.toString()
                        )
                        Toast.makeText(this, "Stock added successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Quantity must be greater than 0.", Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Vehicle select logic
        if (defaultVehicle.isBlank()) {
            showVehicleSelectDialog(prefs, vehicleSpinner, editEmergency, textEmergencyLabel)
        } else {
            val index = vehicleList.indexOf(defaultVehicle)
            vehicleSpinner.setSelection(if (index >= 0) index else 0)
            vehicleSpinner.isEnabled = false
            db.prepopulateMedicinesForVehicle(defaultVehicle, FormConstants.medicineList)
            updateEmergencyVisibility(editEmergency, textEmergencyLabel, defaultVehicle, layoutStoreIssued)
        }

        // Medicine picker
        medicineEdit.isFocusable = false
        medicineEdit.setOnClickListener {
            MedicineDialogUtils.showMedicineDialog(this, medicineList, medicineEdit)
        }

        // Load Store Issued on medicine change
        fun loadStoreIssued(medicine: String) {
            val data = db.getAllMedicines().find { it.vehicleName == defaultVehicle && it.medicineName == medicine }
            textStoreIssued.text = data?.storeIssued ?: "0"
        }
        medicineEdit.addTextChangedListener {
            val medicineName = medicineEdit.text.toString()
            if (medicineName.isNotBlank()) {
                MedicineStockUtils.loadMedicineStock(db = db, vehicleName = defaultVehicle, medicineName = medicineName, textOpening = textOpening, textClosing = textClosing, editConsumption = editConsumption, editEmergency = editEmergency, formatMedValue = ::formatMedValue, lastLoadedOpeningBalanceSetter = { lastLoadedOpeningBalance = it }, lastLoadedClosingBalanceSetter = { lastLoadedClosingBalance = it })
                loadStoreIssued(medicineName)
            }
        }

        // Store Issued dialog logic
        textStoreIssued.setOnClickListener {
            if (defaultVehicle == "RS-01") {
                val medName = medicineEdit.text.toString()
                if (medName.isBlank()) {
                    Toast.makeText(this, "Pehle medicine select karein!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val input = EditText(this)
                input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                input.hint = "Enter issued value"
                if (textStoreIssued.text.toString() == "0") {
                    input.setText("")
                } else {
                    input.setText(textStoreIssued.text.toString())
                }
                AlertDialog.Builder(this)
                    .setTitle("Store Issued")
                    .setMessage("Set issued value for $medName:")
                    .setView(input)
                    .setPositiveButton("Save") { dialog, _ ->
                        val value = input.text.toString().toIntOrNull() ?: 0
                        db.updateStoreIssued(defaultVehicle, medName, value.toString())
                        textStoreIssued.text = value.toString()
                        Toast.makeText(this, "Store Issued value updated!", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
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

        val snackbar = Snackbar.make(binding.root, "Weak or No internet connection", Snackbar.LENGTH_INDEFINITE)
        snackbar(snackbar)
        lifecycleScope.launch {
            observeNetworkConnectivity(snackbar,applicationContext).collect { isConnected ->
                if (isConnected) {
                    snackbar.dismiss()
                } else {
                    snackbar.show()
                }
            }
        }
        btnSubmit.setOnClickListener {
            val vehicleName = defaultVehicle
            val medicineName = medicineEdit.text.toString()
            val opening = textOpening.text.toString()
            val inputConsumption = editConsumption.text.toString()
            val inputEmergency = editEmergency.text.toString()
            val uiClosing = textClosing.text.toString() // UI se dikh rahi value (reference ke liye)

            // Consumption must not be blank or zero
            if (inputConsumption.isBlank() || inputConsumption == "0" || inputConsumption == "0.0") {
                AlertDialog.Builder(this)
                    .setTitle("Missing Consumption")
                    .setMessage("Consumption ki value enter karein!")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            // Always get user input for consumption (including RS-01)
            val consumption = inputConsumption.toFloatOrNull() ?: 0f
            val emergency = inputEmergency.toIntOrNull() ?: 0

            // 1) Stock zero check: AB DB ke current closing pe (save se pehle) check hoga
            val dbRecord = db.getAllMedicines().find { it.vehicleName == vehicleName && it.medicineName == medicineName }
            val currentDbClosing = dbRecord?.closingBalance?.toFloatOrNull() ?: 0f
            if (currentDbClosing == 0f && consumption > 0f) {
                Toast.makeText(this, "Bhai, pehle stock issue karwao, phir consumption kar sakte ho.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // 2) Required fields check
            if (vehicleName.isBlank() || medicineName.isBlank()) {
                errorText.text = "Vehicle & medicine required"
                return@setOnClickListener
            }
            if (defaultVehicle != "RS-01") {
                if (inputConsumption.isBlank() || inputEmergency.isBlank()) {
                    errorText.text = "consumption & emergency fields are required"
                    return@setOnClickListener
                }
            }

            // 3) Numeric validation
            if (listOf(opening, uiClosing).any { it.toFloatOrNull() == null } ||
                inputConsumption.toFloatOrNull() == null ||
                (defaultVehicle != "RS-01" && inputEmergency.toIntOrNull() == null)
            ) {
                errorText.text = "Numeric fields must be numbers"
                return@setOnClickListener
            }

            // 4) Business Rule for non RS-01 (same as before)
            if (defaultVehicle != "RS-01") {
                if (consumption == 0f || emergency == 0) {
                    val msg = when {
                        consumption > 0f && emergency == 0 -> "Bhai, jab consumption likho to total emergency bhi likhna zaroori hai!"
                        emergency > 0 && consumption == 0f -> "Bhai, jab emergency likho to total consumption bhi likhna zaroori hai!"
                        emergency == 0 && consumption == 0f -> "Bhai, emergency & consumption likhna zaroori hai!"
                        else -> "Fields required!"
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Validation Error")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
                    return@setOnClickListener
                }
            }

            errorText.text = ""

            // Purane data (sum logic same) — DB closing se naya closing recalc karenge
            val oldData = dbRecord
            val oldConsumption = oldData?.consumption?.toFloatOrNull() ?: 0f
            val oldEmergency = oldData?.totalEmergency?.toIntOrNull() ?: 0

            val sumConsumption = oldConsumption + consumption
            val sumEmergency = oldEmergency + emergency

            // DB ke current closing se naya closing compute (0 se niche na jaye)
            val recalculatedClosing = (currentDbClosing - consumption).coerceAtLeast(0f)
            val closingToSave = formatMedValue(medicineName, recalculatedClosing.toString())

            db.addOrUpdateMedicine(
                vehicleName,
                medicineName,
                opening,
                sumConsumption.toString(),
                sumEmergency.toString(),
                closingToSave
            )

            // State update
            lastLoadedClosingBalance = recalculatedClosing
            // UI bhi sync kar dein
            textClosing.text = closingToSave

            AlertDialog.Builder(this)
                .setTitle("Saved")
                .setMessage("Saved locally! Upload when ready.")
                .setPositiveButton("OK", null)
                .show()
            editConsumption.setText("")
            editEmergency.setText("")
        }

        btnSummary.setOnClickListener {
            val intent = Intent(this, SummaryActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

    }

    private fun checkUpdate() {
        var appVersion:String?
        executor?.execute {
            try {
                val document = Jsoup.connect("https://sites.google.com/view/dailyconsumption/dailyconumption").get()
                val elements = document.getElementsByClass("C9DxTc")
                appVersion = elements[2].text()
                val isUpdateAvailable = elements[4].text()
                val updateInfo = elements[6].text()
                handler?.post {
                    if (isUpdateAvailable == "true") {
                        if (appVersionName != appVersion) {
                            goToUpdate(appVersion,updateInfo)
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
                if (appVersionName != appVersion){
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
                lastLoadedClosingBalanceSetter = { lastLoadedClosingBalance = it }
            )
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
    private fun showVehicleSelectDialog(
        prefs: android.content.SharedPreferences,
        vehicleSpinner: Spinner,
        editEmergency: EditText,
        textEmergencyLabel: TextView
    ) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Apni Vehicle Select Karein")
        builder.setCancelable(false)
        builder.setSingleChoiceItems(vehicleList.toTypedArray(), -1) { dialog, which ->
            val selected = vehicleList[which]
            if (selected == "RS-01") {
                val passwordInput = EditText(this)
                passwordInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                passwordInput.hint = "Enter password"
                AlertDialog.Builder(this)
                    .setTitle("Password Required")
                    .setMessage("RS-01 select karne ke liye password enter karein.")
                    .setView(passwordInput)
                    .setPositiveButton("OK") { passDialog, _ ->
                        val password = passwordInput.text.toString()
                        if (password == "FDR-1049*") {
                            prefs.edit().putString("default_vehicle", selected).apply()
                            defaultVehicle = selected
                            val index = vehicleList.indexOf(defaultVehicle)
                            vehicleSpinner.setSelection(if (index >= 0) index else 0)
                            vehicleSpinner.isEnabled = false
                            db.prepopulateMedicinesForVehicle(selected, FormConstants.medicineList)
                            updateEmergencyVisibility(editEmergency, textEmergencyLabel, defaultVehicle, layoutStoreIssued)
                            invalidateOptionsMenu()
                            dialog.dismiss()
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("Wrong Password")
                                .setMessage("Password ghalat hai. Dobara try karein.")
                                .setPositiveButton("OK") { _, _ ->
                                    showVehicleSelectDialog(prefs, vehicleSpinner, editEmergency, textEmergencyLabel)
                                }
                                .show()
                            passDialog.dismiss()
                            dialog.dismiss()
                        }
                    }
                    .setNegativeButton("Cancel") { passDialog, _ ->
                        showVehicleSelectDialog(prefs, vehicleSpinner, editEmergency, textEmergencyLabel)
                        passDialog.dismiss()
                        dialog.dismiss()
                    }
                    .show()
            } else {
                prefs.edit().putString("default_vehicle", selected).apply()
                defaultVehicle = selected
                val index = vehicleList.indexOf(defaultVehicle)
                vehicleSpinner.setSelection(if (index >= 0) index else 0)
                vehicleSpinner.isEnabled = false
                db.prepopulateMedicinesForVehicle(selected, FormConstants.medicineList)
                updateEmergencyVisibility(
                    editEmergency,
                    textEmergencyLabel,
                    defaultVehicle,
                    layoutStoreIssued
                )
                invalidateOptionsMenu()
                dialog.dismiss()
            }
        }
        builder.show()
    }

    private fun updateEmergencyVisibility(
        editEmergency: EditText,
        textEmergencyLabel: TextView,
        vehicle: String,
        layoutStoreIssued: LinearLayout
    ) {
        if (vehicle == "RS-01") {
            editEmergency.visibility = View.GONE
            textEmergencyLabel.visibility = View.GONE
            layoutStoreIssued.visibility = View.VISIBLE
        } else {
            editEmergency.visibility = View.VISIBLE
            textEmergencyLabel.visibility = View.VISIBLE
            layoutStoreIssued.visibility = View.GONE
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
        when(item.itemId){
            R.id.action_upload -> {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val shift = getShiftTag()
                val uploadKey = "${today}_${defaultVehicle}_${shift}_normal"
                val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

                // Removed incorrect early return for non-RS-01 users

                if (prefs.getBoolean(uploadKey, false)) {
                    AlertDialog.Builder(this)
                        .setTitle("Already Uploaded")
                        .setMessage("Aap is shift ka normal data upload kar chuke hain. Agli shift me upload karein.")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    val allMedicines = db.getAllMedicines()
                    if (allMedicines.isEmpty()) {
                        AlertDialog.Builder(this)
                            .setTitle("Database Empty")
                            .setMessage("Aap ka database khali hai, upload nahi ho sakta.")
                            .setPositiveButton("OK", null)
                            .show()
                        return true
                    }

                    val uploadList = if (defaultVehicle == "RS-01") {
                        allMedicines.map { fd ->
                            fd.copy(
                                consumption = "0",
                                totalEmergency = "0"
                            )
                        }
                    } else {
                        allMedicines
                    }

                    AlertDialog.Builder(this)
                        .setTitle("Upload Warning")
                        .setMessage("Yeh data aik shift me sirf 1 dafa send ho sakta hai. Upload hone ke baad data delete/edit nahi ho sakta. Continue?")
                        .setPositiveButton("Upload") { _, _ ->
                            val progress = ProgressDialog(this)
                            progress.setMessage("Uploading to Google Sheet...")
                            progress.setCancelable(false)
                            progress.show()
                            lifecycleScope.launch {
                                val result = GoogleSheetApi.bulkUpload(today, uploadList)
                                progress.dismiss()
                                val intent = Intent(this@FormActivity, MyUploadActivity::class.java).apply {
                                    putExtra("vehicle_name", defaultVehicle)
                                    putExtra("date", today)
                                }
                                if (result.isSuccess) {
                                    prefs.edit().putBoolean(uploadKey, true).apply()

                                    if (defaultVehicle != "RS-01"){
                                        db.resetConsumptionAndEmergency()
                                    }
                                    Toast.makeText(this@FormActivity, "Upload Successful", Toast.LENGTH_SHORT).show()
                                    startActivity(intent)
                                } else {
                                    Toast.makeText(
                                        this@FormActivity,
                                        "Upload failed: " + (result.exceptionOrNull()?.message ?: "Unknown error"),
                                        Toast.LENGTH_LONG
                                    ).show()
                                    startActivity(intent)
                                    Log.e("failedToLoad", "Upload failed", result.exceptionOrNull())
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                return true
            }
            R.id.action_upload_rs01 -> {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val shift = getShiftTag()
                val uploadKey = "${today}_${defaultVehicle}_${shift}_rs01"
                val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

                if (defaultVehicle != "RS-01") return true

                if (prefs.getBoolean(uploadKey, false)) {
                    AlertDialog.Builder(this)
                        .setTitle("Already Uploaded")
                        .setMessage("Aap is shift ka RS-01 special data upload kar chuke hain. Agli shift me upload karein.")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    val allMedicines = db.getAllMedicines()
                    if (allMedicines.isEmpty()) {
                        AlertDialog.Builder(this)
                            .setTitle("Database Empty")
                            .setMessage("Aap ka database khali hai, upload nahi ho sakta.")
                            .setPositiveButton("OK", null)
                            .show()
                        return true
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Upload Warning")
                        .setMessage("Yeh data aik shift me sirf 1 dafa send ho sakta hai. Upload hone ke baad data delete/edit nahi ho sakta. Continue?")
                        .setPositiveButton("Upload") { _, _ ->
                            val progress = ProgressDialog(this)
                            progress.setMessage("Uploading to Google Sheet...")
                            progress.setCancelable(false)
                            progress.show()
                            lifecycleScope.launch {
                                val result = GoogleSheetApi.rs01BulkUpload(today, allMedicines)
                                progress.dismiss()
                                if (result.isSuccess) {
                                    prefs.edit().putBoolean(uploadKey, true).apply()
                                    db.resetConsumptionAndEmergency()
                                    AlertDialog.Builder(this@FormActivity)
                                        .setTitle("Upload Successful")
                                        .setMessage("Aap ka RS-01 special data upload ho chuka hai successfully.")
                                        .setPositiveButton("OK", null)
                                        .show()
                                } else {
                                    Toast.makeText(
                                        this@FormActivity,
                                        "Upload failed: " + result.exceptionOrNull()?.message,
                                        Toast.LENGTH_LONG
                                    ).show()
                                    Log.e("failedToLoad", "Upload failed", result.exceptionOrNull())
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }
    private fun formatMedValue(medicineName: String, value: String?): String {
        val isSpecial = specialDecimalMeds.contains(medicineName.trim())
        val doubleValue = value?.toDoubleOrNull() ?: 0.0
        return if (isSpecial) "%.2f".format(doubleValue) else doubleValue.toInt().toString()
    }
}