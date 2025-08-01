package com.epicx.apps.dailyconsumptionformapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

class AppDatabase(context: Context) : SQLiteOpenHelper(context, "medicinedb", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE medicines (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                vehicleName TEXT,
                medicineName TEXT,
                openingBalance TEXT,
                consumption TEXT,
                totalEmergency TEXT,
                closingBalance TEXT
            );
        """)
        db.execSQL("""
            CREATE TABLE upload_flags (
                date TEXT PRIMARY KEY,
                uploaded INTEGER
            );
        """)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS medicines")
        db.execSQL("DROP TABLE IF EXISTS upload_flags")
        onCreate(db)
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
    fun getAllMedicines(): List<FormData> {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT vehicleName, medicineName, openingBalance, consumption, totalEmergency, closingBalance FROM medicines", null)
        val list = mutableListOf<FormData>()
        while (cursor.moveToNext()) {
            list.add(FormData(
                vehicleName = cursor.getString(0),
                medicineName = cursor.getString(1),
                openingBalance = cursor.getString(2),
                consumption = cursor.getString(3),
                totalEmergency = cursor.getString(4),
                closingBalance = cursor.getString(5)
            ))
        }
        cursor.close()
        return list
    }
    // Upload flag functions
    fun checkUploadFlag(date: String): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT uploaded FROM upload_flags WHERE date=?", arrayOf(date))
        val uploaded = cursor.moveToFirst() && cursor.getInt(0) == 1
        cursor.close()
        return uploaded
    }
    fun setUploadFlag(date: String) {
        val db = writableDatabase
        db.execSQL("INSERT OR REPLACE INTO upload_flags(date, uploaded) VALUES (?, 1)", arrayOf(date))
    }
    // Export as CSV
    fun exportToCSV(file: File): Boolean {
        return try {
            val medicines = getAllMedicines()
            file.printWriter().use { out ->
                out.println("vehicleName,medicineName,openingBalance,consumption,totalEmergency,closingBalance")
                for (item in medicines) {
                    out.println("${item.vehicleName},${item.medicineName},${item.openingBalance},${item.consumption},${item.totalEmergency},${item.closingBalance}")
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    // Import from CSV
    fun importFromCSV(file: File): Boolean {
        return try {
            val lines = file.readLines()
            if (lines.isEmpty()) return false
            val db = writableDatabase
            db.execSQL("DELETE FROM medicines") // Purana data hata do
            for (i in 1 until lines.size) { // pehli line headers hai
                val parts = lines[i].split(",")
                if (parts.size == 6) {
                    addOrUpdateMedicine(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5])
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}