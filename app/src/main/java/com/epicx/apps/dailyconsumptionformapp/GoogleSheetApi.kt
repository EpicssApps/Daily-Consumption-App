package com.epicx.apps.dailyconsumptionformapp

import com.squareup.moshi.*
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.IOException
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.code
import kotlin.text.get

object GoogleSheetApi {
    // OkHttpClient with increased timeouts (60 seconds each)
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private const val BASE_URL = "https://script.google.com/macros/s/AKfycbzurHgnnX-nf8f0Xvbihovh7Q1g69d1kB5AsyubQ-Yf4jxF9Wx68ZnoN3rK3QZDdvg/exec"
    private const val RS01_BASE_URL = "https://script.google.com/macros/s/AKfycbxkSEx1RKyt0oRPVgQwdWhhKLu9EmOtij9I8pUwa5IytaPXyTM7AnQUUT0_ER8sKqlCOQ/exec"
    // Ambulances for which shift tag should be added
    private val shiftAmbulances = listOf("BNA 07","BNA 08","BNA 09","BNA 10","BNA 11","BNA 17","BNA 21","BNA 22", "BNA 25", "BNA 26", "BNA 29")

    // Returns "Morning", "Evening" or null (for Night)
    private fun getShiftTag(): String? {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        return when {
            hour in 8 until 15 -> "Morning"
            hour in 15 until 22 -> "Evening"
            hour in 22..23 -> "Night"
            hour in 0 until 8 -> "Early Morning"
            else -> null // Should never happen, but just in case
        }
    }

    // Helper to sanitize numbers
    fun safeNumberString(value: String?): String {
        return value?.toDoubleOrNull()?.let { String.format("%.2f", it) } ?: "0.00"
    }

