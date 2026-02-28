package com.claudemonitor.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.claudemonitor.app.R
import com.claudemonitor.app.data.model.ModelLimit
import com.claudemonitor.app.data.model.UsageData
import com.claudemonitor.app.data.repository.PreferencesManager
import com.claudemonitor.app.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject

class MonitorForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefsManager: PreferencesManager
    private var refreshJob: Job? = null
    private var latestData: UsageData? = null

    companion object {
        private const val TAG = "MonitorService"
        const val CHANNEL_ID = "claude_monitor_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.claudemonitor.STOP"
        const val ACTION_REFRESH = "com.claudemonitor.REFRESH"
        const val ACTION_RESHOW = "com.claudemonitor.RESHOW"
        const val ACTION_DATA_UPDATED = "com.claudemonitor.DATA_UPDATED"

        private var instance: MonitorForegroundService? = null
        val isRunning: Boolean get() = instance != null

        fun getLatestUsageData(): UsageData? = instance?.latestData

        fun start(context: Context) {
            val intent = Intent(context, MonitorForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefsManager = PreferencesManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                // Show "Aggiornamento..." then launch RefreshActivity
                showRefreshingNotification()
                handler.postDelayed({
                    try { RefreshActivity.launch(this) }
                    catch (e: Exception) { Log.e(TAG, "RefreshActivity launch failed", e); loadCachedData() }
                }, 200)
                return START_STICKY
            }
            ACTION_DATA_UPDATED -> {
                // RefreshActivity finished — reload from cache
                loadCachedData()
                return START_STICKY
            }
            ACTION_RESHOW -> {
                val notification = buildNotification(latestData)
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, notification)
                return START_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification(null))
        loadCachedData()
        startPeriodicRefresh()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        refreshJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun loadCachedData() {
        scope.launch {
            try {
                val json = prefsManager.lastUsageJson.first()
                if (json != null) {
                    val data = parseCachedUsage(json)
                    latestData = data
                    updateNotification(data)
                }
            } catch (_: Exception) { }
        }
    }

    private fun startPeriodicRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val intervalMinutes = prefsManager.refreshInterval.first()
            val intervalMs = intervalMinutes * 60 * 1000L

            while (isActive) {
                delay(intervalMs)
                try { RefreshActivity.launch(this@MonitorForegroundService) }
                catch (_: Exception) { loadCachedData() }
            }
        }
    }

    // ── Parsing ──

    private fun parseCachedUsage(json: String): UsageData {
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
        return UsageData(
            planName = obj.optString("planName", ""),
            modelLimits = models,
            resetTime = obj.optString("resetTime", ""),
            lastUpdated = obj.optLong("lastUpdated", System.currentTimeMillis())
        )
    }

    // ── Notification ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Claude Usage Monitor",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows current Claude usage limits"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun showRefreshingNotification() {
        val title = if (latestData != null && latestData!!.planName.isNotEmpty()) {
            "Claude ${latestData!!.planName}"
        } else {
            "Claude Usage Monitor"
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val reshowIntent = Intent(this, MonitorForegroundService::class.java).apply {
            action = ACTION_RESHOW
        }
        val pendingReshow = PendingIntent.getService(
            this, 3, reshowIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Aggiornamento...")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Aggiornamento..."))
            .setContentIntent(pendingOpen)
            .setDeleteIntent(pendingReshow)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        notification.flags = notification.flags or
                Notification.FLAG_ONGOING_EVENT or
                Notification.FLAG_NO_CLEAR

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(usageData: UsageData?): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val reshowIntent = Intent(this, MonitorForegroundService::class.java).apply {
            action = ACTION_RESHOW
        }
        val pendingReshow = PendingIntent.getService(
            this, 3, reshowIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentTitle = if (usageData != null && usageData.planName.isNotEmpty()) {
            "Claude ${usageData.planName}"
        } else {
            "Claude Usage Monitor"
        }

        val contentText = buildNotificationText(usageData)

        // Refresh → Service intent (keeps notification panel open momentarily)
        val refreshIntent = Intent(this, MonitorForegroundService::class.java).apply {
            action = ACTION_REFRESH
        }
        val pendingRefresh = PendingIntent.getService(
            this, 1, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(pendingOpen)
            .setDeleteIntent(pendingReshow)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .addAction(R.drawable.ic_refresh, "Refresh", pendingRefresh)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        notification.flags = notification.flags or
                Notification.FLAG_ONGOING_EVENT or
                Notification.FLAG_NO_CLEAR
        return notification
    }

    private fun buildNotificationText(usageData: UsageData?): String {
        if (usageData == null || usageData.modelLimits.isEmpty()) {
            return if (usageData?.planName?.isNotEmpty() == true) {
                "Plan: ${usageData.planName} — open app to refresh"
            } else {
                "Open app to load usage data"
            }
        }

        val mainLimits = usageData.modelLimits.filter { limit ->
            val lower = limit.modelName.lowercase()
            lower.contains("sessione") || lower.contains("session") ||
            lower.contains("tutti") || lower.contains("all model") ||
            lower.contains("sonnet")
        }
        val limitsToShow = mainLimits.ifEmpty { usageData.modelLimits.take(3) }

        return limitsToShow.joinToString(" | ") { limit ->
            val shortName = shortenName(limit.modelName)
            if (limit.unit == "%") {
                "$shortName: ${limit.used}%"
            } else {
                "$shortName: ${limit.used}/${limit.total}"
            }
        }
    }

    private fun shortenName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.contains("sessione") || lower.contains("session") -> "Session"
            lower.contains("tutti") || lower.contains("all model") -> "All"
            lower.contains("sonnet") -> "Sonnet"
            lower.contains("opus") -> "Opus"
            lower.contains("haiku") -> "Haiku"
            name.length > 12 -> name.take(12)
            else -> name
        }
    }

    private fun updateNotification(usageData: UsageData) {
        val notification = buildNotification(usageData)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}
