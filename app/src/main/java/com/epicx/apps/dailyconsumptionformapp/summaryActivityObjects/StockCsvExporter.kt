package com.epicx.apps.dailyconsumptionformapp.summaryActivityObjects

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.text.SimpleDateFormat

/**
 * CSV Exporter for "Current Stock" sheet.
 * Usage:
 *
 * val result = StockCsvExporter.export(list, context)
 * when (result) {
 *   is StockCsvExporter.ExportResult.Success -> {
 *       Toast.makeText(context, "Saved: ${result.fileName}", Toast.LENGTH_LONG).show()
 *       StockCsvExporter.share(context, result.uri) // optional
 *   }
 *   is StockCsvExporter.ExportResult.Error -> {
 *       Toast.makeText(context, "Error: ${result.message}", Toast.LENGTH_LONG).show()
 *   }
 * }
 */
object StockCsvExporter {

    // Your row model (adjust fields / types as per your real data class)
    data class StockRow(
        val vehicleName: String?,
        val medicineName: String?,
        val openingBalance: String?,
        val consumption: String?,
        val totalEmergency: String?,
        val closingBalance: String?,
        val storeIssued: String?,
        val stockAvailable: String?
    )

    sealed class ExportResult {
        data class Success(val uri: Uri, val fileName: String) : ExportResult()
        data class Error(val message: String, val cause: Throwable? = null) : ExportResult()
    }

    /**
     * Export list to Downloads via MediaStore (scoped storage friendly).
     * Returns ExportResult.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun export(
        list: List<StockRow>,
        context: Context,
        // addBOM = true if Excel encoding issues (Hindi/Unicode) on some Windows machines
        addBOM: Boolean = false
    ): ExportResult = withContext(Dispatchers.IO) {
        if (list.isEmpty()) {
            return@withContext ExportResult.Error("List khali hai.")
        }

        val yesterdayStr = getYesterdayString()
        val baseName = "RS-01 All DataSheet $yesterdayStr"
        val fileName = ensureUniqueDisplayName(context.contentResolver, "$baseName.csv")

        val rows: List<String> = buildList {
            add("Vehicle,Medicine,Opening,Consumption,Emergency,Closing,StoreIssued,StockAvailable")
            list.forEach { fd ->
                fun clean(v: String?): String = (v ?: "").replace(",", " ").trim()
                add(
                    listOf(
                        clean(fd.vehicleName),
                        clean(fd.medicineName),
                        clean(fd.openingBalance),
                        clean(fd.consumption),
                        clean(fd.totalEmergency),
                        clean(fd.closingBalance),
                        clean(fd.storeIssued),
                        clean(fd.stockAvailable)
                    ).joinToString(",")
                )
            }
        }

        try {
            val uri = saveCsvToDownloadsMediaStore(
                context = context,
                displayName = fileName,
                rows = rows,
                addBOM = addBOM
            ) ?: return@withContext ExportResult.Error("Uri null (insert failed).")

            ExportResult.Success(uri, fileName)
        } catch (e: Exception) {
            ExportResult.Error("Write failed: ${e.message}", e)
        }
    }

    fun share(context: Context, uri: Uri) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share / Open CSV"))
    }

    private fun getYesterdayString(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LocalDate.now().minusDays(1)
                .format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
        } else {
            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -1)
            }
            SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(cal.time)
        }
    }

    private fun saveCsvToDownloadsMediaStore(
        context: Context,
        displayName: String,
        rows: List<String>,
        addBOM: Boolean
    ): Uri? {
        val resolver = context.contentResolver
        val mimeType = "text/csv"

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(collection, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { os ->
                if (addBOM) {
                    // UTF-8 BOM
                    os.write(0xEF)
                    os.write(0xBB)
                    os.write(0xBF)
                }
                OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                    rows.forEach { line ->
                        writer.appendLine(line)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            // Cleanup on failure
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun ensureUniqueDisplayName(
        resolver: ContentResolver,
        original: String
    ): String {
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        var name = original
        var idx = 1
        while (true) {
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
            resolver.query(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                projection,
                selection,
                arrayOf(name),
                null
            ).use { c ->
                if (c == null || !c.moveToFirst()) {
                    return name
                }
            }
            val dot = original.lastIndexOf('.')
            val base = if (dot != -1) original.substring(0, dot) else original
            val ext = if (dot != -1) original.substring(dot) else ""
            name = "$base ($idx)$ext"
            idx++
        }
    }
}