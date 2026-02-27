package com.claudemonitor.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.claudemonitor.app.data.repository.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = PreferencesManager(context)
            CoroutineScope(Dispatchers.Main).launch {
                val isLoggedIn = prefs.isLoggedIn.first()
                val notifEnabled = prefs.notificationEnabled.first()
                if (isLoggedIn && notifEnabled) {
                    MonitorForegroundService.start(context)
                }
            }
        }
    }
}