    suspend fun getAllForDate(date: String? = null): Result<List<FormData>> = withContext(Dispatchers.IO) {
        val url = if (date.isNullOrBlank()) {
            "$BASE_URL?action=getAllForDate"
        } else {
            "$BASE_URL?action=getAllForDate&date=${URLEncoder.encode(date, "UTF-8")}"
        }
        val request = Request.Builder().url(url).get().build()
        try {
            client.newCall(request).execute().use { response ->
                val json = response.body?.string()
                if (!response.isSuccessful || json.isNullOrEmpty()) {
                    return@withContext Result.failure(IOException("No data returned"))
                }
                val type = Types.newParameterizedType(List::class.java, FormData::class.java)
                val adapter = moshi.adapter<List<FormData>>(type)
                val data = adapter.fromJson(json)
                if (data != null) Result.success(data)
                else Result.failure(IOException("Malformed data"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAllForDateOfRS01(date: String? = null): Result<List<FormData>> = withContext(Dispatchers.IO) {
        val url = if (date.isNullOrBlank()) {
            "$RS01_BASE_URL?action=getAllForDate"
        } else {
            "$RS01_BASE_URL?action=getAllForDate&date=${URLEncoder.encode(date, "UTF-8")}"
        }
        val request = Request.Builder().url(url).get().build()
        try {
            client.newCall(request).execute().use { response ->
                val json = response.body?.string()
                if (!response.isSuccessful || json.isNullOrEmpty()) {
                    return@withContext Result.failure(IOException("No data returned"))
                }
                val type = Types.newParameterizedType(List::class.java, FormData::class.java)
                val adapter = moshi.adapter<List<FormData>>(type)
                val data = adapter.fromJson(json)
                if (data != null) Result.success(data)
                else Result.failure(IOException("Malformed data"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    fun isZero(value: String?): Boolean {
        return value.isNullOrBlank() ||
                value == "0" ||
                value == "00" ||
                value == "000" ||
                value == ".0" ||
                value == "0.0" ||
                value == ".00" ||
                value.toDoubleOrNull() == 0.0
    }

    suspend fun bulkUpload(date: String, data: List<FormData>): Result<Unit> = withContext(Dispatchers.IO) {
        val shiftTag = getShiftTag()
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val isNight = (shiftTag == null)
        val appVersion = "V09"
        val zeroBalances =
            (shiftTag != null && (shiftTag == "Morning" || shiftTag == "Evening" || shiftTag == "Early Morning"))

        val modifiedList = data.map { fd ->
            val safeOpening = safeNumberString(fd.openingBalance)
            val safeConsumption = safeNumberString(fd.consumption)
            val safeTotalEmergency = safeNumberString(fd.totalEmergency)
            val safeClosing = safeNumberString(fd.closingBalance)
            val safeStockAvailable = safeNumberString(fd.stockAvailable)

            val newVehicleName = if (fd.vehicleName in shiftAmbulances && shiftTag != null) {
                "${fd.vehicleName} $shiftTag $appVersion"
            } else {
                fd.vehicleName
            }

            if (fd.vehicleName in shiftAmbulances && zeroBalances) {
                fd.copy(
                    vehicleName = newVehicleName,
                    date = date,
                    openingBalance = "0",
                    closingBalance = "0",
                    consumption = safeConsumption,
                    totalEmergency = safeTotalEmergency,
                    stockAvailable = safeStockAvailable
                )
            } else {
                fd.copy(
                    vehicleName = newVehicleName,
                    date = date,
                    openingBalance = safeOpening,
                    closingBalance = safeClosing,
                    consumption = safeConsumption,
                    totalEmergency = safeTotalEmergency,
                    stockAvailable = safeStockAvailable
                )
            }
        }.filter { fd ->
            !(isZero(fd.openingBalance) &&
                    isZero(fd.consumption) &&
                    isZero(fd.totalEmergency) &&
                    isZero(fd.closingBalance))
        }

        val jsonAdapter = moshi.adapter<List<FormData>>(
            Types.newParameterizedType(
                List::class.java,
                FormData::class.java
            )
        )
        val body = RequestBody.create(
            "application/json; charset=utf-8".toMediaTypeOrNull(),
            jsonAdapter.toJson(modifiedList)
        )
        val url = "$BASE_URL?action=bulkUpload&date=$date"
        val request = Request.Builder().url(url).post(body).build()
        try {
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string()
                Log.d("SheetApi", "BULK POST: code=${response.code} | body=$respBody")
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    val fullError =
                        "Bulk upload failed: ${response.code} ${response.message} Body: $respBody"
                    Log.e("SheetApi", fullError)
                    Result.failure(IOException(fullError))
                }
            }
        } catch (e: Exception) {
            Log.e("SheetApi", "Exception in bulkUpload", e)
            Result.failure(e)
        }
    }



    suspend fun rs01BulkUpload(date: String, data: List<FormData>): Result<Unit> = withContext(Dispatchers.IO) {
        val shiftTag = getShiftTag()
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val isNight = (shiftTag == null)
        val appVersion = "V09"
        val zeroBalances =
            (shiftTag != null && (shiftTag == "Morning" || shiftTag == "Evening" || shiftTag == "Early Morning"))

        val modifiedList = data.map { fd ->
            val safeOpening = safeNumberString(fd.openingBalance)
            val safeConsumption = safeNumberString(fd.consumption)
            val safeTotalEmergency = safeNumberString(fd.totalEmergency)
            val safeClosing = safeNumberString(fd.closingBalance)
            val safeStockAvailable = safeNumberString(fd.stockAvailable)

            val newVehicleName = if (fd.vehicleName in shiftAmbulances && shiftTag != null) {
                "${fd.vehicleName} $shiftTag $appVersion"
            } else {
                fd.vehicleName
            }

            if (fd.vehicleName in shiftAmbulances && zeroBalances) {
                fd.copy(
                    vehicleName = newVehicleName,
                    date = date,
                    openingBalance = "0",
                    closingBalance = "0",
                    consumption = safeConsumption,
                    totalEmergency = safeTotalEmergency,
                    stockAvailable = safeStockAvailable
                )
            } else {
                fd.copy(
                    vehicleName = newVehicleName,
                    date = date,
                    openingBalance = safeOpening,
                    closingBalance = safeClosing,
                    consumption = safeConsumption,
                    totalEmergency = safeTotalEmergency,
                    stockAvailable = safeStockAvailable
                )
            }
        }.filter { fd ->
            !(isZero(fd.openingBalance) &&
                    isZero(fd.consumption) &&
                    isZero(fd.totalEmergency) &&
                    isZero(fd.closingBalance))
        }

        val jsonAdapter = moshi.adapter<List<FormData>>(
            Types.newParameterizedType(
                List::class.java,
                FormData::class.java
            )
        )
        val body = RequestBody.create(
            "application/json; charset=utf-8".toMediaTypeOrNull(),
            jsonAdapter.toJson(modifiedList)
        )
        val url = "$RS01_BASE_URL?action=bulkUpload&date=$date"
        val request = Request.Builder().url(url).post(body).build()
        try {
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string()
                Log.d("SheetApi", "BULK POST: code=${response.code} | body=$respBody")
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    val fullError =
                        "Bulk upload failed: ${response.code} ${response.message} Body: $respBody"
                    Log.e("SheetApi", fullError)
                    Result.failure(IOException(fullError))
                }
            }
        } catch (e: Exception) {
            Log.e("SheetApi", "Exception in bulkUpload", e)
            Result.failure(e)
        }
    }
}