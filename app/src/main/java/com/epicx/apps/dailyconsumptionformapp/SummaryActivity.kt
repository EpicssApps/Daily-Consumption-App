package com.epicx.apps.dailyconsumptionformapp

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.epicx.apps.dailyconsumptionformapp.databinding.ActivitySummaryBinding
import java.io.File

class SummaryActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySummaryBinding
    private lateinit var db: AppDatabase

    // For Import (file picker)
    private val importFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val inputStream = contentResolver.openInputStream(uri)
            val tempFile = File.createTempFile("imported", ".csv", cacheDir)
            tempFile.outputStream().use { out -> inputStream?.copyTo(out) }
            val ok = db.importFromCSV(tempFile)
            Toast.makeText(this, if (ok) "Import Successful!" else "Import Failed!", Toast.LENGTH_LONG).show()
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase(this)
        val dataList = db.getAllMedicines()

        binding.recyclerSummary.layoutManager = LinearLayoutManager(this)
        binding.recyclerSummary.adapter = SummaryAdapter(dataList) { selectedItem ->
            // Yahan intent me sari values bhej do
            val intent = Intent(this, FormActivity::class.java)
            intent.putExtra("vehicleName", selectedItem.vehicleName)
            intent.putExtra("medicineName", selectedItem.medicineName)
            intent.putExtra("openingBalance", selectedItem.openingBalance)
            intent.putExtra("consumption", selectedItem.consumption)
            intent.putExtra("totalEmergency", selectedItem.totalEmergency)
            intent.putExtra("closingBalance", selectedItem.closingBalance)
            intent.putExtra("editMode", true) // optional: pata chale edit mode hai
            startActivity(intent)
        }

        // Export Button
        binding.btnExport.setOnClickListener {
            val fileName = "medicines_backup_${System.currentTimeMillis()}.csv"
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)
            val ok = db.exportToCSV(file)
            Toast.makeText(this, if (ok) "Exported to Downloads/$fileName" else "Export Failed!", Toast.LENGTH_LONG).show()
        }
        // Import Button
        binding.btnImport.setOnClickListener {
            importFileLauncher.launch("*/*")
        }
    }
}