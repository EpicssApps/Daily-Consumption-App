package com.epicx.apps.dailyconsumptionformapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.epicx.apps.dailyconsumptionformapp.databinding.ActivityMyUploadBinding
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MyUploadActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMyUploadBinding
    private var uploadedList: List<FormData> = emptyList()
    private var vehicleName: String = ""
    private var shift: String = ""
    private var date: String = ""
    private var time: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vehicleName = intent.getStringExtra("vehicle_name") ?: ""
        date = intent.getStringExtra("date") ?: ""
        binding.btnShareWhatsapp.visibility = View.GONE

        // Shift nikalne ka tareeqa, same jo ap GoogleSheetApi me use karte hain
        shift = getShiftTag() ?: ""
        time = SimpleDateFormat("HH-mm", Locale.US).format(Date())

        title = "Mera Uploaded Data"

        binding.progressBar.visibility = View.VISIBLE
        binding.dataContainer.visibility = View.GONE
        binding.textNoData.visibility = View.GONE

        lifecycleScope.launch {
            val result = GoogleSheetApi.getAllForDate(date)
            binding.progressBar.visibility = View.GONE
            if (result.isSuccess) {
                uploadedList = result.getOrNull()?.filter {
                    it.vehicleName.trim().startsWith(vehicleName)
                } ?: emptyList()
                if (uploadedList.isEmpty()) {
                    binding.textNoData.visibility = View.VISIBLE
                    binding.dataContainer.visibility = View.GONE
                    binding.btnShareWhatsapp.visibility = View.GONE // <--- already here
                } else {
                    binding.recyclerView.layoutManager = LinearLayoutManager(this@MyUploadActivity)
                    binding.recyclerView.adapter = SummaryAdapter(uploadedList, false) { /* no edit */ }
                    binding.dataContainer.visibility = View.VISIBLE
                    binding.textNoData.visibility = View.GONE
                    binding.btnShareWhatsapp.visibility = View.VISIBLE // <--- already here
                }
            } else {
                Toast.makeText(this@MyUploadActivity, "Data fetch nahi ho saka", Toast.LENGTH_LONG).show()
                binding.textNoData.visibility = View.VISIBLE
                binding.dataContainer.visibility = View.GONE
                binding.btnShareWhatsapp.visibility = View.GONE // <--- already here
            }
        }

        // WhatsApp share button listener
        binding.btnShareWhatsapp.setOnClickListener {
            if (uploadedList.isEmpty()) {
                Toast.makeText(this, "Koi uploaded data nahi hai!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            shareDataOnWhatsapp()
        }
    }

    private fun shareDataOnWhatsapp() {
        try {
            val fileName = "${vehicleName.replace(" ", "_")}_${shift}_${date}_${time}_GoogleSheetUpload.csv"
            val file = File(cacheDir, fileName)
            file.printWriter().use { out ->
                out.println("Vehicle,Medicine,Opening,Consumption,Emergency,Closing,Store Issued")
                for (item in uploadedList) {
                    out.println("${item.vehicleName},${item.medicineName},${item.openingBalance},${item.consumption},${item.totalEmergency},${item.closingBalance},${item.storeIssued}")
                }
            }

            // Yeh line change karein:
            // val uri = Uri.fromFile(file)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "WhatsApp par CSV share karen..."))
        } catch (e: Exception) {
            Toast.makeText(this, "Share nahi ho saka: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getShiftTag(): String? {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        return when {
            hour in 8 until 15 -> "Morning"
            hour in 15 until 22 -> "Evening"
            hour in 22..23 -> "Night"
            hour in 0 until 8 -> "EarlyMorning"
            else -> null
        }
    }
}