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
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        // Future migrations if needed
    }

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

    // NEW: Update single pending row (only if still unuploaded)
    fun updateIssueQty(id: Long, newQty: Int) {
        if (newQty <= 0) return
        writableDatabase.execSQL(
            "UPDATE issue_items SET qty=? WHERE id=? AND uploaded=0",
            arrayOf(newQty, id)
        )
    }

    // NEW: Delete single pending row (only if still unuploaded)
    fun deleteIssue(id: Long) {
        writableDatabase.execSQL(
            "DELETE FROM issue_items WHERE id=? AND uploaded=0",
            arrayOf(id)
        )
    }

    companion object {
        const val DB_NAME = "issuedb.db"
        const val DB_VER = 1
    }
}