package com.epicx.apps.dailyconsumptionformapp.objects

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import androidx.core.widget.addTextChangedListener
import com.epicx.apps.dailyconsumptionformapp.R

object MedicineDialogUtils {
    /**
     * Show a searchable medicine picker dialog.
     *
     * @param context The activity or context to use (usually 'this' from Activity).
     * @param newMedicineList The list of all medicines.
     * @param editMedicine The EditText to set the selected medicine.
     */
    fun showMedicineDialog(
        context: Context,
        medicineList: List<String>,
        editMedicine: EditText
    ) {
        val builder = AlertDialog.Builder(context)
        val inflater = LayoutInflater.from(context)
        val dialogView = inflater.inflate(R.layout.dialog_medicine_picker, null)
        builder.setView(dialogView)
        val dialog = builder.create()

        val searchEdit = dialogView.findViewById<EditText>(R.id.edit_search)
        val listView = dialogView.findViewById<ListView>(R.id.list_medicines)

        var filteredList = medicineList
        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, filteredList)
        listView.adapter = adapter

        searchEdit.addTextChangedListener {
            val s = it?.toString() ?: ""
            filteredList = medicineList.filter { m -> m.contains(s, ignoreCase = true) }
            listView.adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, filteredList)
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            editMedicine.setText(filteredList[position])
            dialog.dismiss()
        }
        dialog.show()
    }
}