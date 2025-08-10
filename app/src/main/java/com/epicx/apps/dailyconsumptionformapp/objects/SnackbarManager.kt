package com.epicx.apps.dailyconsumptionformapp.objects

import com.google.android.material.snackbar.Snackbar

object SnackbarManager {
    private var snackbar: Snackbar? = null

    fun snackbar( snackbar: Snackbar?) {
        snackbar?.setAction("Dismiss") {
            snackbar.dismiss()
        }
    }
    fun snackbarInBillActivity(snackbar: Snackbar?) {
        snackbar?.setAction("Dismiss") {
            snackbar.dismiss()
        }
    }
}