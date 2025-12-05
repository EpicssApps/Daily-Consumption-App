package com.epicx.apps.dailyconsumptionformapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import androidx.core.database.sqlite.transaction
import android.database.Cursor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AppDatabase(context: Context) : SQLiteOpenHelper(context, "medicinedb", null, 5) { // bump to 5

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
        // NOTE: No global prepopulate here.
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Wipe for schema bump to 5 (safe reset if coming from <5). If you prefer migration, adjust accordingly.
        if (oldVersion < 5) {
            db.execSQL("DROP TABLE IF EXISTS medicines")
            db.execSQL("DROP TABLE IF EXISTS upload_flags")
            db.execSQL("DROP TABLE IF EXISTS compiled_summary")
            onCreate(db)
            return
        }

        // Legacy (future safety)
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
        if (oldVersion < 4) {
            // no-op here because we already drop/recreate above for <5
        }
    }

    // Sirf selected vehicle ke liye medicines ko zero se seed karne ka helper
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

    // Revert (delete) pending unsent consumption + emergency for a single medicine
    fun revertPendingForMedicine(vehicle: String, medicine: String) {
        val db = writableDatabase
        val cursor = db.rawQuery(
            "SELECT openingBalance, consumption, totalEmergency, closingBalance FROM medicines WHERE vehicleName=? AND medicineName=? LIMIT 1",
            arrayOf(vehicle, medicine)
        )
        if (cursor.moveToFirst()) {
            val opening = cursor.getString(0)?.toIntOrNull() ?: 0
            val cons = cursor.getString(1)?.toIntOrNull() ?: 0
            val emerg = cursor.getString(2)?.toIntOrNull() ?: 0
            val closing = cursor.getString(3)?.toIntOrNull() ?: 0
            val restoredClosing = (closing + cons + emerg).coerceAtLeast(0)
            db.execSQL(
                "UPDATE medicines SET consumption='0', totalEmergency='0', closingBalance=? WHERE vehicleName=? AND medicineName=?",
                arrayOf(restoredClosing.toString(), vehicle, medicine)
            )
        }
        cursor.close()
    }

    // Apply edited pending values (adjust closing accordingly)
    fun applyEditedPending(
        vehicle: String,
        medicine: String,
        newConsumption: Int,
        newEmergency: Int
    ) {
        val db = writableDatabase
        val cursor = db.rawQuery(
            "SELECT openingBalance, consumption, totalEmergency, closingBalance FROM medicines WHERE vehicleName=? AND medicineName=? LIMIT 1",
            arrayOf(vehicle, medicine)
        )
        if (cursor.moveToFirst()) {
            val oldCons = cursor.getString(1)?.toIntOrNull() ?: 0
            val oldEmerg = cursor.getString(2)?.toIntOrNull() ?: 0
            val currentClosing = cursor.getString(3)?.toIntOrNull() ?: 0
            val baseStock = currentClosing + oldCons + oldEmerg
            val newClosing = (baseStock - newConsumption - newEmergency).coerceAtLeast(0)
            db.execSQL(
                "UPDATE medicines SET consumption=?, totalEmergency=?, closingBalance=? WHERE vehicleName=? AND medicineName=?",
                arrayOf(
                    newConsumption.toString(),
                    newEmergency.toString(),
                    newClosing.toString(),
                    vehicle,
                    medicine
                )
            )
        }
        cursor.close()
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

    fun getMedicinesForVehicle(vehicle: String): List<FormData> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT vehicleName, medicineName, openingBalance, consumption, totalEmergency, closingBalance, storeIssued FROM medicines WHERE vehicleName=?",
            arrayOf(vehicle)
        )
        val list = mutableListOf<FormData>()
        while (cursor.moveToNext()) {
            list.add(
                FormData(
                    vehicleName = cursor.getString(0),
                    medicineName = cursor.getString(1),
                    openingBalance = cursor.getString(2),
                    consumption = cursor.getString(3),
                    totalEmergency = cursor.getString(4),
                    closingBalance = cursor.getString(5),
                    storeIssued = cursor.getString(6) ?: "0"
                )
            )
        }
        cursor.close()
        return list
    }

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

    //----------- Compiled Summary CRUD ------------

    // INT-only model now
    data class CompiledMedicineData(
        val medicineName: String,
        val totalConsumption: Int,
        val totalEmergency: Int,
        val totalOpening: Int,
        val totalClosing: Int,
        val totalStoreIssued: Int,
        val stockAvailable: Int
    )

    fun deleteAllCompiledSummary() {
        val db = writableDatabase
        db.execSQL("DELETE FROM compiled_summary")
    }

    fun deleteMedicineByName(vehicleName: String, medicineName: String) {
        val db = writableDatabase
        db.execSQL("DELETE FROM medicines WHERE vehicleName=? AND medicineName=?", arrayOf(vehicleName, medicineName))
    }

    fun hasDailyCompileAdded(date: String): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT uploaded FROM upload_flags WHERE date=? LIMIT 1",
            arrayOf(date)
        )
        val existsAndUploaded = cursor.moveToFirst() && (cursor.getInt(0) == 1)
        cursor.close()
        return existsAndUploaded
    }

    // Mark today's compiled data as saved (so it won't be added again)
    fun markDailyCompileAdded(date: String) {
        val db = writableDatabase
        db.execSQL(
            "INSERT OR REPLACE INTO upload_flags(date, uploaded) VALUES (?, ?)",
            arrayOf(date, 1)
        )
    }

