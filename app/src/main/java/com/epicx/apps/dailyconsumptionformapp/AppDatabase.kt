package com.epicx.apps.dailyconsumptionformapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import androidx.core.database.sqlite.transaction

class AppDatabase(context: Context) : SQLiteOpenHelper(context, "medicinedb", null, 3) { // version bumped to 3

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE medicines (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                vehicleName TEXT,
                medicineName TEXT,
                openingBalance TEXT,
                consumption TEXT,
                totalEmergency TEXT,
                closingBalance TEXT,
                storeIssued TEXT DEFAULT '0'
            );
        """)
        db.execSQL("""
            CREATE TABLE upload_flags (
                date TEXT PRIMARY KEY,
                uploaded INTEGER
            );
        """)
        db.execSQL("""
            CREATE TABLE compiled_summary (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT,
                medicineName TEXT,
                openingBalance INTEGER,
                consumption INTEGER,
                totalEmergency INTEGER,
                closingBalance INTEGER,
                storeIssued INTEGER,
                stockAvailable INTEGER
            );
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE medicines ADD COLUMN storeIssued TEXT DEFAULT '0'")
        }
        if (oldVersion < 3) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS compiled_summary (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    date TEXT,
                    medicineName TEXT,
                    openingBalance INTEGER,
                    consumption INTEGER,
                    totalEmergency INTEGER,
                    closingBalance INTEGER,
                    storeIssued INTEGER,
                    stockAvailable INTEGER
                );
            """)
        }
    }

    // CRUD for medicines
    fun addOrUpdateMedicine(vehicle: String, medicine: String, opening: String, consumption: String, emergency: String, closing: String) {
        val db = writableDatabase
        val cursor = db.rawQuery("SELECT id FROM medicines WHERE vehicleName=? AND medicineName=?", arrayOf(vehicle, medicine))
        if (cursor.moveToFirst()) {
            val id = cursor.getInt(0)
            db.execSQL("UPDATE medicines SET openingBalance=?, consumption=?, totalEmergency=?, closingBalance=? WHERE id=?",
                arrayOf(opening, consumption, emergency, closing, id))
        } else {
            db.execSQL("INSERT INTO medicines(vehicleName, medicineName, openingBalance, consumption, totalEmergency, closingBalance) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf(vehicle, medicine, opening, consumption, emergency, closing))
        }
        cursor.close()
    }

    fun updateStoreIssued(vehicle: String, medicine: String, issued: String) {
        val db = writableDatabase
        db.execSQL("UPDATE medicines SET storeIssued=? WHERE vehicleName=? AND medicineName=?",
            arrayOf(issued, vehicle, medicine))
    }

    fun getAllMedicines(): List<FormData> {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT vehicleName, medicineName, openingBalance, consumption, totalEmergency, closingBalance, storeIssued FROM medicines", null)
        val list = mutableListOf<FormData>()
        while (cursor.moveToNext()) {
            list.add(FormData(
                vehicleName = cursor.getString(0),
                medicineName = cursor.getString(1),
                openingBalance = cursor.getString(2),
                consumption = cursor.getString(3),
                totalEmergency = cursor.getString(4),
                closingBalance = cursor.getString(5),
                storeIssued = cursor.getString(6) ?: "0"
            ))
        }
        cursor.close()
        return list
    }

    // NEW: Single-record getters to fix unresolved reference in FormActivity
    fun getMedicineOpening(vehicle: String, medicine: String): String {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT openingBalance FROM medicines WHERE vehicleName=? AND medicineName=? LIMIT 1",
            arrayOf(vehicle, medicine)
        )
        val value = if (cursor.moveToFirst()) cursor.getString(0) ?: "0" else "0"
        cursor.close()
        return value
    }

    fun getMedicineClosing(vehicle: String, medicine: String): String {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT closingBalance FROM medicines WHERE vehicleName=? AND medicineName=? LIMIT 1",
            arrayOf(vehicle, medicine)
        )
        val value = if (cursor.moveToFirst()) cursor.getString(0) ?: "0" else "0"
        cursor.close()
        return value
    }

    // Optional helper (not required by FormActivity but handy)
    fun getMedicineRecord(vehicle: String, medicine: String): FormData? {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT vehicleName, medicineName, openingBalance, consumption, totalEmergency, closingBalance, storeIssued FROM medicines WHERE vehicleName=? AND medicineName=? LIMIT 1",
            arrayOf(vehicle, medicine)
        )
        val item = if (cursor.moveToFirst()) {
            FormData(
                vehicleName = cursor.getString(0),
                medicineName = cursor.getString(1),
                openingBalance = cursor.getString(2),
                consumption = cursor.getString(3),
                totalEmergency = cursor.getString(4),
                closingBalance = cursor.getString(5),
                storeIssued = cursor.getString(6) ?: "0"
            )
        } else null
        cursor.close()
        return item
    }

    fun checkUploadFlag(date: String): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT uploaded FROM upload_flags WHERE date=?", arrayOf(date))
        val uploaded = cursor.moveToFirst() && cursor.getInt(0) == 1
        cursor.close()
        return uploaded
    }

    fun resetConsumptionAndEmergency() {
        val db = writableDatabase
        db.execSQL("UPDATE medicines SET consumption = '0', totalEmergency = '0'")
    }

    fun setUploadFlag(date: String) {
        val db = writableDatabase
        db.execSQL("INSERT OR REPLACE INTO upload_flags(date, uploaded) VALUES (?, 1)", arrayOf(date))
    }

    fun exportToCSV(file: File): Boolean {
        return try {
            val medicines = getAllMedicines()
            file.printWriter().use { out ->
                out.println("vehicleName,medicineName,openingBalance,consumption,totalEmergency,closingBalance,storeIssued")
                for (item in medicines) {
                    out.println("${item.vehicleName},${item.medicineName},${item.openingBalance},${item.consumption},${item.totalEmergency},${item.closingBalance},${item.storeIssued}")
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun importFromCSV(file: File): Boolean {
        return try {
            val lines = file.readLines()
            if (lines.isEmpty()) return false
            val db = writableDatabase
            db.execSQL("DELETE FROM medicines")
            for (i in 1 until lines.size) {
                val parts = lines[i].split(",")
                if (parts.size >= 6) {
                    val storeIssued = if (parts.size >= 7) parts[6] else "0"
                    val args = arrayOf(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], storeIssued)
                    db.execSQL(
                        "INSERT INTO medicines(vehicleName, medicineName, openingBalance, consumption, totalEmergency, closingBalance, storeIssued) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        args
                    )
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun prepopulateMedicinesForVehicle(vehicleName: String, medicineList: List<String>) {
        val db = writableDatabase
        val cursor = db.rawQuery(
            "SELECT medicineName FROM medicines WHERE vehicleName = ?",
            arrayOf(vehicleName)
        )
        val existing = mutableSetOf<String>()
        while (cursor.moveToNext()) {
            existing.add(cursor.getString(0))
        }
        cursor.close()
        db.transaction {
            try {
                for (med in medicineList) {
                    if (!existing.contains(med)) {
                        execSQL(
                            "INSERT INTO medicines(vehicleName, medicineName, openingBalance, consumption, totalEmergency, closingBalance, storeIssued) VALUES (?, ?, '0', '0', '0', '0', '0')",
                            arrayOf(vehicleName, med)
                        )
                    }
                }
            } finally {
            }
        }
    }

    //----------- Compiled Summary CRUD ------------

    data class CompiledMedicineData(
        val medicineName: String,
        val totalConsumption: Double,
        val totalEmergency: Double,
        val totalOpening: Double,
        val totalClosing: Double,
        val totalStoreIssued: Double,
        val stockAvailable: String
    )

    fun deleteAllCompiledSummary() {
        val db = writableDatabase
        db.execSQL("DELETE FROM compiled_summary")
    }
    fun deleteMedicineByName(vehicleName: String, medicineName: String) {
        val db = writableDatabase
        db.execSQL("DELETE FROM medicines WHERE vehicleName=? AND medicineName=?", arrayOf(vehicleName, medicineName))
    }
    fun insertCompiledSummary(
        date: String,
        items: List<CompiledMedicineData>
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (item in items) {
                db.execSQL(
                    "INSERT INTO compiled_summary(date, medicineName, openingBalance, consumption, totalEmergency, closingBalance, storeIssued, stockAvailable) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(
                        date,
                        item.medicineName,
                        item.totalOpening,
                        item.totalConsumption,
                        item.totalEmergency,
                        item.totalClosing,
                        item.totalStoreIssued,
                        item.stockAvailable.toIntOrNull() ?: 0
                    )
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAllCompiledSummary(): List<CompiledMedicineDataWithId> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, date, medicineName, openingBalance, consumption, totalEmergency, closingBalance, storeIssued, stockAvailable FROM compiled_summary ORDER BY date DESC, id DESC", null
        )
        val list = mutableListOf<CompiledMedicineDataWithId>()
        while (cursor.moveToNext()) {
            list.add(
                CompiledMedicineDataWithId(
                    id = cursor.getInt(0),
                    date = cursor.getString(1),
                    medicineName = cursor.getString(2),
                    totalOpening = cursor.getDouble(3),
                    totalConsumption = cursor.getDouble(4),
                    totalEmergency = cursor.getDouble(5),
                    totalClosing = cursor.getDouble(6),
                    totalStoreIssued = cursor.getDouble(7),
                    stockAvailable = cursor.getInt(8).toString()
                )
            )
        }
        cursor.close()
        return list
    }

    fun deleteCompiledSummaryById(id: Int) {
        val db = writableDatabase
        db.execSQL("DELETE FROM compiled_summary WHERE id=?", arrayOf(id))
    }
}