package com.claudemonitor.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.claudemonitor.app.R
import com.claudemonitor.app.data.model.ModelLimit
import com.claudemonitor.app.data.model.UsageData
import com.claudemonitor.app.data.repository.PreferencesManager
import com.claudemonitor.app.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class MonitorForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var scraper: ClaudeWebScraper? = null
    private lateinit var prefsManager: PreferencesManager
    private val handler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null

    companion object {
        const val CHANNEL_ID = "claude_monitor_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.claudemonitor.STOP"
        const val ACTION_REFRESH = "com.claudemonitor.REFRESH"

        private var instance: MonitorForegroundService? = null
        val isRunning: Boolean get() = instance != null

        fun getLatestUsageData(): UsageData? = instance?.scraper?.usageData?.value

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
        initScraper()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                refreshUsage()
                return START_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification(null))
        startPeriodicRefresh()
        refreshUsage()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        refreshRunnable?.let { handler.removeCallbacks(it) }
        scraper?.destroy()
        scope.cancel()
        super.onDestroy()
    }

    private fun initScraper() {
        scraper = ClaudeWebScraper(this).apply {
            initialize()
            scope.launch {
                val cookies = prefsManager.sessionCookies.first()
                if (cookies != null) {
                    setCookies(cookies)
                }
            }
        }
    }

    private fun startPeriodicRefresh() {
        scope.launch {
            val intervalMinutes = prefsManager.refreshInterval.first()
            val intervalMs = intervalMinutes * 60 * 1000L

            refreshRunnable?.let { handler.removeCallbacks(it) }
            refreshRunnable = object : Runnable {
                override fun run() {
                    refreshUsage()
                    handler.postDelayed(this, intervalMs)
                }
            }
            handler.postDelayed(refreshRunnable!!, intervalMs)
        }
    }

    private fun refreshUsage() {
        scraper?.fetchUsage { usageData ->
            updateNotification(usageData)
            // Persist last usage data
            scope.launch {
                try {
                    prefsManager.saveLastUsageJson(usageDataToJson(usageData))
                } catch (_: Exception) { }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Claude Usage Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows current Claude usage limits"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(usageData: UsageData?): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val refreshIntent = Intent(this, MonitorForegroundService::class.java).apply {
            action = ACTION_REFRESH
        }
        val pendingRefresh = PendingIntent.getService(
            this, 1, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MonitorForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentTitle = if (usageData != null && usageData.planName.isNotEmpty()) {
            "Claude ${usageData.planName}"
        } else {
            "Claude Usage Monitor"
        }

        val contentText = buildNotificationText(usageData)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .addAction(R.drawable.ic_refresh, "Refresh", pendingRefresh)
            .addAction(R.drawable.ic_stop, "Stop", pendingStop)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildNotificationText(usageData: UsageData?): String {
        if (usageData == null || usageData.modelLimits.isEmpty()) {
            return if (usageData?.isLoading == true) {
                "Loading usage data..."
            } else if (usageData?.error != null) {
                "Error: ${usageData.error}"
            } else {
                "Monitoring active — waiting for data"
            }
        }

        return usageData.modelLimits.joinToString(" | ") { limit ->
            val pct = (limit.percentage * 100).toInt()
            val icon = when {
                limit.isAtLimit -> "\u26D4"     // no entry
                limit.isNearLimit -> "\u26A0\uFE0F" // warning
                else -> "\u2705"                 // check
            }
            "$icon ${limit.modelName}: ${limit.used}/${limit.total} ($pct%)"
        }
    }

    private fun updateNotification(usageData: UsageData) {
        val notification = buildNotification(usageData)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun usageDataToJson(data: UsageData): String {
        val models = data.modelLimits.joinToString(",") { m ->
            """{"modelName":"${m.modelName}","used":${m.used},"total":${m.total},"unit":"${m.unit}","resetPeriod":"${m.resetPeriod}"}"""
        }
        return """{"planName":"${data.planName}","resetTime":"${data.resetTime}","lastUpdated":${data.lastUpdated},"models":[$models]}"""
    }
}
