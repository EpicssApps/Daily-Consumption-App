package com.epicx.apps.dailyconsumptionformapp.formActivityObjects

import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object EmergencyVisibilityHelper {

    fun update(
        editEmergency: EditText,
        textEmergencyLabel: TextView,
        vehicle: String,
        layoutStoreIssued: LinearLayout,
        issueToVehicles: Button,
        rs01Vehicle: String = "RS-01",
        btnSendToMonthly: Button
    ) {
        if (vehicle == rs01Vehicle) {
            editEmergency.visibility = View.GONE
            textEmergencyLabel.visibility = View.GONE
            layoutStoreIssued.visibility = View.VISIBLE
            issueToVehicles.visibility = View.VISIBLE
            btnSendToMonthly.visibility = View.VISIBLE
        } else {
            editEmergency.visibility = View.VISIBLE
            textEmergencyLabel.visibility = View.VISIBLE
            layoutStoreIssued.visibility = View.GONE
            issueToVehicles.visibility = View.GONE
            btnSendToMonthly.visibility = View.GONE
        }
    }
}