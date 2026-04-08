package com.epicx.apps.dailyconsumptionformapp.objects

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfShareHelper {

    data class MedicineEntry(
        val medicine: String,
        val qty: Int
    )

    /**
     * Generate a PDF with the list of medicines and share via WhatsApp (or generic share).
     */
    fun generateAndSharePdf(
        context: Context,
        vehicle: String,
        indent: String,
        items: List<MedicineEntry>,
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (items.isEmpty()) {
            Toast.makeText(context, "No items to share.", Toast.LENGTH_SHORT).show()
            return
        }

        val pdfFile = generatePdf(context, vehicle, indent, items, timestamp)
        if (pdfFile == null) {
            Toast.makeText(context, "Failed to generate PDF.", Toast.LENGTH_SHORT).show()
            return
        }

        sharePdf(context, pdfFile, vehicle, indent)
    }

    private fun generatePdf(
        context: Context,
        vehicle: String,
        indent: String,
        items: List<MedicineEntry>,
        timestamp: Long
    ): File? {
        return try {
            val pageWidth = 595  // A4 width in points
            val pageHeight = 842 // A4 height in points
            val margin = 40f
            val dateStr = SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.US).format(Date(timestamp))

            val document = PdfDocument()

            // Calculate how many items fit per page
            val headerHeight = 140f
            val rowHeight = 22f
            val tableHeaderHeight = 28f
            val footerHeight = 40f
            val usableHeight = pageHeight - margin * 2 - headerHeight - footerHeight - tableHeaderHeight
            val itemsPerPage = (usableHeight / rowHeight).toInt().coerceAtLeast(1)
            val totalPages = ((items.size + itemsPerPage - 1) / itemsPerPage).coerceAtLeast(1)

            for (pageNum in 0 until totalPages) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum + 1).create()
                val page = document.startPage(pageInfo)
                val canvas: Canvas = page.canvas

                val paintTitle = Paint().apply {
                    color = Color.parseColor("#1565C0")
                    textSize = 20f
                    isFakeBoldText = true
                    isAntiAlias = true
                }
                val paintSubTitle = Paint().apply {
                    color = Color.parseColor("#333333")
                    textSize = 13f
                    isAntiAlias = true
                }
                val paintHeader = Paint().apply {
                    color = Color.WHITE
                    textSize = 12f
                    isFakeBoldText = true
                    isAntiAlias = true
                }
                val paintRow = Paint().apply {
                    color = Color.parseColor("#222222")
                    textSize = 11f
                    isAntiAlias = true
                }
                val paintLine = Paint().apply {
                    color = Color.parseColor("#CCCCCC")
                    strokeWidth = 0.5f
                    isAntiAlias = true
                }
                val paintHeaderBg = Paint().apply {
                    color = Color.parseColor("#1565C0")
                    style = Paint.Style.FILL
                }
                val paintAltRow = Paint().apply {
                    color = Color.parseColor("#F5F5F5")
                    style = Paint.Style.FILL
                }
                val paintFooter = Paint().apply {
                    color = Color.parseColor("#999999")
                    textSize = 9f
                    isAntiAlias = true
                }

                var y = margin

                // Title
                canvas.drawText("Medicine Issue Record", margin, y + 22f, paintTitle)
                y += 36f

                // Separator line
                val separatorPaint = Paint().apply {
                    color = Color.parseColor("#1565C0")
                    strokeWidth = 2f
                }
                canvas.drawLine(margin, y, pageWidth - margin, y, separatorPaint)
                y += 16f

                // Info rows
                paintSubTitle.isFakeBoldText = true
                canvas.drawText("Vehicle:", margin, y + 14f, paintSubTitle)
                paintSubTitle.isFakeBoldText = false
                canvas.drawText(vehicle, margin + 65f, y + 14f, paintSubTitle)
                y += 22f

                paintSubTitle.isFakeBoldText = true
                canvas.drawText("Indent #:", margin, y + 14f, paintSubTitle)
                paintSubTitle.isFakeBoldText = false
                canvas.drawText(indent, margin + 65f, y + 14f, paintSubTitle)
                y += 22f

                paintSubTitle.isFakeBoldText = true
                canvas.drawText("Date:", margin, y + 14f, paintSubTitle)
                paintSubTitle.isFakeBoldText = false
                canvas.drawText(dateStr, margin + 65f, y + 14f, paintSubTitle)
                y += 22f

                val totalQty = items.sumOf { it.qty }
                paintSubTitle.isFakeBoldText = true
                canvas.drawText("Total Items:", margin, y + 14f, paintSubTitle)
                paintSubTitle.isFakeBoldText = false
                canvas.drawText("${items.size} medicines, Total Qty: $totalQty", margin + 85f, y + 14f, paintSubTitle)
                y += 30f

                // Table columns
                val col1X = margin          // S#
                val col2X = margin + 35f    // Medicine Name
                val col3X = pageWidth - margin - 55f // Qty
                val tableRight = pageWidth - margin

                // Table header background
                canvas.drawRect(margin, y, tableRight, y + tableHeaderHeight, paintHeaderBg)

                canvas.drawText("S#", col1X + 5f, y + 18f, paintHeader)
                canvas.drawText("Medicine Name", col2X + 5f, y + 18f, paintHeader)
                canvas.drawText("Qty", col3X + 5f, y + 18f, paintHeader)
                y += tableHeaderHeight

                // Table rows
                val startIdx = pageNum * itemsPerPage
                val endIdx = minOf(startIdx + itemsPerPage, items.size)
                for (i in startIdx until endIdx) {
                    val item = items[i]
                    val rowIdx = i - startIdx

                    // Alternating row background
                    if (rowIdx % 2 == 0) {
                        canvas.drawRect(margin, y, tableRight, y + rowHeight, paintAltRow)
                    }

                    canvas.drawText("${i + 1}", col1X + 5f, y + 15f, paintRow)

                    // Truncate long medicine names
                    val maxMedWidth = col3X - col2X - 15f
                    var medText = item.medicine
                    while (paintRow.measureText(medText) > maxMedWidth && medText.length > 3) {
                        medText = medText.dropLast(1)
                    }
                    if (medText != item.medicine) medText += "…"

                    canvas.drawText(medText, col2X + 5f, y + 15f, paintRow)
                    canvas.drawText("${item.qty}", col3X + 5f, y + 15f, paintRow)

                    // Row bottom line
                    canvas.drawLine(margin, y + rowHeight, tableRight, y + rowHeight, paintLine)
                    y += rowHeight
                }

                // Table border
                val tableBorderPaint = Paint().apply {
                    color = Color.parseColor("#999999")
                    style = Paint.Style.STROKE
                    strokeWidth = 1f
                }
                canvas.drawRect(margin, margin + headerHeight - 2f, tableRight, y, tableBorderPaint)

                // Footer
                val footerY = pageHeight - margin
                canvas.drawText(
                    "Page ${pageNum + 1} of $totalPages  •  Generated by Daily Consumption App",
                    margin, footerY, paintFooter
                )

                document.finishPage(page)
            }

            val fileName = "medicine_issue_${vehicle}_${indent}_${System.currentTimeMillis()}.pdf"
            val file = File(context.cacheDir, fileName)
            FileOutputStream(file).use { fos ->
                document.writeTo(fos)
            }
            document.close()
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun sharePdf(context: Context, pdfFile: File, vehicle: String, indent: String) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pdfFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Medicine Issue - $vehicle (Indent: $indent)")
                putExtra(Intent.EXTRA_TEXT, "Medicine issue record for vehicle $vehicle, Indent# $indent")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // Try WhatsApp first
            try {
                val whatsappIntent = Intent(shareIntent).apply {
                    setPackage("com.whatsapp")
                }
                context.startActivity(whatsappIntent)
            } catch (e: Exception) {
                // WhatsApp not found, use generic share
                context.startActivity(Intent.createChooser(shareIntent, "Share Medicine PDF"))
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to share PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

