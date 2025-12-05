package com.epicx.apps.dailyconsumptionformapp

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ReportArchiveDatabase(context: Context) :
    SQLiteOpenHelper(context, "report_archive.db", null, 2) { // bump to 2

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE archive_compiled (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT,               -- yyyy-MM-dd
                medicineName TEXT,
                openingBalance INTEGER,
                consumption INTEGER,
                totalEmergency INTEGER,
                closingBalance INTEGER,
                storeIssued INTEGER,
                stockAvailable INTEGER
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_archive_date ON archive_compiled(date)")
        db.execSQL("CREATE INDEX idx_archive_med ON archive_compiled(medicineName)")
        db.execSQL("CREATE INDEX idx_archive_date_med ON archive_compiled(date, medicineName)")

        // NEW: monthly archive (immutable wrt half deletions)
        db.execSQL(
            """
            CREATE TABLE monthly_archive (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                year INTEGER,
                month INTEGER,           -- 1..12
                medicineName TEXT,
                openingBalance INTEGER,
                consumption INTEGER,
                totalEmergency INTEGER,
                closingBalance INTEGER,
                storeIssued INTEGER,
                stockAvailable INTEGER,
                lastUpdatedDate TEXT     -- yyyy-MM-dd (latest day seen in this month)
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_monthly_key ON monthly_archive(year, month, medicineName)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS monthly_archive (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    year INTEGER,
                    month INTEGER,
                    medicineName TEXT,
                    openingBalance INTEGER,
                    consumption INTEGER,
                    totalEmergency INTEGER,
                    closingBalance INTEGER,
                    storeIssued INTEGER,
                    stockAvailable INTEGER,
                    lastUpdatedDate TEXT
                );
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_monthly_key ON monthly_archive(year, month, medicineName)")
        }
    }

    // Snapshot insert-or-accumulate per (dateISO, medicineName)
    // consumption + totalEmergency = ADD; others = OVERWRITE latest
    fun insertOrAccumulateSnapshot(dateISO: String, items: List<AppDatabase.CompiledMedicineData>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (item in items) {
                val cursor: Cursor = db.rawQuery(
                    "SELECT id, openingBalance, consumption, totalEmergency, closingBalance, storeIssued, stockAvailable " +
                            "FROM archive_compiled WHERE date=? AND medicineName=? LIMIT 1",
                    arrayOf(dateISO, item.medicineName)
                )
                if (cursor.moveToFirst()) {
                    val id = cursor.getInt(0)
                    val existingConsumption = cursor.getInt(2)
                    val existingEmergency = cursor.getInt(3)
                    val newConsumption = existingConsumption + item.totalConsumption
                    val newEmergency = existingEmergency + item.totalEmergency
                    db.execSQL(
                        "UPDATE archive_compiled SET openingBalance=?, consumption=?, totalEmergency=?, closingBalance=?, storeIssued=?, stockAvailable=? WHERE id=?",
                        arrayOf(item.totalOpening, newConsumption, newEmergency, item.totalClosing, item.totalStoreIssued, item.stockAvailable, id)
                    )
                } else {
                    db.execSQL(
                        "INSERT INTO archive_compiled(date, medicineName, openingBalance, consumption, totalEmergency, closingBalance, storeIssued, stockAvailable) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            dateISO,
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

    private fun isoFormat(d: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)
    private fun isoOf(year: Int, month1: Int, day: Int): String {
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month1 - 1)
        cal.set(Calendar.DAY_OF_MONTH, day)
        return isoFormat(cal.time)
    }
    private fun lastDayOfMonth(year: Int, month1: Int): Int {
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month1 - 1)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    // Aggregate totals in [startIso, endIso]
    private fun getAggregatedBetween(startIso: String, endIso: String): List<AppDatabase.CompiledMedicineData> {
        val db = readableDatabase
        val sumCursor = db.rawQuery(
            """
            SELECT medicineName, SUM(consumption) AS totalCons, SUM(totalEmergency) AS totalEmerg, MAX(date) AS lastDate
            FROM archive_compiled
            WHERE date BETWEEN ? AND ?
            GROUP BY medicineName
            ORDER BY medicineName ASC
            """.trimIndent(),
            arrayOf(startIso, endIso)
        )
        val result = mutableListOf<AppDatabase.CompiledMedicineData>()
        while (sumCursor.moveToNext()) {
            val med = sumCursor.getString(0)
            val totalCons = sumCursor.getInt(1)
            val totalEmerg = sumCursor.getInt(2)
            val lastDate = sumCursor.getString(3) ?: endIso
            val lastCursor = db.rawQuery(
                "SELECT openingBalance, closingBalance, storeIssued, stockAvailable FROM archive_compiled WHERE medicineName=? AND date=? LIMIT 1",
                arrayOf(med, lastDate)
            )
            var opening = 0; var closing = 0; var storeIssued = 0; var stockAvailable = 0
            if (lastCursor.moveToFirst()) {
                opening = lastCursor.getInt(0)
                closing = lastCursor.getInt(1)
                storeIssued = lastCursor.getInt(2)
                stockAvailable = lastCursor.getInt(3)
            }
            lastCursor.close()
            result.add(
                AppDatabase.CompiledMedicineData(
                    medicineName = med,
                    totalConsumption = totalCons,
                    totalEmergency = totalEmerg,
                    totalOpening = opening,
                    totalClosing = closing,
                    totalStoreIssued = storeIssued,
                    stockAvailable = stockAvailable
                )
            )
        }
        sumCursor.close()
        return result
    }

    fun getAggregatedForFirstHalf(year: Int, month1: Int): List<AppDatabase.CompiledMedicineData> {
        val startIso = isoOf(year, month1, 1)
        val endIso = isoOf(year, month1, 15)
        return getAggregatedBetween(startIso, endIso)
    }
    fun getAggregatedForSecondHalf(year: Int, month1: Int): List<AppDatabase.CompiledMedicineData> {
        val startIso = isoOf(year, month1, 16)
        val endIso = isoOf(year, month1, lastDayOfMonth(year, month1))
        return getAggregatedBetween(startIso, endIso)
    }
    fun getAggregatedForMonth(year: Int, month1: Int): List<AppDatabase.CompiledMedicineData> {
        val startIso = isoOf(year, month1, 1)
        val endIso = isoOf(year, month1, lastDayOfMonth(year, month1))
        return getAggregatedBetween(startIso, endIso)
    }

    // NEW: Monthly archive upsert per day compile (immutable vs half deletions)
    fun insertOrAccumulateMonthlyTotals(dateISO: String, items: List<AppDatabase.CompiledMedicineData>) {
        val (year, month1) = parseYearMonth(dateISO)
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (item in items) {
                val cursor = db.rawQuery(
                    "SELECT id, openingBalance, consumption, totalEmergency, closingBalance, storeIssued, stockAvailable, lastUpdatedDate " +
                            "FROM monthly_archive WHERE year=? AND month=? AND medicineName=? LIMIT 1",
                    arrayOf(year.toString(), month1.toString(), item.medicineName)
                )
                if (cursor.moveToFirst()) {
                    val id = cursor.getInt(0)
                    val existingConsumption = cursor.getInt(2)
                    val existingEmergency = cursor.getInt(3)
                    val newConsumption = existingConsumption + item.totalConsumption
                    val newEmergency = existingEmergency + item.totalEmergency
                    // Overwrite latest fields
                    db.execSQL(
                        "UPDATE monthly_archive SET openingBalance=?, consumption=?, totalEmergency=?, closingBalance=?, storeIssued=?, stockAvailable=?, lastUpdatedDate=? WHERE id=?",
                        arrayOf(item.totalOpening, newConsumption, newEmergency, item.totalClosing, item.totalStoreIssued, item.stockAvailable, dateISO, id)
                    )
                } else {
                    db.execSQL(
                        "INSERT INTO monthly_archive(year, month, medicineName, openingBalance, consumption, totalEmergency, closingBalance, storeIssued, stockAvailable, lastUpdatedDate) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            year, month1, item.medicineName,
                            item.totalOpening, item.totalConsumption, item.totalEmergency,
                            item.totalClosing, item.totalStoreIssued, item.stockAvailable,
                            dateISO
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

    // Get monthly aggregated rows from monthly_archive (safe even if halves were deleted)
    fun getMonthlyAggregated(year: Int, month1: Int): List<AppDatabase.CompiledMedicineData> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT medicineName, openingBalance, consumption, totalEmergency, closingBalance, storeIssued, stockAvailable " +
                    "FROM monthly_archive WHERE year=? AND month=? ORDER BY medicineName ASC",
            arrayOf(year.toString(), month1.toString())
        )
        val list = mutableListOf<AppDatabase.CompiledMedicineData>()
        while (cursor.moveToNext()) {
            list.add(
                AppDatabase.CompiledMedicineData(
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

    // Permanent delete for one medicine in half-month range
    fun deleteHalfRangeForMedicine(year: Int, month1: Int, firstHalf: Boolean, medicineName: String) {
        val startIso = isoOf(year, month1, if (firstHalf) 1 else 16)
        val endIso = isoOf(year, month1, if (firstHalf) 15 else lastDayOfMonth(year, month1))
        val db = writableDatabase
        db.execSQL("DELETE FROM archive_compiled WHERE medicineName=? AND date BETWEEN ? AND ?", arrayOf(medicineName, startIso, endIso))
    }

    // Permanent delete ALL medicines in half-month range
    fun deleteHalfRangeAll(year: Int, month1: Int, firstHalf: Boolean) {
        val startIso = isoOf(year, month1, if (firstHalf) 1 else 16)
        val endIso = isoOf(year, month1, if (firstHalf) 15 else lastDayOfMonth(year, month1))
        val db = writableDatabase
        db.execSQL("DELETE FROM archive_compiled WHERE date BETWEEN ? AND ?", arrayOf(startIso, endIso))
    }

    // Monthly delete helpers
    fun deleteMonthlyForMedicine(year: Int, month1: Int, medicineName: String) {
        val db = writableDatabase
        db.execSQL("DELETE FROM monthly_archive WHERE year=? AND month=? AND medicineName=?", arrayOf(year, month1, medicineName))
    }
    fun deleteMonthlyAll(year: Int, month1: Int) {
        val db = writableDatabase
        db.execSQL("DELETE FROM monthly_archive WHERE year=? AND month=?", arrayOf(year, month1))
    }

    private fun parseYearMonth(dateISO: String): Pair<Int, Int> {
        // dateISO format: yyyy-MM-dd
        val y = dateISO.substring(0, 4).toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)
        val m = dateISO.substring(5, 7).toIntOrNull() ?: (Calendar.getInstance().get(Calendar.MONTH) + 1)
        return y to m
    }
}