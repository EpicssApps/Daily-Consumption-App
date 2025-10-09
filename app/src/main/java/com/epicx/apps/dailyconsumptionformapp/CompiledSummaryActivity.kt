package com.epicx.apps.dailyconsumptionformapp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.epicx.apps.dailyconsumptionformapp.databinding.ActivityCompiledSummaryBinding

class CompiledSummaryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCompiledSummaryBinding
    private lateinit var db: AppDatabase
    private lateinit var adapter: CompiledSummaryAdapter

    // Display values exactly as stored in DB (no halving)
    private fun formatValue(value: Double): String = value.toInt().toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompiledSummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase(this)
        val summaryList = db.getAllCompiledSummary()
        val reversedSummaryList = summaryList.reversed()
        adapter = CompiledSummaryAdapter(
            reversedSummaryList.toMutableList(),
            onDeleteClicked = { item ->
                db.deleteCompiledSummaryById(item.id)
                adapter.removeItem(item)
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
            },
            onItemClick = { item ->
                val intent = Intent(this, WebViewActivity::class.java)
                intent.putExtra("medicineName", item.medicineName)
                intent.putExtra("date", item.date)
                intent.putExtra("opening", formatValue(item.totalOpening))
                intent.putExtra("consumption", formatValue(item.totalConsumption))
                intent.putExtra("emergency", item.totalEmergency.toInt().toString())
                intent.putExtra("closing", formatValue(item.totalClosing))
                intent.putExtra("storeIssued", formatValue(item.totalStoreIssued))
                intent.putExtra("stockAvailable", item.stockAvailable)
                startActivity(intent)
            }
        )

        binding.recyclerCompiled.layoutManager = LinearLayoutManager(this)
        binding.recyclerCompiled.adapter = adapter

        binding.btnDeleteAll.setOnClickListener {
            db.deleteAllCompiledSummary()
            adapter.clearAll()
            Toast.makeText(this, "All data deleted!", Toast.LENGTH_SHORT).show()
        }
    }
}