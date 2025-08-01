package com.epicx.apps.dailyconsumptionformapp

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class FormActivity : AppCompatActivity() {

    // Vehicle aur medicine ki list yahan likh di hai taake file complete ho
    private val vehicleList = listOf(
        "BNA 07", "BNA 08", "BNA 09", "BNA 10", "BNA 11", "BNA 17", "BNA 21", "BNA 22",
        "BNA 25", "BNA 26", "BNA 29"
    ) + (1..50).map { n -> "BNB %02d".format(n) }
    private val medicineList = listOf(
        "Tab. Paracetamol 500 mg",
        "Inj. Paracetamol 60 mg (Water based)",
        "Tab. Asprin 300 mg",
        "Tab. Angised 0.5 mg",
        "Inj. Adrenaline in Pre-filled Syringe",
        "Inj. Hydrocortisone (250 mg)",
        "Inj. Pheniramine Maleate (25 mg)",
        "Ipratropium Bromide (20 ml)",
        "Inj. Dimenhydrinate (50 mg / ml)",
        "Inj. 25% Dextrose Water (20 ml)",
        "Inj. Diazepam 10 mg",
        "Tab. Captopril 25 mg",
        "Inj. Ringer Lactate (500 ml)",
        "Inj. Normal Saline (500/1000 ml)",
        "Inj. Diclofenac sodium water based (75 mg / 3ml)",
        "Povi-iodine Solution 10% (450 ml)",
        "Polymyxin B Sulphate Skin Ointment with lignocaine (20 gm)",
        "Lignocaine gel 2% (15 gm)",
        "Surface Disinfectant Spray",
        "Ethyl Chloride Spray (175 ml)",
        "Silver Sulphadiazine Cream (50gm)",
        "Airway 0",
        "Airway 1",
        "Airway 2",
        "Airway 3",
        "Airway 4",
        "Airway 5",
        "Laryngeal Mask Airway (LMA) 02",
        "Laryngeal Mask Airway (LMA) 03",
        "Laryngeal Mask Airway (LMA) 04",
        "Alcohol Swabs",
        "Face mask (Disposable, Cup shape with double strings)",
        "Disposable Syringes 05 ml",
        "Disposable 20 ml",
        "IV Cannula 18 No",
        "IV Cannula 20 No",
        "IV Cannula 22 No",
        "IV Cannula 24 No",
        "Drip Set",
        "Intraosseous Needle",
        "Cotton Bandages BPC 6.5 cm X 6 meter (2.5\")",
        "Cotton Bandages BPC 10 cm X 6 meter (4 inch)",
        "Cotton Bandages BPC 15 cm X 6 meter",
        "Crape Bandage Size 04 inches x 4.5 meter",
        "Sterilized Gauze Pieces 10 cm x 10 cm 1 box",
        "Paper Adhesive Tape (1”)",
        "Cotton Roll (500 gm)",
        "Nelton Catheter (18 Gauge)",
        "Nebulizer Mask with tubing (Small)",
        "Nebulizer Mask with tubing (Large)",
        "Adjustable Hard Cervical Collar with chin Support",
        "Triangular Bandage (Medium)",
        "Triangular Bandage (Large)",
        "Compatible Lancets",
        "Glucometer Active",
        "Glucometer Strips Accu-Check Active",
        "Glucometer Strips Accu-Check Instant",
        "Glucometer Strips Accu-Check Performa",
        "Glucometer Strips Medisign",
        "Neomycin Sulphate 0.5%, bacitracin zinc",
        "Examination Gloves"
    )

    private lateinit var db: AppDatabase
    private lateinit var defaultVehicle: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_form)

        db = AppDatabase(this)
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        defaultVehicle = prefs.getString("default_vehicle", null) ?: ""

        val vehicleSpinner = findViewById<Spinner>(R.id.spinner_vehicle)
        val medicineEdit = findViewById<EditText>(R.id.edit_medicine)
        val editOpening = findViewById<EditText>(R.id.edit_opening)
        val editConsumption = findViewById<EditText>(R.id.edit_consumption)
        val editEmergency = findViewById<EditText>(R.id.edit_total_emergency)
        val editClosing = findViewById<EditText>(R.id.edit_closing)
        val errorText = findViewById<TextView>(R.id.text_error)
        val btnSubmit = findViewById<Button>(R.id.btn_submit)
        val btnSummary = findViewById<Button>(R.id.btn_summary)
        val editDate = findViewById<EditText>(R.id.edit_date)

        val vehicleAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, vehicleList)
        vehicleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        vehicleSpinner.adapter = vehicleAdapter

        // Pehli dafa ya jab default vehicle set na ho
        if (defaultVehicle.isBlank()) {
            showVehicleSelectDialog(prefs, vehicleSpinner)
        } else {
            val index = vehicleList.indexOf(defaultVehicle)
            vehicleSpinner.setSelection(if (index >= 0) index else 0)
            vehicleSpinner.isEnabled = false
        }

        // Summary se agar edit mode mein aaye ho to form fields fill karo
        intent?.let {
            if (it.getBooleanExtra("editMode", false)) {
                val vehicleName = it.getStringExtra("vehicleName") ?: defaultVehicle
                val medicineName = it.getStringExtra("medicineName") ?: ""
                val opening = it.getStringExtra("openingBalance") ?: ""
                val consumption = it.getStringExtra("consumption") ?: ""
                val emergency = it.getStringExtra("totalEmergency") ?: ""
                val closing = it.getStringExtra("closingBalance") ?: ""

                val index = vehicleList.indexOf(vehicleName)
                vehicleSpinner.setSelection(if (index >= 0) index else 0)
                vehicleSpinner.isEnabled = false

                medicineEdit.setText(medicineName)
                editOpening.setText(opening)
                editConsumption.setText(consumption)
                editEmergency.setText(emergency)
                editClosing.setText(closing)
            }
        }

        // Date picker
        editDate.isFocusable = false
        editDate.setOnClickListener {
            val calendar = Calendar.getInstance()
            val datePicker = DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    calendar.set(year, month, dayOfMonth)
                    editDate.setText(sdf.format(calendar.time))
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            datePicker.show()
        }

        // Medicine picker
        medicineEdit.isFocusable = false
        medicineEdit.setOnClickListener {
            showMedicineDialog(medicineEdit)
        }

        btnSubmit.setOnClickListener {
            val vehicleName = prefs.getString("default_vehicle", "") ?: ""
            val medicineName = medicineEdit.text.toString()
            val opening = editOpening.text.toString()
            val consumption = editConsumption.text.toString()
            val emergency = editEmergency.text.toString()
            val closing = editClosing.text.toString()

            Log.d("FormSubmit", "vehicle=$vehicleName medicine=$medicineName opening=$opening consumption=$consumption emergency=$emergency closing=$closing")

            if (vehicleName.isBlank() || medicineName.isBlank() ||
                opening.isBlank() || consumption.isBlank() || emergency.isBlank() || closing.isBlank()
            ) {
                errorText.text = "All fields are required"
            } else if (
                listOf(opening, consumption, emergency, closing).any { it.toIntOrNull() == null }
            ) {
                errorText.text = "Numeric fields must be numbers"
            } else {
                errorText.text = ""
                db.addOrUpdateMedicine(vehicleName, medicineName, opening, consumption, emergency, closing)
                Toast.makeText(this, "Saved locally! Upload when ready.", Toast.LENGTH_SHORT).show()
            }
        }

        btnSummary.setOnClickListener {
            val intent = Intent(this, SummaryActivity::class.java)
            startActivity(intent)
        }
    }

    private fun showVehicleSelectDialog(prefs: android.content.SharedPreferences, vehicleSpinner: Spinner) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Apni Vehicle Select Karein")
        builder.setCancelable(false)
        builder.setSingleChoiceItems(vehicleList.toTypedArray(), -1) { dialog, which ->
            val selected = vehicleList[which]
            prefs.edit().putString("default_vehicle", selected).apply()
            defaultVehicle = selected
            val index = vehicleList.indexOf(defaultVehicle)
            vehicleSpinner.setSelection(if (index >= 0) index else 0)
            vehicleSpinner.isEnabled = false
            dialog.dismiss()
        }
        builder.show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_form, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_upload) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            if (db.checkUploadFlag(today)) {
                AlertDialog.Builder(this)
                    .setTitle("Already Uploaded")
                    .setMessage("Aap aaj ka data upload kar chuke hain. Kal try karein.")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Upload Warning")
                    .setMessage("Yeh data 1 din me sirf 1 dafa send ho sakta hai. Upload hone ke baad data delete/edit nahi ho sakta. Continue?")
                    .setPositiveButton("Upload") { _, _ ->
                        val allMedicines = db.getAllMedicines()
                        val progress = ProgressDialog(this)
                        progress.setMessage("Uploading to Google Sheet...")
                        progress.setCancelable(false)
                        progress.show()
                        lifecycleScope.launch {
                            val result = GoogleSheetApi.bulkUpload(today, allMedicines)
                            progress.dismiss()
                            if (result.isSuccess) {
                                db.setUploadFlag(today)
                                Toast.makeText(this@FormActivity, "Upload successful!", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this@FormActivity, "Upload failed: " + result.exceptionOrNull()?.message, Toast.LENGTH_LONG).show()
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