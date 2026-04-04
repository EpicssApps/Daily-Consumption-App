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
                    "uploaded INTEGER NOT NULL DEFAULT 0," +
                    "forced INTEGER NOT NULL DEFAULT 0" +
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

        // Indent number per vehicle table
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS issue_indent (" +
                    "vehicle TEXT PRIMARY KEY," +
                    "indent TEXT NOT NULL" +
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
        // Indent table migration for older versions
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS issue_indent (" +
                    "vehicle TEXT PRIMARY KEY," +
                    "indent TEXT NOT NULL" +
                    ")"
        )
        if (oldVersion < 3) {
            // Add forced column to track force-adjusted items (stock was insufficient)
            try {
                db.execSQL("ALTER TABLE issue_items ADD COLUMN forced INTEGER NOT NULL DEFAULT 0")
            } catch (_: Exception) { /* column may already exist */ }
        }
        // Future migrations >3 yahan
    }

    // ---------------- Issue Items (existing) ----------------
    fun insertIssue(vehicle: String?, medicine: String?, qty: Int, forced: Boolean = false): Long {
        val db = writableDatabase
        val cv = ContentValues()
        cv.put("vehicle", vehicle)
        cv.put("medicine", medicine)
        cv.put("qty", qty)
        cv.put("created_at", System.currentTimeMillis())
        cv.put("uploaded", 0)
        cv.put("forced", if (forced) 1 else 0)
        return db.insert("issue_items", null, cv)
    }

    fun getUnuploadedByVehicle(vehicle: String?): MutableList<IssueItem?> {
        val db = readableDatabase
        val c = db.rawQuery(
            "SELECT id, vehicle, medicine, qty, created_at, uploaded, forced FROM issue_items WHERE vehicle=? AND uploaded=0",
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
                it.forced = c.getInt(6) == 1
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

    // ----------- Indent Number Methods (NEW) -----------

    /**
     * Save or update indent number per vehicle. Replaces old value if exists.
     */
    fun saveIndent(vehicle: String, indent: String) {
        val db = writableDatabase
        val cv = ContentValues()
        cv.put("vehicle", vehicle)
        cv.put("indent", indent)
        db.insertWithOnConflict("issue_indent", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Get last indent number for this vehicle. Returns null if not set.
     */
    fun getIndent(vehicle: String): String? {
        val db = readableDatabase
        val c =
            db.rawQuery("SELECT indent FROM issue_indent WHERE vehicle=? LIMIT 1", arrayOf(vehicle))
        val result = if (c.moveToFirst()) c.getString(0) else null
        c.close()
        return result
    }

    companion object {
        const val DB_NAME = "issuedb.db"
        const val DB_VER = 3   // bumped to 3 (added forced column)
    }
}