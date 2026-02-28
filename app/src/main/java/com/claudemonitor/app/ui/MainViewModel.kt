package com.claudemonitor.app.ui

import android.app.Application
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.claudemonitor.app.data.model.LoginState
import com.claudemonitor.app.data.model.ModelLimit
import com.claudemonitor.app.data.model.SessionState
import com.claudemonitor.app.data.model.UsageData
import com.claudemonitor.app.data.repository.PreferencesManager
import com.claudemonitor.app.service.MonitorForegroundService
import com.claudemonitor.app.service.RefreshActivity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsManager = PreferencesManager(application)

    private val _usageData = MutableStateFlow(UsageData())
    val usageData: StateFlow<UsageData> = _usageData.asStateFlow()

    private val _sessionState = MutableStateFlow(SessionState(loginState = LoginState.LOADING))
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(MonitorForegroundService.isRunning)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    val refreshInterval: StateFlow<Int> = prefsManager.refreshInterval
        .stateIn(viewModelScope, SharingStarted.Eagerly, 15)

    val notificationEnabled: StateFlow<Boolean> = prefsManager.notificationEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val accountEmail: StateFlow<String?> = prefsManager.accountEmail
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        loadSession()
        // Observe DataStore for changes from RefreshActivity
        viewModelScope.launch {
            prefsManager.lastUsageJson.collect { json ->
                if (json != null) {
                    parseCachedUsage(json)
                }
            }
        }
    }

    private fun loadSession() {
        viewModelScope.launch {
            // Check both DataStore flag AND cookies
            val isLoggedIn = prefsManager.isLoggedIn.first()
            val hasCookies = try {
                val cookies = CookieManager.getInstance().getCookie("https://claude.ai") ?: ""
                cookies.contains("sessionKey")
            } catch (_: Exception) { false }

            if (isLoggedIn || hasCookies) {
                // Ensure flag is set if we have cookies
                if (!isLoggedIn && hasCookies) {
                    prefsManager.saveLoggedIn(true)
                }
                _sessionState.value = SessionState(loginState = LoginState.LOGGED_IN)

                val cachedJson = prefsManager.lastUsageJson.first()
                if (cachedJson != null) {
                    parseCachedUsage(cachedJson)
                } else {
                    // No cached data — trigger refresh
                    _usageData.value = _usageData.value.copy(isLoading = true)
                    RefreshActivity.launch(getApplication())
                }
            } else {
                _sessionState.value = SessionState(loginState = LoginState.NOT_LOGGED_IN)
            }
        }
    }

    fun onLoginSuccess(cookies: String) {
        viewModelScope.launch {
            prefsManager.saveSessionCookies(cookies)
            prefsManager.saveLoggedIn(true)
            _sessionState.value = SessionState(loginState = LoginState.LOGGED_IN)
            _usageData.value = _usageData.value.copy(isLoading = true)

            // Launch invisible refresh to fetch usage data
            RefreshActivity.launch(getApplication())
        }
    }

    fun refreshUsage() {
        _usageData.value = _usageData.value.copy(isLoading = true)
        RefreshActivity.launch(getApplication())
    }

    fun toggleService() {
        val context = getApplication<Application>()
        if (MonitorForegroundService.isRunning) {
            MonitorForegroundService.stop(context)
            _isServiceRunning.value = false
        } else {
            MonitorForegroundService.start(context)
            _isServiceRunning.value = true
        }
    }

    fun updateRefreshInterval(minutes: Int) {
        viewModelScope.launch {
            prefsManager.saveRefreshInterval(minutes)
        }
    }

    fun updateNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsManager.saveNotificationEnabled(enabled)
            if (!enabled && MonitorForegroundService.isRunning) {
                MonitorForegroundService.stop(getApplication())
                _isServiceRunning.value = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            MonitorForegroundService.stop(getApplication())
            _isServiceRunning.value = false

            try {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            } catch (_: Exception) {}

            prefsManager.clearAll()
            _sessionState.value = SessionState(loginState = LoginState.NOT_LOGGED_IN)
            _usageData.value = UsageData()
        }
    }

    fun checkServiceStatus() {
        _isServiceRunning.value = MonitorForegroundService.isRunning

        val serviceData = MonitorForegroundService.getLatestUsageData()
        if (serviceData != null && serviceData.lastUpdated > _usageData.value.lastUpdated) {
            _usageData.value = serviceData
        }
    }

    private fun parseCachedUsage(json: String) {
        try {
            val obj = JSONObject(json)
            val modelsArray = obj.optJSONArray("models")
            val models = mutableListOf<ModelLimit>()
            if (modelsArray != null) {
                for (i in 0 until modelsArray.length()) {
                    val m = modelsArray.getJSONObject(i)
                    models.add(ModelLimit(
                        modelName = m.optString("modelName", "Unknown"),
                        used = m.optInt("used", 0),
                        total = m.optInt("total", 0),
                        unit = m.optString("unit", "%"),
                        resetPeriod = m.optString("resetPeriod", "")
                    ))
                }
            }
            _usageData.value = UsageData(
                planName = obj.optString("planName", ""),
                modelLimits = models,
                resetTime = obj.optString("resetTime", ""),
                lastUpdated = obj.optLong("lastUpdated", System.currentTimeMillis())
            )
        } catch (_: Exception) { }
    }
}
