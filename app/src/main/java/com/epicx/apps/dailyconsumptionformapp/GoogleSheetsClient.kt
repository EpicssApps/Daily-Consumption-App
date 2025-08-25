package com.epicx.apps.dailyconsumptionformapp

import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

object GoogleSheetsClient {

    private const val WEB_APP_URL = "https://script.google.com/macros/s/AKfycbw_tkfECdcd1_Cyrhegb1ELv30--u_lnhp6F2Hz_kF80i43yKm5eNYeILGehEyQ4BdyBQ/exec"
    private const val API_KEY = "8k4jd723lkdc723pos57"

    private val gson = Gson()
    private val jsonMedia = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /* ========== Data Models ========== */

    data class MedQty(
        @SerializedName("medicine") val medicine: String,
        @SerializedName("qty") val qty: Int
    )

    data class SubmitItem(
        @SerializedName("medicine") val medicine: String,
        @SerializedName("consumption") val consumption: Int,
        @SerializedName("emergency") val emergency: Int,
        @SerializedName("mainStoreIssued") val mainStoreIssued: Int? = null,
        @SerializedName("stockAvailable")  val stockAvailable:  Int? = null
    )

    private data class IssuePayload(
        @SerializedName("vehicle") val vehicle: String,
        @SerializedName("items") val items: List<MedQty>,
        @SerializedName("key") val key: String = API_KEY,
        @SerializedName("mode") val mode: String? = null,
        @SerializedName("requestId") val requestId: String
    )

    private data class ConsumePayload(
        @SerializedName("vehicle") val vehicle: String,
        @SerializedName("items") val items: List<SubmitItem>,
        @SerializedName("key") val key: String = API_KEY,
        @SerializedName("mode") val mode: String = "consume",
        @SerializedName("requestId") val requestId: String
    )

    private data class RolloverPayload(
        @SerializedName("vehicle") val vehicle: String,
        @SerializedName("items") val items: List<String> = emptyList(),
        @SerializedName("key") val key: String = API_KEY,
        @SerializedName("mode") val mode: String = "rollover",
        @SerializedName("requestId") val requestId: String
    )

    data class UploadResponse(
        @SerializedName("ok") val ok: Boolean,
        @SerializedName("duplicate") val duplicate: Boolean? = null,
        @SerializedName("vehicle") val vehicle: String? = null,
        @SerializedName("updated") val updated: List<UpdatedItem>? = null,
        @SerializedName("notFound") val notFound: List<NotFoundItem>? = null,
        @SerializedName("error") val error: String? = null,
        @SerializedName("mode") val mode: String? = null,
        @SerializedName("code") val code: String? = null
    )

    data class UpdatedItem(
        @SerializedName("medicine") val medicine: String,
        @SerializedName("from") val from: Double? = null,
        @SerializedName("to") val to: Double? = null
    )

    data class NotFoundItem(
        @SerializedName("medicine") val medicine: String?,
        @SerializedName("reason") val reason: String?
    )

    data class BalanceRow(
        @SerializedName("medicine") val medicine: String,
        @SerializedName("opening") val opening: Double,
        @SerializedName("closing") val closing: Double,
        @SerializedName("storeIssued") val storeIssued: Double? = null,
        @SerializedName("stockAvailable") val stockAvailable: Double? = null
    )

    data class BalancesResponse(
        @SerializedName("ok") val ok: Boolean,
        @SerializedName("vehicle") val vehicle: String? = null,
        @SerializedName("rows") val rows: List<BalanceRow>? = null,
        @SerializedName("error") val error: String? = null,
        @SerializedName("code") val code: String? = null
    )

    /* ========== Public API (with requestId reuse support) ========== */

    fun uploadIssues(
        context: android.content.Context,
        vehicle: String,
        items: List<MedQty>,
        previousRequestId: String? = null,
        callback: (success: Boolean, response: UploadResponse?, requestIdUsed: String) -> Unit
    ) {
        val reqId = previousRequestId ?: newReqId()
        val payload = IssuePayload(
            vehicle = vehicle,
            items = items,
            requestId = reqId
        )
        val body = gson.toJson(payload).toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url(WEB_APP_URL)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-API-KEY", API_KEY)
            .build()
        http.newCall(req).enqueue(defaultCallback(reqId, callback))
    }

    fun submitConsumption(
        vehicle: String,
        items: List<SubmitItem>,
        previousRequestId: String? = null,
        callback: (success: Boolean, response: UploadResponse?, requestIdUsed: String) -> Unit
    ) {
        val reqId = previousRequestId ?: newReqId()
        val payload = ConsumePayload(
            vehicle = vehicle,
            items = items,
            requestId = reqId
        )
        val body = gson.toJson(payload).toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url(WEB_APP_URL)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-API-KEY", API_KEY)
            .build()
        http.newCall(req).enqueue(defaultCallback(reqId, callback))
    }

    fun rolloverBalances(
        @Suppress("UNUSED_PARAMETER") vehicle: String,
        previousRequestId: String? = null,
        callback: (success: Boolean, response: UploadResponse?, requestIdUsed: String) -> Unit
    ) {
        val reqId = previousRequestId ?: newReqId()
        val payload = RolloverPayload(vehicle = "", requestId = reqId)
        val body = gson.toJson(payload).toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url(WEB_APP_URL)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-API-KEY", API_KEY)
            .build()
        http.newCall(req).enqueue(defaultCallback(reqId, callback))
    }

    fun fetchBalances(
        vehicle: String,
        callback: (success: Boolean, response: BalancesResponse?) -> Unit
    ) {
        val url = WEB_APP_URL +
                "?action=balances" +
                "&vehicle=" + URLEncoder.encode(vehicle, "UTF-8") +
                "&key=" + URLEncoder.encode(API_KEY, "UTF-8")
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                postToMain { callback(false, BalancesResponse(ok = false, error = e.message)) }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    val parsed = try {
                        gson.fromJson(bodyStr, BalancesResponse::class.java)
                    } catch (t: Throwable) {
                        BalancesResponse(ok = false, error = "Invalid response")
                    }
                    val success = resp.isSuccessful && (parsed.ok)
                    postToMain { callback(success, parsed) }
                }
            }
        })
    }

    /* ========== Internal Helpers ========== */

    private fun defaultCallback(
        requestIdUsed: String,
        callback: (success: Boolean, response: UploadResponse?, requestIdUsed: String) -> Unit
    ) = object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
            postToMain { callback(false, UploadResponse(ok = false, error = e.message), requestIdUsed) }
        }
        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            response.use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                val parsed = try {
                    gson.fromJson(bodyStr, UploadResponse::class.java)
                } catch (t: Throwable) {
                    UploadResponse(ok = false, error = "Invalid response")
                }
                val success = resp.isSuccessful && (parsed.ok)
                postToMain { callback(success, parsed, requestIdUsed) }
            }
        }
    }

    private fun newReqId(): String =
        System.currentTimeMillis().toString() + "-" + UUID.randomUUID().toString().take(8)

    private fun postToMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}