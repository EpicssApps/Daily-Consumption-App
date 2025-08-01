package com.epicx.apps.dailyconsumptionformapp

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.epicx.apps.dailyconsumptionformapp.databinding.RowSummaryItemBinding

class SummaryAdapter(
    private val list: List<FormData>,
    private val onItemClick: (FormData) -> Unit
) : RecyclerView.Adapter<SummaryAdapter.SummaryViewHolder>() {

    class SummaryViewHolder(val binding: RowSummaryItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SummaryViewHolder {
        val binding = RowSummaryItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SummaryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SummaryViewHolder, position: Int) {
        val item = list[position]
        holder.binding.textVehicle.text = item.vehicleName
        holder.binding.textMedicine.text = item.medicineName
        holder.binding.textOpening.text = item.openingBalance
        holder.binding.textConsumption.text = item.consumption
        holder.binding.textEmergency.text = item.totalEmergency
        holder.binding.textClosing.text = item.closingBalance

        holder.binding.root.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = list.size
}