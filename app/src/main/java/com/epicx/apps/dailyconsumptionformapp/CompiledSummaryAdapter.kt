package com.epicx.apps.dailyconsumptionformapp

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.epicx.apps.dailyconsumptionformapp.databinding.ItemCompiledSummaryBinding

class CompiledSummaryAdapter(
    private val items: MutableList<CompiledMedicineDataWithId>,
    private val onDeleteClicked: (CompiledMedicineDataWithId) -> Unit,
    private val onItemClick: (CompiledMedicineDataWithId) -> Unit
) : RecyclerView.Adapter<CompiledSummaryAdapter.CompiledSummaryViewHolder>() {

    fun clearAll() {
        items.clear()
        notifyDataSetChanged()
    }

    inner class CompiledSummaryViewHolder(val binding: ItemCompiledSummaryBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CompiledSummaryViewHolder {
        val binding = ItemCompiledSummaryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CompiledSummaryViewHolder(binding)
    }

    // Display values exactly as stored in DB (no halving)
    fun formatValue(medicineName: String, value: Double): String {
        return value.toInt().toString()
    }

    override fun onBindViewHolder(holder: CompiledSummaryViewHolder, position: Int) {
        val item = items[position]

        with(holder.binding) {
            tvSerial.text = (itemCount - position).toString()
            tvDate.text = item.date
            tvMedicine.text = item.medicineName
            tvOpening.text = formatValue(item.medicineName, item.totalOpening)
            tvConsumption.text = formatValue(item.medicineName, item.totalConsumption)
            tvEmergency.text = item.totalEmergency.toInt().toString()
            tvClosing.text = formatValue(item.medicineName, item.totalClosing)
            tvStoreIssued.text = formatValue(item.medicineName, item.totalStoreIssued)
            tvStockAvailable.text = item.stockAvailable

            btnDelete.setOnClickListener {
                onDeleteClicked(item)
            }
            holder.itemView.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun removeItem(item: CompiledMedicineDataWithId) {
        val index = items.indexOf(item)
        if (index >= 0) {
            items.removeAt(index)
            notifyItemRemoved(index)
        }
    }
}