package com.epicx.apps.dailyconsumptionformapp

data class CompiledMedicineDataWithId(
    val id: Int,
    val date: String,
    val medicineName: String,
    val totalConsumption: Double,
    val totalEmergency: Double,
    val totalOpening: Double,
    val totalClosing: Double,
    val totalStoreIssued: Double,
    val stockAvailable: String
)
