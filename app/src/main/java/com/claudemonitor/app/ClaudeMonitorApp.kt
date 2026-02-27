package com.claudemonitor.app

import android.app.Application
import android.webkit.CookieManager

class ClaudeMonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize cookie manager for WebView
        CookieManager.getInstance().setAcceptCookie(true)
    }
}
