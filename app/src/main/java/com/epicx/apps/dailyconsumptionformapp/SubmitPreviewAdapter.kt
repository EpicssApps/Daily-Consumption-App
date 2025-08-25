package com.epicx.apps.dailyconsumptionformapp

import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.epicx.apps.dailyconsumptionformapp.GoogleSheetsClient.SubmitItem

class SubmitPreviewAdapter(
    private val context: Context,
    private val vehicle: String,
    private val items: MutableList<SubmitItem>,
    private val onDataChanged: () -> Unit,
    private val onEditPersist: (medicine: String, newCons: Int, newEmerg: Int) -> Unit,
    private val onDeletePersist: (medicine: String) -> Unit
) : BaseAdapter() {

    private val isRs01 = vehicle.equals("RS-01", true)

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): SubmitItem = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val row = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_submit_preview, parent, false)

        val tvIndex = row.findViewById<TextView>(R.id.tvIndex)
        val tvName = row.findViewById<TextView>(R.id.tvName)
        val tvConsumption = row.findViewById<TextView>(R.id.tvConsumption)
        val tvEmergency = row.findViewById<TextView>(R.id.tvEmergency)
        val labelEmergency = row.findViewById<TextView>(R.id.labelEmergency)
        val btnEdit = row.findViewById<Button>(R.id.btnEdit)
        val btnDelete = row.findViewById<Button>(R.id.btnDelete)

        val item = items[position]

        tvIndex.text = (position + 1).toString()
        tvName.text = item.medicine
        tvConsumption.text = item.consumption.toString()
        tvEmergency.text = item.emergency.toString()

        if (isRs01) {
            tvEmergency.visibility = View.GONE
            labelEmergency.visibility = View.GONE
        } else {
            tvEmergency.visibility = View.VISIBLE
            labelEmergency.visibility = View.VISIBLE
        }

        btnEdit.setOnClickListener { showEditDialog(position) }
        btnDelete.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Delete?")
                .setMessage("Remove '${item.medicine}' from this submission?")
                .setPositiveButton("Delete") { d, _ ->
                    val medName = item.medicine
                    items.removeAt(position)
                    notifyDataSetChanged()
                    onDeletePersist(medName)          // persist deletion
                    onDataChanged()
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        return row
    }

    private fun showEditDialog(position: Int) {
        val item = items[position]
        val isRs01 = vehicle.equals("RS-01", true)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val etConsumption = EditText(context).apply {
            hint = "Consumption"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            setText(item.consumption.toString())
        }
        val etEmergency = EditText(context).apply {
            hint = "Emergency"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            setText(item.emergency.toString())
        }
        container.addView(TextView(context).apply {
            text = item.medicine
            textSize = 16f
        })
        container.addView(etConsumption)
        if (!isRs01) container.addView(etEmergency)

        AlertDialog.Builder(context)
            .setTitle("Edit Row")
            .setView(container)
            .setPositiveButton("Save") { d, _ ->
                val cons = etConsumption.text.toString().toIntOrNull() ?: 0
                val emerg = if (isRs01) 0 else etEmergency.text.toString().toIntOrNull() ?: 0
                if (cons <= 0 && emerg <= 0) {
                    Toast.makeText(context, "Nothing to keep (>=1 required)", Toast.LENGTH_LONG).show()
                } else {
                    items[position] = item.copy(consumption = cons, emergency = emerg)
                    notifyDataSetChanged()
                    onEditPersist(item.medicine, cons, emerg)   // persist edit
                    onDataChanged()
                }
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}