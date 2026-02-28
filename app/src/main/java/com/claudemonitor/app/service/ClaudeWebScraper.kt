package com.claudemonitor.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.claudemonitor.app.data.model.ModelLimit
import com.claudemonitor.app.data.model.UsageData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Headless WebView-based scraper for Claude usage data.
 * Uses an invisible WebView to load claude.ai and extract usage information
 * via JavaScript injection.
 */
class ClaudeWebScraper(private val context: Context) {

    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val _usageData = MutableStateFlow(UsageData())
    val usageData: StateFlow<UsageData> = _usageData

    private var scrapeCallback: ((UsageData) -> Unit)? = null

    companion object {
        private const val CLAUDE_BASE_URL = "https://claude.ai"
        private const val CLAUDE_SETTINGS_URL = "https://claude.ai/settings"
        private const val CLAUDE_API_USAGE_URL = "https://claude.ai/api/organizations"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"

        // JavaScript to extract usage data from the settings/usage page
        private const val EXTRACT_USAGE_JS = """
            (function() {
                try {
                    var result = { planName: '', models: [], resetTime: '', error: null };

                    // Try to find usage info from the page DOM
                    var allText = document.body ? document.body.innerText : '';

                    // Look for plan name
                    var planMatch = allText.match(/(Pro|Max|Team|Free)\s*(Plan|plan)?/i);
                    if (planMatch) result.planName = planMatch[0];

                    // Look for usage indicators - progress bars, counters etc.
                    var progressBars = document.querySelectorAll('[role="progressbar"], .progress, [class*="progress"], [class*="usage"]');
                    var usageTexts = document.querySelectorAll('[class*="limit"], [class*="usage"], [class*="quota"]');

                    // Try parsing usage text patterns like "45/100 messages" or "45 of 100"
                    var usagePattern = /(\d+)\s*(?:\/|of)\s*(\d+)\s*(messages?|tokens?|requests?)?/gi;
                    var matches = allText.matchAll ? [...allText.matchAll(usagePattern)] : [];

                    for (var i = 0; i < matches.length; i++) {
                        var m = matches[i];
                        result.models.push({
                            modelName: 'Model ' + (i + 1),
                            used: parseInt(m[1]),
                            total: parseInt(m[2]),
                            unit: m[3] || 'messages'
                        });
                    }

                    // Look for reset time
                    var resetMatch = allText.match(/resets?\s*(in\s*)?(\d+\s*(hours?|minutes?|days?))/i);
                    if (resetMatch) result.resetTime = resetMatch[0];

                    // Try the model-specific patterns
                    var modelNames = ['Opus', 'Sonnet', 'Haiku', 'Claude 3.5', 'Claude 4'];
                    modelNames.forEach(function(name) {
                        var regex = new RegExp(name + '[^\\d]*(\\d+)\\s*(?:\\/|of)\\s*(\\d+)', 'i');
                        var match = allText.match(regex);
                        if (match) {
                            result.models.push({
                                modelName: name,
                                used: parseInt(match[1]),
                                total: parseInt(match[2]),
                                unit: 'messages'
                            });
                        }
                    });

                    return JSON.stringify(result);
                } catch(e) {
                    return JSON.stringify({ error: e.message, models: [] });
                }
            })();
        """

        // JavaScript to intercept API responses for more accurate data
        private const val INTERCEPT_API_JS = """
            (function() {
                if (window.__claudeMonitorInstalled) return;
                window.__claudeMonitorInstalled = true;
                window.__claudeUsageData = null;

                var origFetch = window.fetch;
                window.fetch = function() {
                    return origFetch.apply(this, arguments).then(function(response) {
                        var url = arguments[0];
                        if (typeof url === 'string' && (url.includes('usage') || url.includes('rate_limit') || url.includes('subscription'))) {
                            response.clone().json().then(function(data) {
                                window.__claudeUsageData = JSON.stringify(data);
                                if (window.ClaudeMonitor) {
                                    window.ClaudeMonitor.onApiData(JSON.stringify(data));
                                }
                            }).catch(function() {});
                        }
                        return response;
                    });
                };

                var origXHR = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__url = url;
                    return origXHR.apply(this, arguments);
                };
                var origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function() {
                    this.addEventListener('load', function() {
                        if (this.__url && (this.__url.includes('usage') || this.__url.includes('rate_limit') || this.__url.includes('subscription'))) {
                            try {
                                var data = JSON.parse(this.responseText);
                                window.__claudeUsageData = this.responseText;
                                if (window.ClaudeMonitor) {
                                    window.ClaudeMonitor.onApiData(this.responseText);
                                }
                            } catch(e) {}
                        }
                    });
                    return origSend.apply(this, arguments);
                };
            })();
        """
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun initialize() {
        handler.post {
            try {
                webView = WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = USER_AGENT
                        cacheMode = WebSettings.LOAD_DEFAULT
                        databaseEnabled = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        setSupportMultipleWindows(false)
                        blockNetworkImage = true // Speed up by not loading images
                    }

                    addJavascriptInterface(JSInterface(), "ClaudeMonitor")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // Inject API interceptor on every page load
                            view?.evaluateJavascript(INTERCEPT_API_JS, null)

                            // After a delay, try to extract usage data from DOM
                            handler.postDelayed({
                                view?.evaluateJavascript(EXTRACT_USAGE_JS) { result ->
                                    parseScrapedData(result)
                                }
                            }, 3000)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            return !url.startsWith(CLAUDE_BASE_URL)
                        }
                    }

                    // Keep WebView invisible - headless mode
                    visibility = android.view.View.GONE
                }
            } catch (e: Exception) {
                _usageData.value = UsageData(
                    error = "WebView not available: ${e.message}",
                    isLoading = false
                )
            }
        }
    }

    fun loadLoginPage(onPageReady: () -> Unit = {}) {
        handler.post {
            webView?.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    onPageReady()
                }
            }
            webView?.loadUrl(CLAUDE_BASE_URL)
        }
    }

    fun getWebView(): WebView? = webView

    fun fetchUsage(callback: ((UsageData) -> Unit)? = null) {
        scrapeCallback = callback
        _usageData.value = _usageData.value.copy(isLoading = true, error = null)

        handler.post {
            webView?.let { wv ->
                // First inject the API interceptor
                wv.evaluateJavascript(INTERCEPT_API_JS, null)

                // Navigate to settings/usage page to trigger usage data loading
                wv.loadUrl(CLAUDE_SETTINGS_URL)

                // Fallback: scrape DOM after delay
                handler.postDelayed({
                    wv.evaluateJavascript(EXTRACT_USAGE_JS) { result ->
                        parseScrapedData(result)
                    }
                }, 5000)
            } ?: run {
                val errorData = UsageData(
                    error = "WebView not initialized",
                    isLoading = false
                )
                _usageData.value = errorData
                callback?.invoke(errorData)
            }
        }
    }

    private fun parseScrapedData(rawJson: String?) {
        try {
            // WebView returns the result wrapped in quotes with escaped content
            val cleanJson = rawJson
                ?.trim()
                ?.removeSurrounding("\"")
                ?.replace("\\\"", "\"")
                ?.replace("\\n", "\n")
                ?: return

            val json = JSONObject(cleanJson)
            val error = json.optString("error", "").ifEmpty { null }
            val planName = json.optString("planName", "")
            val resetTime = json.optString("resetTime", "")
            val modelsArray = json.optJSONArray("models") ?: JSONArray()

            val models = mutableListOf<ModelLimit>()
            for (i in 0 until modelsArray.length()) {
                val m = modelsArray.getJSONObject(i)
                models.add(
                    ModelLimit(
                        modelName = m.optString("modelName", "Unknown"),
                        used = m.optInt("used", 0),
                        total = m.optInt("total", 0),
                        unit = m.optString("unit", "messages")
                    )
                )
            }

            val data = UsageData(
                planName = planName,
                modelLimits = models,
                resetTime = resetTime,
                lastUpdated = System.currentTimeMillis(),
                isLoading = false,
                error = error
            )

            _usageData.value = data
            scrapeCallback?.invoke(data)
        } catch (e: Exception) {
            val errorData = UsageData(
                error = "Parse error: ${e.message}",
                isLoading = false,
                lastUpdated = System.currentTimeMillis()
            )
            _usageData.value = errorData
            scrapeCallback?.invoke(errorData)
        }
    }

    fun parseApiResponse(jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val models = mutableListOf<ModelLimit>()

            // Parse various API response formats
            if (json.has("rate_limit")) {
                val rl = json.getJSONObject("rate_limit")
                models.add(
                    ModelLimit(
                        modelName = rl.optString("model", "Claude"),
                        used = rl.optInt("used", 0),
                        total = rl.optInt("limit", 0),
                        unit = "messages",
                        resetPeriod = rl.optString("reset_period", "")
                    )
                )
            }

            if (json.has("usage")) {
                val usage = json.getJSONObject("usage")
                val keys = usage.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val item = usage.optJSONObject(key)
                    if (item != null) {
                        models.add(
                            ModelLimit(
                                modelName = key,
                                used = item.optInt("used", 0),
                                total = item.optInt("limit", item.optInt("total", 0)),
                                unit = item.optString("unit", "messages"),
                                resetPeriod = item.optString("reset_period", "")
                            )
                        )
                    }
                }
            }

            if (models.isNotEmpty()) {
                val data = UsageData(
                    planName = json.optString("plan", _usageData.value.planName),
                    modelLimits = models,
                    resetTime = json.optString("reset_time", ""),
                    lastUpdated = System.currentTimeMillis(),
                    isLoading = false
                )
                _usageData.value = data
                scrapeCallback?.invoke(data)
            }
        } catch (_: Exception) { }
    }

    fun getCookies(): String {
        return CookieManager.getInstance().getCookie(CLAUDE_BASE_URL) ?: ""
    }

    fun setCookies(cookies: String) {
        val cookieManager = CookieManager.getInstance()
        cookies.split(";").forEach { cookie ->
            cookieManager.setCookie(CLAUDE_BASE_URL, cookie.trim())
        }
        cookieManager.flush()
    }

    fun isLoggedIn(): Boolean {
        val cookies = getCookies()
        return cookies.contains("sessionKey") || cookies.contains("__cf_bm")
    }

    fun destroy() {
        handler.post {
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }

    inner class JSInterface {
        @JavascriptInterface
        fun onApiData(jsonString: String) {
            parseApiResponse(jsonString)
        }

        @JavascriptInterface
        fun onUsageUpdate(jsonString: String) {
            parseScrapedData("\"${jsonString.replace("\"", "\\\"")}\"")
        }
    }
}
