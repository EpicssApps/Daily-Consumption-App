package com.epicx.apps.dailyconsumptionformapp

import android.app.Activity
import android.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object SubmitPreviewDialogHelper {

    fun show(
        activity: Activity,
        vehicle: String,
        originalItems: List<GoogleSheetsClient.SubmitItem>,
        onConfirm: (List<GoogleSheetsClient.SubmitItem>) -> Unit,
        onEditPersist: (medicine: String, newCons: Int, newEmerg: Int) -> Unit,
        onDeletePersist: (medicine: String) -> Unit
    ) {
        val working = originalItems.map { it.copy() }.toMutableList()
        val isRs01 = vehicle.equals("RS-01", true)

        val inflater = activity.layoutInflater
        val view = inflater.inflate(R.layout.dialog_submit_preview, null)
        val tvHeader = view.findViewById<TextView>(R.id.tvHeader)
        val tvFooter = view.findViewById<TextView>(R.id.tvFooter)
        val listView = view.findViewById<ListView>(R.id.listPreview)
        val btnUpload = view.findViewById<Button>(R.id.btnUpload)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)

        tvHeader.text = "Vehicle: $vehicle"

        fun recomputeFooter() {
            val total = working.size
            val sumCons = working.sumOf { it.consumption }
            val sumEmerg = working.sumOf { it.emergency }
            tvFooter.text = if (isRs01) {
                "Rows: $total   Total Consumption: $sumCons"
            } else {
                "Rows: $total   Total Consumption: $sumCons   Total Emergency: $sumEmerg"
            }
            btnUpload.isEnabled = working.isNotEmpty()
        }

        val adapter = SubmitPreviewAdapter(
            context = activity,
            vehicle = vehicle,
            items = working,
            onDataChanged = { recomputeFooter() },
            onEditPersist = { med, cons, emerg ->
                onEditPersist(med, cons, emerg)
            },
            onDeletePersist = { med ->
                onDeletePersist(med)
            }
        )
        listView.adapter = adapter
        recomputeFooter()

        val dialog = MaterialAlertDialogBuilder(activity, R.style.AlertDialogTheme)
            .setTitle("Confirm Submission")
            .setView(view)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnUpload.setOnClickListener {
            dialog.dismiss()
            onConfirm(working.toList())
        }

        dialog.show()
    }
}