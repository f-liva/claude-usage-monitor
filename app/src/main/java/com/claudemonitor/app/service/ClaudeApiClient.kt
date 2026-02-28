package com.claudemonitor.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.*
import com.claudemonitor.app.data.model.ModelLimit
import com.claudemonitor.app.data.model.UsageData
import org.json.JSONArray

/**
 * Fetches Claude usage data by navigating a hidden WebView to /settings/usage
 * and extracting the rendered DOM content.
 */
class ClaudeApiClient(private val context: Context) {

    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var currentCallback: ((UsageData) -> Unit)? = null
    private var isInitialized = false
    private var orgName: String = ""

    companion object {
        private const val TAG = "ClaudeApiClient"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"

        private const val FETCH_ORG_JS = """
            (async function() {
                try {
                    var resp = await fetch('/api/organizations', {
                        credentials: 'include',
                        headers: { 'Accept': 'application/json' }
                    });
                    if (resp.ok) {
                        var data = await resp.json();
                        Android.onOrgData(JSON.stringify(data));
                    }
                } catch(e) {
                    Android.onLog('org fetch error: ' + e.message);
                }
            })();
        """

        private const val EXTRACT_DOM_JS = """
            (function() {
                try {
                    var text = document.body ? document.body.innerText : '';
                    Android.onDomContent(text);
                } catch(e) {
                    Android.onLog('dom extract error: ' + e.message);
                }
            })();
        """
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun initialize(cookies: String) {
        if (isInitialized) return
        handler.post {
            try {
                if (cookies.isNotEmpty()) {
                    val cookieManager = CookieManager.getInstance()
                    cookies.split(";").forEach { cookie ->
                        cookieManager.setCookie("https://claude.ai", cookie.trim())
                    }
                    cookieManager.flush()
                }

                webView = WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = USER_AGENT
                        cacheMode = WebSettings.LOAD_DEFAULT
                        blockNetworkImage = true
                    }
                    visibility = View.GONE
                    addJavascriptInterface(JSBridge(), "Android")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            try {
                                super.onPageFinished(view, url)
                                val u = url ?: return
                                Log.d(TAG, "onPageFinished: $u")

                                if (u.contains("/settings/usage")) {
                                    view?.evaluateJavascript(FETCH_ORG_JS, null)
                                    handler.postDelayed({
                                        try { view?.evaluateJavascript(EXTRACT_DOM_JS, null) }
                                        catch (e: Exception) { Log.e(TAG, "JS inject error", e) }
                                    }, 3000)
                                    handler.postDelayed({
                                        try { view?.evaluateJavascript(EXTRACT_DOM_JS, null) }
                                        catch (e: Exception) { Log.e(TAG, "JS inject error", e) }
                                    }, 6000)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "onPageFinished error", e)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?, request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            Log.e(TAG, "WebView error: ${error?.description}")
                        }
                    }
                }
                isInitialized = true
                Log.d(TAG, "Initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Initialize failed", e)
                returnError("WebView init failed: ${e.message}")
            }
        }
    }

    fun updateCookies(cookies: String) {
        if (cookies.isEmpty()) return
        try {
            val cookieManager = CookieManager.getInstance()
            cookies.split(";").forEach { cookie ->
                cookieManager.setCookie("https://claude.ai", cookie.trim())
            }
            cookieManager.flush()
        } catch (e: Exception) {
            Log.e(TAG, "updateCookies failed", e)
        }
    }

    fun fetchUsage(callback: (UsageData) -> Unit) {
        currentCallback = callback

        handler.post {
            try {
                if (webView == null) {
                    returnError("WebView not ready")
                    return@post
                }
                Log.d(TAG, "Loading /settings/usage")
                webView?.loadUrl("https://claude.ai/settings/usage")
            } catch (e: Exception) {
                Log.e(TAG, "fetchUsage loadUrl failed", e)
                returnError("Load failed: ${e.message}")
            }
        }

        // Timeout after 15 seconds
        handler.postDelayed({
            if (currentCallback != null) {
                Log.d(TAG, "Fetch timed out")
                returnError("Timeout — page took too long to load")
            }
        }, 15000)
    }

    fun destroy() {
        handler.post {
            try {
                webView?.stopLoading()
                webView?.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "destroy error", e)
            }
            webView = null
            isInitialized = false
        }
    }

    private fun returnError(msg: String) {
        val cb = currentCallback
        currentCallback = null
        cb?.invoke(UsageData(
            error = msg,
            isLoading = false,
            lastUpdated = System.currentTimeMillis()
        ))
    }

    private fun returnData(data: UsageData) {
        val cb = currentCallback
        currentCallback = null
        cb?.invoke(data)
    }

    private fun parseDomContent(text: String): UsageData {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val limits = mutableListOf<ModelLimit>()
        var planName = orgName

        // Find plan name
        for (line in lines) {
            val lower = line.lowercase()
            if (lower.contains("max") && (lower.contains("plan") || lower.contains("piano"))) {
                planName = "Max"; break
            } else if (lower.contains("pro") && (lower.contains("plan") || lower.contains("piano"))) {
                planName = "Pro"; break
            }
        }

        // Parse percentage sections
        val pctRegex = Regex("""(\d+)%\s*(utilizzato|used|utilizado)""", RegexOption.IGNORE_CASE)
        val resetRegex = Regex("""(?:Si ripristina|Resets?|Rinnovo)\s+(.+)""", RegexOption.IGNORE_CASE)

        for (i in lines.indices) {
            val pctMatch = pctRegex.find(lines[i]) ?: continue
            val percent = pctMatch.groupValues[1].toIntOrNull() ?: continue

            var sectionName = ""
            var resetTime = ""

            // Look backwards for context
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
                    prev.length in 3..60
                ) {
                    sectionName = prev
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
            error = if (limits.isEmpty()) "Page loaded but no usage data found" else null
        )
    }

    private fun extractPlanFromOrg(json: String) {
        try {
            val orgs = JSONArray(json)
            if (orgs.length() > 0) {
                val org = orgs.getJSONObject(0)

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

                orgName = org.optString("name", "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractPlanFromOrg error", e)
        }
    }

    inner class JSBridge {
        @JavascriptInterface
        fun onOrgData(json: String) {
            try { extractPlanFromOrg(json) }
            catch (e: Exception) { Log.e(TAG, "onOrgData error", e) }
        }

        @JavascriptInterface
        fun onDomContent(text: String) {
            try {
                if (text.isBlank() || currentCallback == null) return

                val hasUsageData = text.contains("%") &&
                        (text.contains("utilizzato", ignoreCase = true) ||
                         text.contains("used", ignoreCase = true))

                Log.d(TAG, "onDomContent: ${text.length} chars, hasUsageData=$hasUsageData")

                if (hasUsageData) {
                    handler.post {
                        try {
                            val data = parseDomContent(text)
                            returnData(data)
                        } catch (e: Exception) {
                            Log.e(TAG, "parseDomContent error", e)
                            returnError("Parse error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onDomContent error", e)
            }
        }

        @JavascriptInterface
        fun onLog(msg: String) {
            Log.d(TAG, "JS: $msg")
        }
    }
}
