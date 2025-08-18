package com.epicx.apps.dailyconsumptionformapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.epicx.apps.dailyconsumptionformapp.databinding.RowSummaryItemBinding
import com.epicx.apps.dailyconsumptionformapp.FormConstants.specialDecimalMeds

class SummaryAdapter(
    private val list: List<FormData>,
    private val onlyShowBasicColumns: Boolean,
    private val onItemClick: (FormData) -> Unit
) : RecyclerView.Adapter<SummaryAdapter.SummaryViewHolder>() {

    class SummaryViewHolder(val binding: RowSummaryItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SummaryViewHolder {
        val binding = RowSummaryItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SummaryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SummaryViewHolder, position: Int) {
        val item = list[position]

        // Check if this medicine is in the special decimal list
        val isSpecial = specialDecimalMeds.contains(item.medicineName.trim())

        // Helper to format values
        fun formatValue(value: String?): String {
            val doubleValue = value?.toDoubleOrNull() ?: 0.0
            return if (isSpecial) {
                "%.2f".format(doubleValue)
            } else {
                doubleValue.toInt().toString()
            }
        }

        holder.binding.textVehicle.text = item.vehicleName
        holder.binding.textMedicine.text = item.medicineName

        // Apply formatting to all numerical values
        holder.binding.textOpening.text = formatValue(item.openingBalance)
        holder.binding.textClosing.text = formatValue(item.closingBalance)
        holder.binding.textConsumption.text = formatValue(item.consumption)
        holder.binding.textEmergency.text = item.totalEmergency

        // Always show consumption and emergency columns for RS-01 and all other vehicles
        holder.binding.textConsumption.visibility = View.VISIBLE
        holder.binding.textEmergency.visibility = View.VISIBLE
        holder.binding.labelConsumption.visibility = View.VISIBLE
        holder.binding.labelEmergency.visibility = View.VISIBLE

        // Show StoreIssued only for RS-01, hide for others
        if (item.vehicleName == "RS-01") {
            holder.binding.textStoreIssued.visibility = View.VISIBLE
            holder.binding.textStoreIssued.text = formatValue(item.storeIssued)
        } else {
            holder.binding.textStoreIssued.visibility = View.GONE
        }

        holder.binding.root.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = list.size
}