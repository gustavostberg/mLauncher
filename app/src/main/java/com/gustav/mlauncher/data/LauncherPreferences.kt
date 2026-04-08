package com.gustav.mlauncher.data

import android.content.Context
import android.content.SharedPreferences

class LauncherPreferences(context: Context) {
    companion object {
        private const val PREFS_NAME = "launcher_prefs"
        private const val SECURE_ALIAS = "mlauncher_secure_prefs_key"
        private const val FAVORITES_KEY = "favorite_components"
        private const val LABEL_OVERRIDE_PREFIX = "label_override_"
        private const val HOME_INTEGRATION_ENABLED_KEY = "home_integration_enabled"
        private const val TESLA_ENABLED_KEY = "tesla_enabled"
        private const val TESLA_ACCESS_TOKEN_KEY = "tesla_access_token"
        private const val TESLA_ACCESS_TOKEN_ENCRYPTED_KEY = "tesla_access_token_encrypted"
        private const val TESLA_VIN_KEY = "tesla_vin"
        private const val TESLA_BASE_URL_KEY = "tesla_base_url"
        private const val TESLA_BATTERY_PERCENT_KEY = "tesla_battery_percent"
        private const val TESLA_LAST_UPDATED_KEY = "tesla_last_updated"
        private const val TESLA_DISPLAY_NAME_KEY = "tesla_display_name"
        private const val TESLA_LAST_ERROR_KEY = "tesla_last_error"
        private const val HOME_WIDGET_ENABLED_KEY = "home_widget_enabled"
        private const val HOME_WIDGET_EXPANDED_KEY = "home_widget_expanded"
        private const val HOME_WIDGET_DEBUG_KEY = "home_widget_debug"
        private const val HOME_WIDGET_ID_KEY = "home_widget_id"
        private const val TESLA_WIDGET_ID_KEY = "tesla_widget_id"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val securePrefsCipher = SecurePrefsCipher(SECURE_ALIAS)

    private fun normalizeSecret(value: String): String {
        val jwtMatch =
            Regex("""eyJ[a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+""")
                .find(value)
                ?.value
        if (!jwtMatch.isNullOrBlank()) {
            return jwtMatch
        }

        return value.replace("\\s+".toRegex(), "")
    }

    fun hasCustomFavorites(): Boolean = prefs.contains(FAVORITES_KEY)

    fun loadFavoriteComponentKeys(): List<String> {
        val serialized = prefs.getString(FAVORITES_KEY, null) ?: return emptyList()
        if (serialized.isBlank()) {
            return emptyList()
        }

        return serialized
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun saveFavoriteComponentKeys(componentKeys: List<String>) {
        prefs.edit()
            .putString(FAVORITES_KEY, componentKeys.joinToString(separator = "\n"))
            .apply()
    }

    fun loadLabelOverrides(): Map<String, String> {
        return prefs.all
            .filterKeys { key -> key.startsWith(LABEL_OVERRIDE_PREFIX) }
            .mapNotNull { (key, value) ->
                val label = value as? String ?: return@mapNotNull null
                key.removePrefix(LABEL_OVERRIDE_PREFIX) to label
            }
            .toMap()
    }

    fun saveLabelOverride(componentKey: String, labelOverride: String?) {
        prefs.edit().apply {
            if (labelOverride.isNullOrBlank()) {
                remove(LABEL_OVERRIDE_PREFIX + componentKey)
            } else {
                putString(LABEL_OVERRIDE_PREFIX + componentKey, labelOverride)
            }
        }.apply()
    }

    fun loadHomeIntegrationEnabled(): Boolean = prefs.getBoolean(HOME_INTEGRATION_ENABLED_KEY, false)

    fun saveHomeIntegrationEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(HOME_INTEGRATION_ENABLED_KEY, enabled)
            .apply()
    }

    fun loadTeslaEnabled(): Boolean = prefs.getBoolean(TESLA_ENABLED_KEY, false)

    fun saveTeslaEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(TESLA_ENABLED_KEY, enabled)
            .apply()
    }

    fun loadTeslaAccessToken(): String {
        val encrypted = prefs.getString(TESLA_ACCESS_TOKEN_ENCRYPTED_KEY, "").orEmpty()
        if (encrypted.isNotBlank()) {
            return try {
                normalizeSecret(securePrefsCipher.decrypt(encrypted))
            } catch (_: Exception) {
                ""
            }
        }

        val legacyPlainText = prefs.getString(TESLA_ACCESS_TOKEN_KEY, "").orEmpty()
        if (legacyPlainText.isNotBlank()) {
            saveTeslaAccessToken(legacyPlainText)
            return normalizeSecret(legacyPlainText)
        }

        return ""
    }

    fun saveTeslaAccessToken(token: String) {
        val normalized = normalizeSecret(token)
        prefs.edit().apply {
            if (normalized.isBlank()) {
                remove(TESLA_ACCESS_TOKEN_KEY)
                remove(TESLA_ACCESS_TOKEN_ENCRYPTED_KEY)
            } else {
                putString(TESLA_ACCESS_TOKEN_ENCRYPTED_KEY, securePrefsCipher.encrypt(normalized))
                remove(TESLA_ACCESS_TOKEN_KEY)
            }
        }.apply()
    }

    fun loadTeslaVin(): String = prefs.getString(TESLA_VIN_KEY, "").orEmpty()

