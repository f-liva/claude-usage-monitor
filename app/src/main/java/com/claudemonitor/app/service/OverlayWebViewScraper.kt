package com.claudemonitor.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.webkit.*
import com.claudemonitor.app.data.model.ModelLimit
import com.claudemonitor.app.data.model.UsageData
import com.claudemonitor.app.data.repository.PreferencesManager
import kotlinx.coroutines.*
import org.json.JSONArray

/**
 * Performs WebView scraping using a SYSTEM_ALERT_WINDOW overlay.
 * This allows the WebView to have a Window context without needing an Activity,
 * bypassing Android 14+ background activity launch restrictions.
 */
class OverlayWebViewScraper(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val prefsManager = PreferencesManager(context)
    private var webView: WebView? = null
    private var orgName = ""
    private var done = false

    companion object {
        private const val TAG = "OverlayScraper"
        private const val TIMEOUT_MS = 20_000L

        fun canUseOverlay(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun scrape() {
        if (!canUseOverlay(context)) {
            Log.w(TAG, "No overlay permission, skipping")
            return
        }

        done = false
        orgName = ""
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.x = 0
        params.y = 0

        webView = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
                cacheMode = WebSettings.LOAD_DEFAULT
                blockNetworkImage = true
            }

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onDomContent(text: String) {
                    if (text.isBlank() || done) return
                    val hasData = text.contains("%") &&
                            (text.contains("utilizzato", ignoreCase = true) ||
                             text.contains("used", ignoreCase = true))
                    Log.d(TAG, "onDomContent: ${text.length} chars, hasData=$hasData")
                    if (hasData) {
                        handler.post { onDataExtracted(text, windowManager) }
                    }
                }

                @JavascriptInterface
                fun onOrgData(json: String) {
                    try { extractPlanFromOrg(json) }
                    catch (e: Exception) { Log.e(TAG, "onOrgData error", e) }
                }
            }, "Android")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val u = url ?: return
                    Log.d(TAG, "onPageFinished: $u")

                    if (u.contains("/settings/usage")) {
                        view?.evaluateJavascript("""
                            (async function() {
                                try {
                                    var r = await fetch('/api/organizations', {credentials:'include'});
                                    if (r.ok) { var d = await r.json(); Android.onOrgData(JSON.stringify(d)); }
                                } catch(e) {}
                            })();
                        """.trimIndent(), null)

                        val extractJs = "(function(){ var t = document.body ? document.body.innerText : ''; Android.onDomContent(t); })();"
                        handler.postDelayed({ view?.evaluateJavascript(extractJs, null) }, 3000)
                        handler.postDelayed({ view?.evaluateJavascript(extractJs, null) }, 6000)
                    }
                }
            }

            loadUrl("https://claude.ai/settings/usage")
        }

        try {
            windowManager.addView(webView, params)
            Log.d(TAG, "Overlay WebView added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay", e)
            cleanup(windowManager)
            return
        }

        // Timeout
        handler.postDelayed({
            if (!done) {
                Log.d(TAG, "Scrape timed out")
                cleanup(windowManager)
                notifyService()
            }
        }, TIMEOUT_MS)
    }

    private fun onDataExtracted(text: String, windowManager: WindowManager) {
        if (done) return
        done = true

        val data = parseDomContent(text)
        Log.d(TAG, "Parsed ${data.modelLimits.size} limits, plan=${data.planName}")

        scope.launch {
            prefsManager.saveLastUsageJson(serializeUsageData(data))
            cleanup(windowManager)
            notifyService()
        }
    }

    private fun cleanup(windowManager: WindowManager) {
        try {
            webView?.stopLoading()
            webView?.destroy()
            windowManager.removeView(webView)
        } catch (_: Exception) {}
        webView = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun notifyService() {
        if (MonitorForegroundService.isRunning) {
            val intent = Intent(context, MonitorForegroundService::class.java).apply {
                action = MonitorForegroundService.ACTION_DATA_UPDATED
            }
            context.startService(intent)
        }
    }

    private fun parseDomContent(text: String): UsageData {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val limits = mutableListOf<ModelLimit>()
        var planName = orgName

        for (line in lines) {
            val lower = line.lowercase()
            if (lower.contains("max") && (lower.contains("plan") || lower.contains("piano"))) {
                planName = "Max"; break
            } else if (lower.contains("pro") && (lower.contains("plan") || lower.contains("piano"))) {
                planName = "Pro"; break
            }
        }

        val pctRegex = Regex("""(\d+)%\s*(utilizzato|used|utilizado)""", RegexOption.IGNORE_CASE)
        val resetRegex = Regex("""(?:Si ripristina|Resets?|Rinnovo)\s+(.+)""", RegexOption.IGNORE_CASE)

        for (i in lines.indices) {
            val pctMatch = pctRegex.find(lines[i]) ?: continue
            val percent = pctMatch.groupValues[1].toIntOrNull() ?: continue

            var sectionName = ""
            var resetTime = ""

            // Look backwards for section name and reset time
            for (j in (i - 1).coerceAtLeast(0) downTo (i - 5).coerceAtLeast(0)) {
                val prev = lines[j]
                val lowerPrev = prev.lowercase()

                if (resetTime.isEmpty()) {
                    resetRegex.find(prev)?.let { resetTime = it.groupValues[1].trim() }
                }

                if (sectionName.isEmpty() &&
                    !lowerPrev.contains("ripristina") && !lowerPrev.contains("reset") &&
                    !lowerPrev.contains("rinnovo") && !lowerPrev.contains("scopri") &&
                    !lowerPrev.contains("learn") && !lowerPrev.contains("%") &&
                    !lowerPrev.contains("limiti di") && !lowerPrev.contains("usage limit") &&
                    !lowerPrev.contains("limiti settimanali") && !lowerPrev.contains("weekly") &&
                    prev.length in 3..60
                ) {
                    sectionName = prev
                }
            }

            // Look forwards for reset time (it usually appears after the percentage)
            if (resetTime.isEmpty()) {
                for (j in (i + 1).coerceAtMost(lines.lastIndex)..
                         (i + 3).coerceAtMost(lines.lastIndex)) {
                    resetRegex.find(lines[j])?.let { resetTime = it.groupValues[1].trim() }
                    if (resetTime.isNotEmpty()) break
                }
            }

            if (sectionName.isEmpty()) sectionName = "Usage"

            limits.add(ModelLimit(
                modelName = sectionName,
                used = percent,
                total = 100,
                unit = "%",
                resetPeriod = resetTime
            ))
        }

        return UsageData(
            planName = planName,
            modelLimits = limits,
            lastUpdated = System.currentTimeMillis(),
            isLoading = false,
            error = if (limits.isEmpty()) "No usage data found" else null
        )
    }

    private fun extractPlanFromOrg(json: String) {
        try {
            val orgs = JSONArray(json)
            if (orgs.length() > 0) {
                val org = orgs.getJSONObject(0)

                val displayName = org.optString("name", "")
                if (displayName.isNotEmpty()) {
                    scope.launch { prefsManager.saveAccountEmail(displayName) }
                }

                org.optJSONArray("active_flags")?.let { flags ->
                    for (j in 0 until flags.length()) {
                        val flag = flags.optString(j, "").lowercase()
                        if (flag.contains("max")) { orgName = "Max"; return }
                        if (flag.contains("pro")) { orgName = "Pro"; return }
                        if (flag.contains("team")) { orgName = "Team"; return }
                    }
                }
                for (field in listOf("plan_display_name", "plan_type", "billing_type")) {
                    val v = org.optString(field, "")
                    if (v.isNotEmpty()) { orgName = v; return }
                }
                orgName = displayName
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractPlanFromOrg error", e)
        }
    }

    private fun serializeUsageData(data: UsageData): String {
        val models = data.modelLimits.joinToString(",") { m ->
            """{"modelName":"${m.modelName}","used":${m.used},"total":${m.total},"unit":"${m.unit}","resetPeriod":"${m.resetPeriod}"}"""
        }
        return """{"planName":"${data.planName}","resetTime":"${data.resetTime}","lastUpdated":${data.lastUpdated},"models":[$models]}"""
    }
}