// - consumption and totalEmergency -> ADD
// - totalOpening, totalClosing, totalStoreIssued, stockAvailable -> OVERWRITE (latest)
    fun insertOrAccumulateCompiledSummary(
        date: String,
        items: List<CompiledMedicineData>
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (item in items) {
                // Date ko ignore: sirf medicineName par check
                val cursor: Cursor = db.rawQuery(
                    "SELECT id, openingBalance, consumption, totalEmergency, closingBalance, storeIssued, stockAvailable FROM compiled_summary WHERE medicineName=? LIMIT 1",
                    arrayOf(item.medicineName)
                )
                if (cursor.moveToFirst()) {
                    val id = cursor.getInt(0)
                    val existingOpening = cursor.getInt(1)
                    val existingConsumption = cursor.getInt(2)
                    val existingEmergency = cursor.getInt(3)
                    val existingClosing = cursor.getInt(4)
                    val existingStoreIssued = cursor.getInt(5)
                    val existingStockAvailable = cursor.getInt(6)

                    // Accumulate only these
                    val newConsumption = existingConsumption + item.totalConsumption
                    val newEmergency = existingEmergency + item.totalEmergency

                    // Overwrite others with latest values
                    val newOpening = item.totalOpening
                    val newClosing = item.totalClosing
                    val newStoreIssued = item.totalStoreIssued
                    val newStockAvailable = item.stockAvailable

                    // Date ko bhi latest se overwrite kar do (optional)
                    db.execSQL(
                        "UPDATE compiled_summary SET date=?, openingBalance=?, consumption=?, totalEmergency=?, closingBalance=?, storeIssued=?, stockAvailable=? WHERE id=?",
                        arrayOf(date, newOpening, newConsumption, newEmergency, newClosing, newStoreIssued, newStockAvailable, id)
                    )
                } else {
                    // First time insert for this medicine
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
                            item.stockAvailable
                        )
                    )
                }
                cursor.close()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // Get rows for a specific date
    fun getCompiledSummaryByDate(date: String): List<CompiledMedicineDataWithId> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, date, medicineName, openingBalance, consumption, totalEmergency, closingBalance, storeIssued, stockAvailable FROM compiled_summary WHERE date=? ORDER BY medicineName ASC",
            arrayOf(date)
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

    // Helpers: date format
    private fun fmtDate(d: Date): String = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(d)

    // Get aggregated totals for last N days (including today), grouped by medicineName
    fun getCompiledSummaryForLastNDays(days: Int): List<CompiledMedicineData> {
        val endCal = Calendar.getInstance() // today
        val endDateStr = fmtDate(endCal.time)

        val startCal = Calendar.getInstance()
        startCal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        val startDateStr = fmtDate(startCal.time)

        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT medicineName, SUM(openingBalance), SUM(consumption), SUM(totalEmergency), SUM(closingBalance), SUM(storeIssued), MAX(stockAvailable) " +
                    "FROM compiled_summary " +
                    "WHERE date BETWEEN ? AND ? " +
                    "GROUP BY medicineName " +
                    "ORDER BY medicineName ASC",
            arrayOf(startDateStr, endDateStr)
        )
        val list = mutableListOf<CompiledMedicineData>()
        while (cursor.moveToNext()) {
            list.add(
                CompiledMedicineData(
                    medicineName = cursor.getString(0),
                    totalOpening = cursor.getInt(1),
                    totalConsumption = cursor.getInt(2),
                    totalEmergency = cursor.getInt(3),
                    totalClosing = cursor.getInt(4),
                    totalStoreIssued = cursor.getInt(5),
                    stockAvailable = cursor.getInt(6)
                )
            )
        }
        cursor.close()
        return list
    }

    fun getCompiledSummaryForLast15Days(): List<CompiledMedicineData> {
        return getCompiledSummaryForLastNDays(15)
    }

    fun getCompiledSummaryForLast30Days(): List<CompiledMedicineData> {
        return getCompiledSummaryForLastNDays(30)
    }
}