    fun saveTeslaVin(vin: String) {
        prefs.edit()
            .putString(TESLA_VIN_KEY, vin.trim())
            .apply()
    }

    fun loadTeslaBaseUrl(defaultValue: String): String =
        prefs.getString(TESLA_BASE_URL_KEY, defaultValue)?.trim().orEmpty().ifBlank { defaultValue }

    fun saveTeslaBaseUrl(baseUrl: String) {
        prefs.edit()
            .putString(TESLA_BASE_URL_KEY, baseUrl.trim().trimEnd('/'))
            .apply()
    }

    fun saveTeslaCache(
        batteryPercent: Int,
        lastUpdatedEpochMillis: Long,
        displayName: String?,
    ) {
        prefs.edit()
            .putInt(TESLA_BATTERY_PERCENT_KEY, batteryPercent)
            .putLong(TESLA_LAST_UPDATED_KEY, lastUpdatedEpochMillis)
            .putString(TESLA_DISPLAY_NAME_KEY, displayName?.trim().orEmpty())
            .remove(TESLA_LAST_ERROR_KEY)
            .apply()
    }

    fun clearTeslaCache() {
        prefs.edit()
            .remove(TESLA_BATTERY_PERCENT_KEY)
            .remove(TESLA_LAST_UPDATED_KEY)
            .remove(TESLA_DISPLAY_NAME_KEY)
            .remove(TESLA_LAST_ERROR_KEY)
            .apply()
    }

    fun clearTeslaCredentials() {
        prefs.edit()
            .remove(TESLA_ACCESS_TOKEN_KEY)
            .remove(TESLA_ACCESS_TOKEN_ENCRYPTED_KEY)
            .remove(TESLA_VIN_KEY)
            .remove(TESLA_BASE_URL_KEY)
            .remove(TESLA_ENABLED_KEY)
            .apply()
        clearTeslaCache()
    }

    fun hasTeslaCache(): Boolean = prefs.contains(TESLA_BATTERY_PERCENT_KEY) && prefs.contains(TESLA_LAST_UPDATED_KEY)

    fun loadTeslaBatteryPercent(): Int? =
        if (prefs.contains(TESLA_BATTERY_PERCENT_KEY)) prefs.getInt(TESLA_BATTERY_PERCENT_KEY, 0) else null

    fun loadTeslaLastUpdatedEpochMillis(): Long? =
        if (prefs.contains(TESLA_LAST_UPDATED_KEY)) prefs.getLong(TESLA_LAST_UPDATED_KEY, 0L) else null

    fun loadTeslaDisplayName(): String = prefs.getString(TESLA_DISPLAY_NAME_KEY, "").orEmpty()

    fun saveTeslaLastError(error: String?) {
        prefs.edit().apply {
            if (error.isNullOrBlank()) {
                remove(TESLA_LAST_ERROR_KEY)
            } else {
                putString(TESLA_LAST_ERROR_KEY, error.trim())
            }
        }.apply()
    }

    fun loadTeslaLastError(): String = prefs.getString(TESLA_LAST_ERROR_KEY, "").orEmpty()

    fun isTeslaConfigured(): Boolean = loadTeslaAccessToken().isNotBlank()

    fun loadHomeWidgetEnabled(): Boolean {
        if (prefs.contains(HOME_WIDGET_ENABLED_KEY)) {
            return prefs.getBoolean(HOME_WIDGET_ENABLED_KEY, false)
        }

        return loadHomeWidgetId() != null
    }

    fun saveHomeWidgetEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(HOME_WIDGET_ENABLED_KEY, enabled)
            .apply()
    }

    fun loadHomeWidgetExpanded(): Boolean = prefs.getBoolean(HOME_WIDGET_EXPANDED_KEY, false)

    fun saveHomeWidgetExpanded(expanded: Boolean) {
        prefs.edit()
            .putBoolean(HOME_WIDGET_EXPANDED_KEY, expanded)
            .apply()
    }

    fun loadHomeWidgetDebug(): Boolean = prefs.getBoolean(HOME_WIDGET_DEBUG_KEY, false)

    fun saveHomeWidgetDebug(enabled: Boolean) {
        prefs.edit()
            .putBoolean(HOME_WIDGET_DEBUG_KEY, enabled)
            .apply()
    }

    fun loadHomeWidgetId(): Int? =
        when {
            prefs.contains(HOME_WIDGET_ID_KEY) -> prefs.getInt(HOME_WIDGET_ID_KEY, 0)
            prefs.contains(TESLA_WIDGET_ID_KEY) -> prefs.getInt(TESLA_WIDGET_ID_KEY, 0)
            else -> null
        }.takeIf { it != null && it > 0 }

    fun saveHomeWidgetId(widgetId: Int?) {
        prefs.edit().apply {
            if (widgetId == null || widgetId <= 0) {
                remove(HOME_WIDGET_ID_KEY)
                remove(TESLA_WIDGET_ID_KEY)
            } else {
                putInt(HOME_WIDGET_ID_KEY, widgetId)
                remove(TESLA_WIDGET_ID_KEY)
            }
        }.apply()
    }

    fun maskedTeslaToken(): String {
        val token = loadTeslaAccessToken()
        if (token.isBlank()) {
            return ""
        }

        val visiblePrefix = token.take(6)
        val visibleSuffix = token.takeLast(4)
        return "$visiblePrefix...$visibleSuffix"
    }
}
