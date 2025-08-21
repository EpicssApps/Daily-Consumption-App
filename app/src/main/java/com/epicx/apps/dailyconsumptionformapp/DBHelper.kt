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
        // handle migrations if needed
    }

    fun insertIssue(vehicle: String?, medicine: String?, qty: Int): Long {
        val db = getWritableDatabase()
        val cv = ContentValues()
        cv.put("vehicle", vehicle)
        cv.put("medicine", medicine)
        cv.put("qty", qty)
        cv.put("created_at", System.currentTimeMillis())
        cv.put("uploaded", 0)
        return db.insert("issue_items", null, cv)
    }

    fun getUnuploadedByVehicle(vehicle: String?): MutableList<IssueItem?> {
        val db = getReadableDatabase()
        val c = db.rawQuery(
            "SELECT id, vehicle, medicine, qty, created_at, uploaded FROM issue_items WHERE vehicle=? AND uploaded=0",
            arrayOf<String?>(vehicle)
        )
        val out: MutableList<IssueItem?> = ArrayList<IssueItem?>()
        try {
            while (c.moveToNext()) {
                val it: IssueItem = IssueItem()
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
        val db = getWritableDatabase()
        val sb = StringBuilder()
        sb.append("UPDATE issue_items SET uploaded=1 WHERE id IN (")
        for (i in ids.indices) {
            if (i > 0) sb.append(",")
            sb.append(ids.get(i))
        }
        sb.append(")")
        db.execSQL(sb.toString())
    }

    companion object {
        const val DB_NAME: String = "issuedb.db"
        const val DB_VER: Int = 1
    }
}