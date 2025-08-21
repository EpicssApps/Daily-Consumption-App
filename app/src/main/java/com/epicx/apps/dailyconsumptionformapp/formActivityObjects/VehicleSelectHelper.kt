package com.epicx.apps.dailyconsumptionformapp.formActivityObjects

import com.epicx.apps.dailyconsumptionformapp.AppDatabase
import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.text.InputType
import android.widget.*
import com.epicx.apps.dailyconsumptionformapp.FormConstants

object VehicleSelectHelper {

    fun showDialog(
        activity: Activity,
        prefs: SharedPreferences,
        vehicleList: List<String>,
        vehicleSpinner: Spinner,
        db: AppDatabase,
        editEmergency: EditText,
        textEmergencyLabel: TextView,
        layoutStoreIssued: LinearLayout,
        issueToVehicles: Button,
        rs01Password: String = "FDR-1049*",
        onVehicleSelected: (String) -> Unit,          // e.g. { defaultVehicle = it }
        invalidateOptionsMenu: () -> Unit,            // e.g. { invalidateOptionsMenu() }
        onAfterSelection: (String) -> Unit = {}       // optional: e.g. sync call after selection
    ) {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle("Apni Vehicle Select Karein")
        builder.setCancelable(false)
        builder.setSingleChoiceItems(vehicleList.toTypedArray(), -1) { dialog, which ->
            val selected = vehicleList[which]

            if (selected == "RS-01") {
                val passwordInput = EditText(activity).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    hint = "Enter password"
                }
                AlertDialog.Builder(activity)
                    .setTitle("Password Required")
                    .setMessage("RS-01 select karne ke liye password enter karein.")
                    .setView(passwordInput)
                    .setPositiveButton("OK") { passDialog, _ ->
                        val password = passwordInput.text.toString()
                        if (password == rs01Password) {
                            applySelection(
                                activity = activity,
                                prefs = prefs,
                                selected = selected,
                                vehicleList = vehicleList,
                                vehicleSpinner = vehicleSpinner,
                                db = db,
                                editEmergency = editEmergency,
                                textEmergencyLabel = textEmergencyLabel,
                                layoutStoreIssued = layoutStoreIssued,
                                issueToVehicles = issueToVehicles,
                                onVehicleSelected = onVehicleSelected,
                                invalidateOptionsMenu = invalidateOptionsMenu,
                                onAfterSelection = onAfterSelection
                            )
                            passDialog.dismiss()
                            dialog.dismiss()
                        } else {
                            AlertDialog.Builder(activity)
                                .setTitle("Wrong Password")
                                .setMessage("Password ghalat hai. Dobara try karein.")
                                .setPositiveButton("OK") { _, _ ->
                                    // Re-open vehicle dialog
                                    showDialog(
                                        activity = activity,
                                        prefs = prefs,
                                        vehicleList = vehicleList,
                                        vehicleSpinner = vehicleSpinner,
                                        db = db,
                                        editEmergency = editEmergency,
                                        textEmergencyLabel = textEmergencyLabel,
                                        layoutStoreIssued = layoutStoreIssued,
                                        issueToVehicles = issueToVehicles,
                                        rs01Password = rs01Password,
                                        onVehicleSelected = onVehicleSelected,
                                        invalidateOptionsMenu = invalidateOptionsMenu,
                                        onAfterSelection = onAfterSelection
                                    )
                                }
                                .show()
                            passDialog.dismiss()
                            dialog.dismiss()
                        }
                    }
                    .setNegativeButton("Cancel") { passDialog, _ ->
                        // Re-open vehicle dialog
                        showDialog(
                            activity = activity,
                            prefs = prefs,
                            vehicleList = vehicleList,
                            vehicleSpinner = vehicleSpinner,
                            db = db,
                            editEmergency = editEmergency,
                            textEmergencyLabel = textEmergencyLabel,
                            layoutStoreIssued = layoutStoreIssued,
                            issueToVehicles = issueToVehicles,
                            rs01Password = rs01Password,
                            onVehicleSelected = onVehicleSelected,
                            invalidateOptionsMenu = invalidateOptionsMenu,
                            onAfterSelection = onAfterSelection
                        )
                        passDialog.dismiss()
                        dialog.dismiss()
                    }
                    .show()
            } else {
                applySelection(
                    activity = activity,
                    prefs = prefs,
                    selected = selected,
                    vehicleList = vehicleList,
                    vehicleSpinner = vehicleSpinner,
                    db = db,
                    editEmergency = editEmergency,
                    textEmergencyLabel = textEmergencyLabel,
                    layoutStoreIssued = layoutStoreIssued,
                    issueToVehicles = issueToVehicles,
                    onVehicleSelected = onVehicleSelected,
                    invalidateOptionsMenu = invalidateOptionsMenu,
                    onAfterSelection = onAfterSelection
                )
                dialog.dismiss()
            }
        }
        builder.show()
    }

    private fun applySelection(
        activity: Activity,
        prefs: SharedPreferences,
        selected: String,
        vehicleList: List<String>,
        vehicleSpinner: Spinner,
        db: AppDatabase,
        editEmergency: EditText,
        textEmergencyLabel: TextView,
        layoutStoreIssued: LinearLayout,
        issueToVehicles: Button,
        onVehicleSelected: (String) -> Unit,
        invalidateOptionsMenu: () -> Unit,
        onAfterSelection: (String) -> Unit
    ) {
        // Save and update UI selection
        prefs.edit().putString("default_vehicle", selected).apply()
        onVehicleSelected(selected)

        val index = vehicleList.indexOf(selected)
        vehicleSpinner.setSelection(if (index >= 0) index else 0)
        vehicleSpinner.isEnabled = false

        // Ensure medicines pre-populated
        db.prepopulateMedicinesForVehicle(selected, FormConstants.medicineList)

        // Update emergency/store issued visibility per vehicle
        updateEmergencyVisibilityInside(
            vehicle = selected,
            editEmergency = editEmergency,
            textEmergencyLabel = textEmergencyLabel,
            layoutStoreIssued = layoutStoreIssued,
            issueToVehicles = issueToVehicles
        )

        // Refresh menu visibility
        invalidateOptionsMenu()

        // Optional: anything after selection (e.g., sync)
        onAfterSelection(selected)
    }

    private fun updateEmergencyVisibilityInside(
        vehicle: String,
        editEmergency: EditText,
        textEmergencyLabel: TextView,
        layoutStoreIssued: LinearLayout,
        issueToVehicles: Button
    ) {
        if (vehicle == "RS-01") {
            editEmergency.visibility = android.view.View.GONE
            textEmergencyLabel.visibility = android.view.View.GONE
            layoutStoreIssued.visibility = android.view.View.VISIBLE
            issueToVehicles.visibility = android.view.View.VISIBLE
        } else {
            editEmergency.visibility = android.view.View.VISIBLE
            textEmergencyLabel.visibility = android.view.View.VISIBLE
            layoutStoreIssued.visibility = android.view.View.GONE
            issueToVehicles.visibility = android.view.View.GONE
        }
    }
}