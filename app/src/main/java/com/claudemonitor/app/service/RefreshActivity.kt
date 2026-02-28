package com.claudemonitor.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.claudemonitor.app.R
import com.claudemonitor.app.data.model.ModelLimit
import com.claudemonitor.app.data.model.UsageData
import com.claudemonitor.app.data.repository.PreferencesManager
import kotlinx.coroutines.*
import org.json.JSONArray

/**
 * Completely invisible Activity that loads claude.ai/settings/usage in a
 * hidden WebView, scrapes DOM content, saves to DataStore, and finishes.
 * The user sees nothing.
 */
class RefreshActivity : ComponentActivity() {

    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var prefsManager: PreferencesManager
    private var orgName = ""
    private var done = false

    companion object {
        private const val TAG = "RefreshActivity"

        fun launch(context: Context) {
            val intent = Intent(context, RefreshActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_HISTORY
            }
            context.startActivity(intent)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        prefsManager = PreferencesManager(this)
        Log.d(TAG, "Starting background refresh")

        // Container that is completely invisible
        val container = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.INVISIBLE
        }

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)
            visibility = View.INVISIBLE
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
                        handler.post { onDataExtracted(text) }
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

        container.addView(webView)
        setContentView(container)

        // Timeout: finish after 15s regardless
        handler.postDelayed({
            if (!done) {
                Log.d(TAG, "Refresh timed out")
                finishQuietly()
            }
        }, 15000)
    }

    private fun onDataExtracted(text: String) {
        if (done) return
        done = true

        val data = parseDomContent(text)
        Log.d(TAG, "Parsed ${data.modelLimits.size} limits, plan=${data.planName}")

        scope.launch {
            prefsManager.saveLastUsageJson(serializeUsageData(data))

            // Tell the foreground service to reload cached data
            if (MonitorForegroundService.isRunning) {
                val refreshIntent = Intent(
                    this@RefreshActivity,
                    MonitorForegroundService::class.java
                ).apply { action = MonitorForegroundService.ACTION_DATA_UPDATED }
                startService(refreshIntent)
            }

            finishQuietly()
        }
    }

    private fun finishQuietly() {
        finish()
        overridePendingTransition(0, 0)
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
            error = if (limits.isEmpty()) getString(R.string.notif_no_data) else null
        )
    }

    private fun extractPlanFromOrg(json: String) {
        try {
            val orgs = JSONArray(json)
            if (orgs.length() > 0) {
                val org = orgs.getJSONObject(0)

                // Save org display name (e.g. "Esperoweb") for Settings screen
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

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
