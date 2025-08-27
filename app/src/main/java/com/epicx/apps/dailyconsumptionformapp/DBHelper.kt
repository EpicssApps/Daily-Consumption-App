package com.epicx.apps.dailyconsumptionformapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DBHelper(ctx: Context?) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VER) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE issue_items (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "vehicle TEXT NOT NULL," +
                    "medicine TEXT NOT NULL," +
                    "qty INTEGER NOT NULL," +
                    "created_at INTEGER NOT NULL," +
                    "uploaded INTEGER NOT NULL DEFAULT 0" +
                    ")"
        )
        db.execSQL("CREATE INDEX idx_issue_vehicle_uploaded ON issue_items(vehicle, uploaded)")

        // NEW: cumulative RS-01 monthly consumption table
        db.execSQL(
            "CREATE TABLE rs01_monthly (" +
                    "medicine TEXT PRIMARY KEY," +
                    "qty INTEGER NOT NULL" +
                    ")"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS rs01_monthly (" +
                        "medicine TEXT PRIMARY KEY," +
                        "qty INTEGER NOT NULL" +
                        ")"
            )
        }
        // Future migrations >2 yahan
    }

    // ---------------- Issue Items (existing) ----------------
    fun insertIssue(vehicle: String?, medicine: String?, qty: Int): Long {
        val db = writableDatabase
        val cv = ContentValues()
        cv.put("vehicle", vehicle)
        cv.put("medicine", medicine)
        cv.put("qty", qty)
        cv.put("created_at", System.currentTimeMillis())
        cv.put("uploaded", 0)
        return db.insert("issue_items", null, cv)
    }

    fun getUnuploadedByVehicle(vehicle: String?): MutableList<IssueItem?> {
        val db = readableDatabase
        val c = db.rawQuery(
            "SELECT id, vehicle, medicine, qty, created_at, uploaded FROM issue_items WHERE vehicle=? AND uploaded=0",
            arrayOf(vehicle)
        )
        val out: MutableList<IssueItem?> = ArrayList()
        try {
            while (c.moveToNext()) {
                val it = IssueItem()
                it.id = c.getLong(0)
                it.vehicle = c.getString(1)
                it.medicine = c.getString(2)
                it.qty = c.getInt(3)
                it.createdAt = c.getLong(4)
                it.uploaded = c.getInt(5) == 1
                out.add(it)
            }
        } finally {
            c.close()
        }
        return out
    }

    fun markUploaded(ids: MutableList<Long?>?) {
        if (ids == null || ids.isEmpty()) return
        val db = writableDatabase
        val sb = StringBuilder()
        sb.append("UPDATE issue_items SET uploaded=1 WHERE id IN (")
        ids.forEachIndexed { idx, id ->
            if (idx > 0) sb.append(",")
            sb.append(id)
        }
        sb.append(")")
        db.execSQL(sb.toString())
    }

    fun updateIssueQty(id: Long, newQty: Int) {
        if (newQty <= 0) return
        writableDatabase.execSQL(
            "UPDATE issue_items SET qty=? WHERE id=? AND uploaded=0",
            arrayOf(newQty, id)
        )
    }

    fun deleteIssue(id: Long) {
        writableDatabase.execSQL(
            "DELETE FROM issue_items WHERE id=? AND uploaded=0",
            arrayOf(id)
        )
    }

    // ---------------- RS-01 Monthly Cumulative (NEW) ----------------

    /**
     * Add qty to cumulative monthly total for given medicine.
     * If row not exists -> insert; else update += qty.
     */
    fun addOrIncrementRs01Monthly(medicine: String, qty: Int) {
        if (qty <= 0) return
        val db = writableDatabase
        // Try update first
        val updated = db.compileStatement(
            "UPDATE rs01_monthly SET qty = qty + ? WHERE medicine = ?"
        ).apply {
            bindLong(1, qty.toLong())
            bindString(2, medicine)
        }.executeUpdateDelete()

        if (updated == 0) {
            val cv = ContentValues()
            cv.put("medicine", medicine)
            cv.put("qty", qty)
            db.insert("rs01_monthly", null, cv)
        }
    }

    /**
     * Return all cumulative rows (medicine, qty)
     */
    fun getAllRs01Monthly(): List<Pair<String, Int>> {
        val db = readableDatabase
        val list = mutableListOf<Pair<String, Int>>()
        val c = db.rawQuery("SELECT medicine, qty FROM rs01_monthly", null)
        c.use {
            while (it.moveToNext()) {
                list.add(it.getString(0) to it.getInt(1))
            }
        }
        return list
    }

    fun clearRs01Monthly() {
        writableDatabase.execSQL("DELETE FROM rs01_monthly")
    }

    companion object {
        const val DB_NAME = "issuedb.db"
        const val DB_VER = 2   // bumped to 2
    }
}