package com.epicx.apps.dailyconsumptionformapp

import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object GoogleSheetsClient {

    private const val WEB_APP_URL = "https://script.google.com/macros/s/AKfycbz01rn8i-QCM2PbV2xWrH__qjuIdmyj2VgtQWiV3TSAe0tERlWgnDPaGFfx4a0RpBOg_w/exec"
    private const val API_KEY = "8k4jd723lkdc723pos57"

    private val gson = Gson()
    private val jsonMedia = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class MedQty(
        @SerializedName("medicine") val medicine: String,
        @SerializedName("qty") val qty: Int
    )

    private data class Payload(
        @SerializedName("vehicle") val vehicle: String,
        @SerializedName("items") val items: List<MedQty>,
        @SerializedName("key") val key: String = API_KEY
    )

    data class UploadResponse(
        @SerializedName("ok") val ok: Boolean,
        @SerializedName("vehicle") val vehicle: String? = null,
        @SerializedName("updated") val updated: List<UpdatedItem>? = null,
        @SerializedName("notFound") val notFound: List<NotFoundItem>? = null,
        @SerializedName("error") val error: String? = null
    )

    data class UpdatedItem(
        @SerializedName("medicine") val medicine: String,
        @SerializedName("from") val from: Double?,
        @SerializedName("to") val to: Double?
    )

    data class NotFoundItem(
        @SerializedName("medicine") val medicine: String?,
        @SerializedName("reason") val reason: String?
    )

    // NEW: Balances API models
    data class BalanceRow(
        @SerializedName("medicine") val medicine: String,
        @SerializedName("opening") val opening: Double,
        @SerializedName("closing") val closing: Double
    )
    data class BalancesResponse(
        @SerializedName("ok") val ok: Boolean,
        @SerializedName("vehicle") val vehicle: String? = null,
        @SerializedName("rows") val rows: List<BalanceRow>? = null,
        @SerializedName("error") val error: String? = null
    )

    fun uploadIssues(
        context: android.content.Context,
        vehicle: String,
        items: List<MedQty>,
        callback: (success: Boolean, response: UploadResponse?) -> Unit
    ) {
        val payload = Payload(vehicle = vehicle, items = items)
        val body = RequestBody.create(jsonMedia, gson.toJson(payload))

        val req = Request.Builder()
            .url(WEB_APP_URL)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-API-KEY", API_KEY)
            .build()

        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                postToMain { callback(false, UploadResponse(ok = false, error = e.message)) }
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
                    postToMain { callback(success, parsed) }
                }
            }
        })
    }

    // NEW: fetch opening/closing balances for a vehicle
    fun fetchBalances(
        vehicle: String,
        callback: (success: Boolean, response: BalancesResponse?) -> Unit
    ) {
        val url = WEB_APP_URL +
                "?action=balances" +
                "&vehicle=" + URLEncoder.encode(vehicle, "UTF-8") +
                "&key=" + URLEncoder.encode(API_KEY, "UTF-8")

        val req = Request.Builder()
            .url(url)
            .get()
            .build()

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

    private fun postToMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}