package com.zenpeartree.krecords

import android.content.Context
import java.util.UUID

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("krecords-settings", Context.MODE_PRIVATE)

    fun loadBackendConfig(): BackendConfig? {
        val backendUrl = prefs.getString(KEY_BACKEND_URL, PRODUCTION_BACKEND_URL)?.trim().orEmpty()
        if (backendUrl.isBlank()) {
            return null
        }
        return BackendConfig(
            backendUrl = backendUrl.trimEnd('/'),
            deviceId = deviceId(),
            deviceSecret = deviceSecret(),
            activeAuthSessionId = prefs.getString(KEY_ACTIVE_AUTH_SESSION_ID, null),
            activeAuthUrl = prefs.getString(KEY_ACTIVE_AUTH_URL, null),
        )
    }

    fun isConfigured(): Boolean = loadBackendConfig() != null

    fun isAuthenticated(): Boolean = prefs.getBoolean(KEY_AUTHENTICATED, false)

    fun saveBackendUrl(
        backendUrl: String,
    ) {
        prefs.edit()
            .putString(KEY_BACKEND_URL, backendUrl.trim().trimEnd('/'))
            .apply()
    }

    fun recordAuthSession(session: AuthSessionStart) {
        prefs.edit()
            .putString(KEY_ACTIVE_AUTH_SESSION_ID, session.sessionId)
            .putString(KEY_ACTIVE_AUTH_URL, session.authUrl)
            .apply()
    }

    fun clearAuthSession() {
        prefs.edit()
            .remove(KEY_ACTIVE_AUTH_SESSION_ID)
            .remove(KEY_ACTIVE_AUTH_URL)
            .apply()
    }

    fun recordStatus(message: String) {
        prefs.edit()
            .putString(KEY_STATUS, message)
            .apply()
    }

    fun recordHistorySync(message: String) {
        prefs.edit()
            .putString(KEY_STATUS, message)
            .putLong(KEY_LAST_HISTORY_SYNC_AT, System.currentTimeMillis())
            .apply()
    }

    fun recordAuthenticated(athleteName: String?) {
        prefs.edit()
            .putBoolean(KEY_AUTHENTICATED, true)
            .putString(KEY_ATHLETE_NAME, athleteName)
            .apply()
    }

    fun clearAuthentication() {
        prefs.edit()
            .putBoolean(KEY_AUTHENTICATED, false)
            .remove(KEY_ATHLETE_NAME)
            .remove(KEY_ACTIVE_AUTH_SESSION_ID)
            .remove(KEY_ACTIVE_AUTH_URL)
            .apply()
    }

    fun recordPr(name: String, elapsedSeconds: Int) {
        prefs.edit()
            .putString(KEY_LAST_PR_NAME, name)
            .putInt(KEY_LAST_PR_SECONDS, elapsedSeconds)
            .apply()
    }

    fun clearCacheMetadata() {
        prefs.edit()
            .remove(KEY_STATUS)
            .remove(KEY_LAST_PR_NAME)
            .remove(KEY_LAST_PR_SECONDS)
            .remove(KEY_LAST_SYNC_AT)
            .remove(KEY_LAST_HISTORY_SYNC_AT)
            .apply()
    }

    fun lastHistorySyncAt(): Long =
        prefs.getLong(KEY_LAST_HISTORY_SYNC_AT, prefs.getLong(KEY_LAST_SYNC_AT, 0L))

    fun athleteName(): String? = prefs.getString(KEY_ATHLETE_NAME, null)

    fun deviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val value = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, value).apply()
        return value
    }

    fun deviceSecret(): String {
        val existing = prefs.getString(KEY_DEVICE_SECRET, null)
        if (!existing.isNullOrBlank()) return existing
        val value = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_SECRET, value).apply()
        return value
    }

    fun dashboard(segmentCount: Int): DashboardSummary {
        return DashboardSummary(
            segmentCount = segmentCount,
            configured = isConfigured(),
            authenticated = isAuthenticated(),
            status = prefs.getString(KEY_STATUS, null) ?: "Waiting to sync.",
            lastPrName = prefs.getString(KEY_LAST_PR_NAME, null),
            lastPrSeconds = prefs.getInt(KEY_LAST_PR_SECONDS, -1).takeIf { it >= 0 },
        )
    }

    companion object {
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_SECRET = "device_secret"
        private const val KEY_ACTIVE_AUTH_SESSION_ID = "active_auth_session_id"
        private const val KEY_ACTIVE_AUTH_URL = "active_auth_url"
        private const val KEY_AUTHENTICATED = "authenticated"
        private const val KEY_ATHLETE_NAME = "athlete_name"
        private const val KEY_STATUS = "status"
        private const val KEY_LAST_PR_NAME = "last_pr_name"
        private const val KEY_LAST_PR_SECONDS = "last_pr_seconds"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_LAST_HISTORY_SYNC_AT = "last_history_sync_at"
        const val PRODUCTION_BACKEND_URL = "https://krecords-87730.web.app"
    }
}
