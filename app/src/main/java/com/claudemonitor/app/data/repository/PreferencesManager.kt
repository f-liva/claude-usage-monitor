package com.claudemonitor.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "claude_monitor_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        private val KEY_SESSION_COOKIES = stringPreferencesKey("session_cookies")
        private val KEY_ACCOUNT_EMAIL = stringPreferencesKey("account_email")
        private val KEY_REFRESH_INTERVAL = intPreferencesKey("refresh_interval_minutes")
        private val KEY_NOTIFICATION_ENABLED = booleanPreferencesKey("notification_enabled")
        private val KEY_LAST_USAGE_JSON = stringPreferencesKey("last_usage_json")
        private val KEY_IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
    }

    val sessionCookies: Flow<String?> = context.dataStore.data.map { it[KEY_SESSION_COOKIES] }
    val accountEmail: Flow<String?> = context.dataStore.data.map { it[KEY_ACCOUNT_EMAIL] }
    val refreshInterval: Flow<Int> = context.dataStore.data.map { it[KEY_REFRESH_INTERVAL] ?: 15 }
    val notificationEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_NOTIFICATION_ENABLED] ?: true }
    val isLoggedIn: Flow<Boolean> = context.dataStore.data.map { it[KEY_IS_LOGGED_IN] ?: false }
    val lastUsageJson: Flow<String?> = context.dataStore.data.map { it[KEY_LAST_USAGE_JSON] }

    suspend fun saveSessionCookies(cookies: String) {
        context.dataStore.edit { it[KEY_SESSION_COOKIES] = cookies }
    }

    suspend fun saveAccountEmail(email: String) {
        context.dataStore.edit { it[KEY_ACCOUNT_EMAIL] = email }
    }

    suspend fun saveRefreshInterval(minutes: Int) {
        context.dataStore.edit { it[KEY_REFRESH_INTERVAL] = minutes }
    }

    suspend fun saveNotificationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_NOTIFICATION_ENABLED] = enabled }
    }

    suspend fun saveLoggedIn(loggedIn: Boolean) {
        context.dataStore.edit { it[KEY_IS_LOGGED_IN] = loggedIn }
    }

    suspend fun saveLastUsageJson(json: String) {
        context.dataStore.edit { it[KEY_LAST_USAGE_JSON] = json }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
