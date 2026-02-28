package com.claudemonitor.app

import android.app.Application
import android.webkit.CookieManager

class ClaudeMonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize cookie manager for WebView
        // Wrapped in try-catch: CookieManager can crash if WebView package
        // is missing, being updated, or disabled on the device.
        try {
            CookieManager.getInstance().setAcceptCookie(true)
        } catch (_: Exception) {
            // WebView not available yet - will be initialized later
        }
    }
}
