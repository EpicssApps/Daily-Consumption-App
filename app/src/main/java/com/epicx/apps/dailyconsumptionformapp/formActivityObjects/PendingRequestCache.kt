package com.epicx.apps.dailyconsumptionformapp.formActivityObjects

import java.util.concurrent.TimeUnit
import com.epicx.apps.dailyconsumptionformapp.GoogleSheetsClient

/**
 * Holds last requestId for an identical payload signature so that
 * a manual retry after a failure can reuse the SAME requestId and
 * trigger server-side idempotency instead of double applying.
 */
object PendingRequestCache {

    private val WINDOW_MS = TimeUnit.MINUTES.toMillis(5)  // reuse window
    private var last: Entry? = null

    private data class Entry(
        val signature: String,
        val requestId: String,
        val time: Long
    )

    fun signatureForConsumption(vehicle: String, items: List<GoogleSheetsClient.SubmitItem>): String {
        return buildString {
            append(vehicle)
            append("|C|")
            items.sortedBy { it.medicine.lowercase() }
                .forEach {
                    append(it.medicine.lowercase())
                    append(':')
                    append(it.consumption)
                    append(':')
                    append(it.emergency)
                    append(':')
                    append(it.mainStoreIssued ?: 0)
                    append(':')
                    append(it.stockAvailable ?: 0)
                    append(';')
                }
        }
    }

    fun signatureForIssue(vehicle: String, items: List<GoogleSheetsClient.MedQty>): String {
        return buildString {
            append(vehicle)
            append("|I|")
            items.sortedBy { it.medicine.lowercase() }
                .forEach {
                    append(it.medicine.lowercase())
                    append(':')
                    append(it.qty)
                    append(';')
                }
        }
    }

    /**
     * Returns previously used requestId if same signature within window.
     */
    fun reuseIfSame(signature: String): String? {
        val e = last ?: return null
        val now = System.currentTimeMillis()
        return if (e.signature == signature && (now - e.time) < WINDOW_MS) e.requestId else null
    }

    /**
     * Store or overwrite current signature → requestId.
     */
    fun store(signature: String, requestId: String) {
        last = Entry(signature, requestId, System.currentTimeMillis())
    }

    /**
     * Clear if matches signature (after confirmed success or duplicate).
     */
    fun clearIf(signature: String) {
        if (last?.signature == signature) last = null
    }
}