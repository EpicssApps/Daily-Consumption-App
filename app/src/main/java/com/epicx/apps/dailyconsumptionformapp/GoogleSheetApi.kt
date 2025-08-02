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

    private const val BASE_URL = "https://script.google.com/macros/s/AKfycbyxvLOI7Ux-xxV3wJcp21PORHkLQ2Elhi5jbffnFmQIOzOt3H5M3VJzLgDYxIQpCi1v/exec"

    // Ambulances for which shift tag should be added
    private val shiftAmbulances = listOf("BNA 17", "BNA 21", "BNA 22", "BNA 25", "BNA 26", "BNA 29")

    // Returns "Morning", "Evening" or null (for Night)
    private fun getShiftTag(): String? {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        return when {
            hour in 8 until 15 -> "Morning"
            hour in 15 until 22 -> "Evening"
            else -> null // Night
        }
    }

    suspend fun upsertData(data: FormData): Result<Unit> = withContext(Dispatchers.IO) {
        // Attach shift tag to date if needed
        val shiftTag = if (data.vehicleName in shiftAmbulances) getShiftTag() else null
        val dateWithShift = if (shiftTag != null) "${data.date} ($shiftTag)" else data.date
        val modifiedData = data.copy(date = dateWithShift)

        val jsonAdapter = moshi.adapter(FormData::class.java)
        val body = RequestBody.create(
            "application/json; charset=utf-8".toMediaTypeOrNull(),
            jsonAdapter.toJson(modifiedData)
        )
        val request = Request.Builder()
            .url("$BASE_URL?action=upsert")
            .post(body)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string()
                Log.d("SheetApi", "POST response: code=${response.code} | body=$respBody")
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    val fullError = "Failed to submit: ${response.code} ${response.message} Body: $respBody"
                    Log.e("SheetApi", fullError)
                    Result.failure(IOException(fullError))
                }
            }
        } catch (e: Exception) {
            Log.e("SheetApi", "Exception in upsertData", e)
            Result.failure(e)
        }
    }

    // Get single record (date + vehicle + medicine)
    suspend fun getSingleData(
        date: String,
        vehicleName: String,
        medicineName: String
    ): Result<FormData> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL?action=get&date=${URLEncoder.encode(date, "UTF-8")}" +
                "&vehicleName=${URLEncoder.encode(vehicleName, "UTF-8")}" +
                "&medicineName=${URLEncoder.encode(medicineName, "UTF-8")}"
        val request = Request.Builder().url(url).get().build()
        try {
            client.newCall(request).execute().use { response ->
                val json = response.body?.string()
                Log.d("SheetApi", "GET single record: code=${response.code} | url=$url | body=$json")
                if (!response.isSuccessful) {
                    val fullError = "Fetch error: ${response.code} ${response.message} Body: $json"
                    Log.e("SheetApi", fullError)
                    return@withContext Result.failure(IOException(fullError))
                }
                if (json.isNullOrEmpty()) {
                    Log.e("SheetApi", "No data returned from API")
                    return@withContext Result.failure(IOException("No data returned"))
                }
                val adapter = moshi.adapter(FormData::class.java)
                val data = adapter.fromJson(json)
                if (data != null) Result.success(data)
                else {
                    Log.e("SheetApi", "Malformed data: $json")
                    Result.failure(IOException("Malformed data"))
                }
            }
        } catch (e: Exception) {
            Log.e("SheetApi", "Exception in getSingleData", e)
            Result.failure(e)
        }
    }

    // Get all records for a vehicle (all medicines, any date)
    suspend fun getAllForVehicle(vehicleName: String): Result<List<FormData>> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL?action=getAllForVehicle&vehicleName=${URLEncoder.encode(vehicleName, "UTF-8")}"
        val request = Request.Builder().url(url).get().build()
        try {
            client.newCall(request).execute().use { response ->
                val json = response.body?.string()
                Log.d("SheetApi", "GET all for vehicle: code=${response.code} | url=$url | body=$json")
                if (!response.isSuccessful) {
                    val fullError = "Fetch error: ${response.code} ${response.message} Body: $json"
                    Log.e("SheetApi", fullError)
                    return@withContext Result.failure(IOException(fullError))
                }
                if (json.isNullOrEmpty()) {
                    Log.e("SheetApi", "No data returned from API")
                    return@withContext Result.failure(IOException("No data returned"))
                }
                val type = Types.newParameterizedType(List::class.java, FormData::class.java)
                val adapter = moshi.adapter<List<FormData>>(type)
                val data = adapter.fromJson(json)
                if (data != null) Result.success(data)
                else {
                    Log.e("SheetApi", "Malformed data: $json")
                    Result.failure(IOException("Malformed data"))
                }
            }
        } catch (e: Exception) {
            Log.e("SheetApi", "Exception in getAllForVehicle", e)
            Result.failure(e)
        }
    }

    suspend fun bulkUpload(date: String, data: List<FormData>): Result<Unit> = withContext(Dispatchers.IO) {
        val shiftTag = getShiftTag()
        val modifiedList = data.map { fd ->
            if (fd.vehicleName in shiftAmbulances && shiftTag != null) {
                fd.copy(date = "$date ($shiftTag)")
            } else {
                fd.copy(date = date)
            }
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
}