package com.epicx.apps.dailyconsumptionformapp

import CheckInternetConnection.observeNetworkConnectivity
import com.epicx.apps.dailyconsumptionformapp.FormConstants.specialDecimalMeds
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.epicx.apps.dailyconsumptionformapp.databinding.ActivityFormBinding
import com.epicx.apps.dailyconsumptionformapp.objects.NetworkMonitor
import com.epicx.apps.dailyconsumptionformapp.objects.SnackbarManager.snackbar
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFormBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase(this)
        rollOverBalancesIfDateChanged(this, db)

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
        val bandageKey = "bandage_deleted_once"
        if (!prefs.getBoolean(bandageKey, false)) {
            // Sabhi vehicles se delete karne ke liye loop:
            val allVehicles = FormConstants.vehicleList // ya apni vehicle list use karein
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
            showMedicineDialog(medicineEdit)
        }

        // Load Store Issued on medicine change
        fun loadStoreIssued(medicine: String) {
            val data = db.getAllMedicines().find { it.vehicleName == defaultVehicle && it.medicineName == medicine }
            textStoreIssued.text = data?.storeIssued ?: "0"
        }
        medicineEdit.addTextChangedListener {
            val medicineName = medicineEdit.text.toString()
            if (medicineName.isNotBlank()) {
                loadMedicineStock(medicineName, textOpening, textClosing, editConsumption, editEmergency)
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

        // Consumption field update
        editConsumption.addTextChangedListener {
            val consumption = editConsumption.text.toString().toFloatOrNull() ?: 0f
            val closingBalanceFloat = lastLoadedClosingBalance
            if (closingBalanceFloat == 0f && consumption > 0f) {
                Toast.makeText(this, "Bhai, pehle stock issue karwao, phir consumption kar sakte ho.", Toast.LENGTH_LONG).show()
                editConsumption.setText("0")
                textClosing.text = "0"
                return@addTextChangedListener
            }
            val newClosing = closingBalanceFloat - consumption
            val medicineName = medicineEdit.text.toString()
            textClosing.text = formatMedValue(medicineName, newClosing.toString())
        }
        val snackbar = Snackbar.make(binding.root, "Weak or No internet connection", Snackbar.LENGTH_INDEFINITE)
        snackbar(snackbar)
        lifecycleScope.launch {
            observeNetworkConnectivity(snackbar,applicationContext).collect { isConnected ->
                if (isConnected) {
                    // Internet is available
                    snackbar.dismiss()
                } else {
                    // No internet access (even if connected to a network)
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
            val closing = textClosing.text.toString()

            // Check: Consumption must not be blank or "0"
            if (inputConsumption.isBlank() || inputConsumption == "0" || inputConsumption == "0.0") {
                AlertDialog.Builder(this)
                    .setTitle("Missing Consumption")
                    .setMessage("Consumption ki value enter karein!")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            // Always zero for RS-01, otherwise user input
            val consumption = if (defaultVehicle == "RS-01") 0f else inputConsumption.toFloatOrNull() ?: 0f
            val emergency = if (defaultVehicle == "RS-01") 0 else inputEmergency.toIntOrNull() ?: 0

            // 1. Stock zero check
            val closingBalanceFloat = closing.toFloatOrNull() ?: 0f
            if (closingBalanceFloat == 0f && consumption > 0f) {
                Toast.makeText(this, "Bhai, pehle stock issue karwao, phir consumption kar sakte ho.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // 2. Required fields check
            if (vehicleName.isBlank() || medicineName.isBlank() ||
                (defaultVehicle != "RS-01" && (inputConsumption.isBlank() || inputEmergency.isBlank()))
            ) {
                errorText.text = "consumption & emergency fields are required"
                return@setOnClickListener
            }

            // 3. Numeric validation
            if (listOf(opening, closing).any { it.toFloatOrNull() == null } ||
                (defaultVehicle != "RS-01" && (inputConsumption.toFloatOrNull() == null || inputEmergency.toIntOrNull() == null))
            ) {
                errorText.text = "Numeric fields must be numbers"
                return@setOnClickListener
            }

            // 4. Business Rule: Only check for other vehicles, skip for RS-01
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

            // --- For RS-01 always save 0, for others add previous values ---
            val oldData = db.getAllMedicines().find { it.vehicleName == vehicleName && it.medicineName == medicineName }
            val oldConsumption = oldData?.consumption?.toFloatOrNull() ?: 0f
            val oldEmergency = oldData?.totalEmergency?.toIntOrNull() ?: 0

            val sumConsumption = if (defaultVehicle == "RS-01") 0f else oldConsumption + (inputConsumption.toFloatOrNull() ?: 0f)
            val sumEmergency = if (defaultVehicle == "RS-01") 0 else oldEmergency + (inputEmergency.toIntOrNull() ?: 0)

            db.addOrUpdateMedicine(
                vehicleName,
                medicineName,
                opening,
                sumConsumption.toString(),
                sumEmergency.toString(),
                closing
            )

            lastLoadedClosingBalance = closing.toFloatOrNull() ?: lastLoadedClosingBalance

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
                // Password dialog
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

    private fun loadMedicineStock(
        medicineName: String,
        textOpening: TextView,
        textClosing: TextView,
        editConsumption: EditText,
        editEmergency: EditText
    ) {
        val medData = db.getAllMedicines().find { it.vehicleName == defaultVehicle && it.medicineName == medicineName }
        if (medData != null) {
            lastLoadedOpeningBalance = medData.openingBalance.toFloatOrNull() ?: 0f
            lastLoadedClosingBalance = medData.closingBalance.toFloatOrNull() ?: 0f
            textOpening.text = formatMedValue(medicineName, medData.openingBalance)
            textClosing.text = formatMedValue(medicineName, medData.closingBalance)
            editConsumption.setText("")
            if (defaultVehicle != "RS-01") editEmergency.setText("0")
        } else {
            lastLoadedOpeningBalance = 0f
            lastLoadedClosingBalance = 0f
            textOpening.text = ""
            textClosing.text = ""
            editConsumption.setText("")
            if (defaultVehicle != "RS-01") editEmergency.setText("")
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
            else -> null // Should never happen, but just in case
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_form, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_upload) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val shift = getShiftTag()
            val uploadKey = "${today}_${defaultVehicle}_$shift"
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

            // Check if this shift+date+vehicle already uploaded
            if (prefs.getBoolean(uploadKey, false)) {
                AlertDialog.Builder(this)
                    .setTitle("Already Uploaded")
                    .setMessage("Aap is shift ka data upload kar chuke hain. Agli shift me upload karein.")
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
                            val result = GoogleSheetApi.bulkUpload(today, allMedicines)
                            progress.dismiss()
                            if (result.isSuccess) {
                                prefs.edit().putBoolean(uploadKey, true).apply()
                                db.resetConsumptionAndEmergency()
                                Toast.makeText(this@FormActivity, "Upload successful!", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this@FormActivity, "Upload failed: " + result.exceptionOrNull()?.message, Toast.LENGTH_LONG).show()
                                Log.e("failedToLoad", "Upload failed", result.exceptionOrNull())
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun formatMedValue(medicineName: String, value: String?): String {
        val isSpecial = specialDecimalMeds.contains(medicineName.trim())
        val doubleValue = value?.toDoubleOrNull() ?: 0.0
        return if (isSpecial) "%.2f".format(doubleValue) else doubleValue.toInt().toString()
    }

    private fun showMedicineDialog(editMedicine: EditText) {
        val builder = AlertDialog.Builder(this)
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_medicine_picker, null)
        builder.setView(dialogView)
        val dialog = builder.create()

        val searchEdit = dialogView.findViewById<EditText>(R.id.edit_search)
        val listView = dialogView.findViewById<ListView>(R.id.list_medicines)

        var filteredList = medicineList
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, filteredList)
        listView.adapter = adapter

        searchEdit.addTextChangedListener {
            val s = it?.toString() ?: ""
            filteredList = medicineList.filter { m -> m.contains(s, ignoreCase = true) }
            listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, filteredList)
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            editMedicine.setText(filteredList[position])
            dialog.dismiss()
        }
        dialog.show()
    }
}

fun rollOverBalancesIfDateChanged(context: Context, db: AppDatabase) {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    val lastDate = prefs.getString("last_rollover_date", null)
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    if (lastDate != today) {
        val allMeds = db.getAllMedicines()
        for (item in allMeds) {
            db.addOrUpdateMedicine(
                item.vehicleName,
                item.medicineName,
                item.closingBalance, // opening = last closing
                "0", // consumption
                "0", // emergency
                item.closingBalance // closing = opening (NOT ZERO)
            )
        }
        prefs.edit().putString("last_rollover_date", today).apply()
    }
}