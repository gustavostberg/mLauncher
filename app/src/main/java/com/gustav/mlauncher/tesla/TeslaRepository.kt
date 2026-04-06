package com.gustav.mlauncher.tesla

import android.content.Context
import com.gustav.mlauncher.data.LauncherPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId
import java.time.ZonedDateTime

data class TeslaBatteryStatus(
    val displayName: String,
    val vin: String,
    val batteryPercent: Int,
    val fetchedAtMillis: Long,
)

sealed interface TeslaFetchResult {
    data class Success(val status: TeslaBatteryStatus) : TeslaFetchResult

    data class Failure(val message: String) : TeslaFetchResult
}

class TeslaRepository(
    private val context: Context,
    private val preferences: LauncherPreferences,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://fleet-api.prd.eu.vn.cloud.tesla.com"
    }

    fun shouldSyncNow(now: ZonedDateTime = ZonedDateTime.now()): Boolean {
        val hour = now.hour
        return hour in 6..23
    }

    fun fetchAndCacheBatteryStatus(): TeslaFetchResult {
        return try {
            if (!preferences.loadTeslaEnabled()) {
                return TeslaFetchResult.Failure("Tesla integration is disabled.")
            }

            if (!shouldSyncNow()) {
                return TeslaFetchResult.Failure("Skipping Tesla sync during quiet hours.")
            }

            val accessToken = preferences.loadTeslaAccessToken()
            if (accessToken.isBlank()) {
                return TeslaFetchResult.Failure("Missing Tesla access token.")
            }

            val baseUrl = preferences.loadTeslaBaseUrl(DEFAULT_BASE_URL)
            val vehicle = resolveVehicle(baseUrl, accessToken, preferences.loadTeslaVin().trim())
                ?: return TeslaFetchResult.Failure("No Tesla vehicle found.")

            val response = getJson("$baseUrl/api/1/vehicles/${vehicle.vin}/vehicle_data", accessToken)
            val chargeState = response.optJSONObject("response")?.optJSONObject("charge_state")
                ?: return TeslaFetchResult.Failure("Tesla response did not include charge state.")
            val batteryPercent = when {
                chargeState.has("usable_battery_level") -> chargeState.optInt("usable_battery_level", -1)
                chargeState.has("battery_level") -> chargeState.optInt("battery_level", -1)
                else -> -1
            }
            if (batteryPercent !in 0..100) {
                return TeslaFetchResult.Failure("Tesla battery percentage was unavailable.")
            }

            val status =
                TeslaBatteryStatus(
                    displayName = vehicle.displayName.ifBlank { context.getString(com.gustav.mlauncher.R.string.tesla_default_display_name) },
                    vin = vehicle.vin,
                    batteryPercent = batteryPercent,
                    fetchedAtMillis = System.currentTimeMillis(),
                )
            preferences.saveTeslaCache(
                batteryPercent = status.batteryPercent,
                lastUpdatedEpochMillis = status.fetchedAtMillis,
                displayName = status.displayName,
            )
            preferences.saveTeslaLastError(null)
            if (preferences.loadTeslaVin().isBlank()) {
                preferences.saveTeslaVin(status.vin)
            }
            TeslaFetchResult.Success(status)
        } catch (exception: Exception) {
            TeslaFetchResult.Failure(exception.message ?: "Tesla request failed.")
        }
    }

    private fun resolveVehicle(baseUrl: String, accessToken: String, configuredVin: String): TeslaVehicle? {
        if (configuredVin.isNotBlank()) {
            return TeslaVehicle(
                displayName = preferences.loadTeslaDisplayName(),
                vin = configuredVin,
            )
        }

        val vehiclesJson = getJson("$baseUrl/api/1/vehicles", accessToken)
        val responseArray = vehiclesJson.optJSONArray("response") ?: JSONArray()
        if (responseArray.length() == 0) {
            return null
        }

        val firstVehicle = responseArray.optJSONObject(0) ?: return null
        return TeslaVehicle(
            displayName = firstVehicle.optString("display_name"),
            vin = firstVehicle.optString("vin"),
        ).takeIf { it.vin.isNotBlank() }
    }

    private fun getJson(url: String, accessToken: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }

        return connection.useAndReadJson()
    }

    private fun HttpURLConnection.useAndReadJson(): JSONObject {
        return try {
            val stream =
                if (responseCode in 200..299) {
                    inputStream
                } else {
                    errorStream ?: inputStream
                }
            val payload = stream.bufferedReader().use(BufferedReader::readText)
            if (responseCode !in 200..299) {
                throw IllegalStateException("Tesla API error $responseCode: $payload")
            }
            JSONObject(payload)
        } finally {
            disconnect()
        }
    }

    private data class TeslaVehicle(
        val displayName: String,
        val vin: String,
    )
}
