package com.claudemonitor.app.service

import com.claudemonitor.app.data.model.ModelLimit
import com.claudemonitor.app.data.model.UsageData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Direct HTTP client for Claude.ai's internal API.
 * Uses session cookies from the login WebView to fetch usage data.
 */
class ClaudeApiClient {

    companion object {
        private const val BASE_URL = "https://claude.ai"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
    }

    /**
     * Fetches usage data from Claude.ai API.
     * 1. GET /api/organizations → org UUID + plan info
     * 2. Try rate limit/usage endpoints
     */
    suspend fun fetchUsage(cookies: String): UsageData = withContext(Dispatchers.IO) {
        if (cookies.isBlank()) {
            return@withContext UsageData(
                error = "Not logged in",
                isLoading = false,
                lastUpdated = System.currentTimeMillis()
            )
        }

        try {
            // Step 1: Get organization info
            val orgsResponse = httpGet("/api/organizations", cookies)
            val orgs = JSONArray(orgsResponse)

            if (orgs.length() == 0) {
                return@withContext UsageData(
                    error = "No organization found",
                    isLoading = false,
                    lastUpdated = System.currentTimeMillis()
                )
            }

            val org = orgs.getJSONObject(0)
            val orgId = org.getString("uuid")
            val planName = extractPlanName(org)

            // Step 2: Try to fetch rate limit / usage data from various endpoints
            val rateLimitResult = tryFetchRateLimits(orgId, cookies)

            UsageData(
                planName = planName,
                modelLimits = rateLimitResult.models,
                resetTime = rateLimitResult.resetTime,
                lastUpdated = System.currentTimeMillis(),
                isLoading = false,
                error = rateLimitResult.error
            )
        } catch (e: HttpException) {
            val errorMsg = when (e.code) {
                401, 403 -> "Session expired — please log in again"
                429 -> "Rate limited — try again later"
                else -> "Server error (HTTP ${e.code})"
            }
            UsageData(
                error = errorMsg,
                isLoading = false,
                lastUpdated = System.currentTimeMillis()
            )
        } catch (e: IOException) {
            UsageData(
                error = "Connection error: ${e.message}",
                isLoading = false,
                lastUpdated = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            UsageData(
                error = "Error: ${e.message}",
                isLoading = false,
                lastUpdated = System.currentTimeMillis()
            )
        }
    }

    private fun extractPlanName(org: JSONObject): String {
        // Try various known fields where plan info might be
        val candidates = listOf(
            // Direct fields
            { org.optString("plan_display_name", "") },
            { org.optString("plan_type", "") },
            { org.optString("billing_type", "") },
            // Nested in settings
            {
                org.optJSONObject("settings")
                    ?.optString("plan_type", "") ?: ""
            },
            // Nested in subscription
            {
                org.optJSONObject("subscription")
                    ?.optString("plan", "") ?: ""
            },
            {
                org.optJSONObject("subscription")
                    ?.optString("type", "") ?: ""
            },
            // Active flags might indicate plan
            {
                val flags = org.optJSONArray("active_flags")
                if (flags != null) {
                    for (i in 0 until flags.length()) {
                        val flag = flags.optString(i, "")
                        if (flag.contains("pro", ignoreCase = true) ||
                            flag.contains("max", ignoreCase = true) ||
                            flag.contains("team", ignoreCase = true)
                        ) return@listOf flag
                    }
                }
                ""
            },
            // Capabilities might hint at plan
            {
                val caps = org.optJSONArray("capabilities")
                if (caps != null) {
                    for (i in 0 until caps.length()) {
                        val cap = caps.optString(i, "")
                        if (cap.contains("pro", ignoreCase = true) ||
                            cap.contains("max", ignoreCase = true)
                        ) return@listOf cap
                    }
                }
                ""
            },
            // Fallback: org name
            { org.optString("name", "") }
        )

        for (candidate in candidates) {
            val value = candidate()
            if (value.isNotEmpty()) {
                return formatPlanName(value)
            }
        }
        return ""
    }

    private fun formatPlanName(raw: String): String {
        val lower = raw.lowercase()
        return when {
            lower.contains("max_5x") || lower.contains("max5x") -> "Max (5x)"
            lower.contains("max_20x") || lower.contains("max20x") -> "Max (20x)"
            lower.contains("max") -> "Max"
            lower.contains("pro") -> "Pro"
            lower.contains("team") -> "Team"
            lower.contains("enterprise") -> "Enterprise"
            lower.contains("free") -> "Free"
            else -> raw.replaceFirstChar { it.uppercase() }
        }
    }

    private data class RateLimitResult(
        val models: List<ModelLimit> = emptyList(),
        val resetTime: String = "",
        val error: String? = null
    )

    private fun tryFetchRateLimits(orgId: String, cookies: String): RateLimitResult {
        // Try various endpoints that might return rate limit / usage data
        val endpoints = listOf(
            "/api/organizations/$orgId/rate_limits",
            "/api/organizations/$orgId/usage",
            "/api/organizations/$orgId/rate_limit",
            "/api/organizations/$orgId/settings/billing",
            "/api/organizations/$orgId/settings"
        )

        for (endpoint in endpoints) {
            try {
                val response = httpGet(endpoint, cookies)
                val result = parseRateLimitResponse(response)
                if (result.models.isNotEmpty()) {
                    return result
                }
            } catch (_: HttpException) {
                // Endpoint doesn't exist or not authorized, try next
            } catch (_: Exception) {
                // Parse error or other issue, try next
            }
        }

        // No rate limit data found from any endpoint
        return RateLimitResult(
            error = "Usage data unavailable — plan info shown above"
        )
    }

    private fun parseRateLimitResponse(json: String): RateLimitResult {
        val models = mutableListOf<ModelLimit>()
        var resetTime = ""

        // Try parsing as JSON object
        try {
            val obj = JSONObject(json)
            models.addAll(parseModelsFromObject(obj))
            resetTime = obj.optString("resets_at", obj.optString("reset_time", ""))
            if (models.isNotEmpty()) return RateLimitResult(models, resetTime)
        } catch (_: Exception) {}

        // Try parsing as JSON array
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                models.addAll(parseModelsFromObject(item))
                if (resetTime.isEmpty()) {
                    resetTime = item.optString("resets_at", item.optString("reset_time", ""))
                }
            }
            if (models.isNotEmpty()) return RateLimitResult(models, resetTime)
        } catch (_: Exception) {}

        return RateLimitResult()
    }

    private fun parseModelsFromObject(obj: JSONObject): List<ModelLimit> {
        val models = mutableListOf<ModelLimit>()

        // Pattern 1: Direct rate_limit object
        obj.optJSONObject("rate_limit")?.let { rl ->
            parseModelFromObject(rl, rl.optString("model", "Claude"))?.let { models.add(it) }
        }

        // Pattern 2: "usage" object with model keys
        obj.optJSONObject("usage")?.let { usage ->
            val keys = usage.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                usage.optJSONObject(key)?.let { item ->
                    parseModelFromObject(item, key)?.let { models.add(it) }
                }
            }
        }

        // Pattern 3: "models" or "rate_limits" array
        val arrayKeys = listOf("models", "rate_limits", "limits", "model_limits")
        for (key in arrayKeys) {
            obj.optJSONArray(key)?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { item ->
                        val name = item.optString(
                            "model",
                            item.optString("model_name", item.optString("name", "Model ${i + 1}"))
                        )
                        parseModelFromObject(item, name)?.let { models.add(it) }
                    }
                }
            }
        }

        // Pattern 4: Top-level object IS a rate limit entry
        if (models.isEmpty()) {
            parseModelFromObject(obj, obj.optString("model", ""))?.let { models.add(it) }
        }

        return models
    }

    private fun parseModelFromObject(obj: JSONObject, defaultName: String): ModelLimit? {
        // Look for usage numbers in various common field names
        val used = obj.optInt("used",
            obj.optInt("current",
                obj.optInt("consumed",
                    obj.optInt("count", -1))))
        val total = obj.optInt("limit",
            obj.optInt("total",
                obj.optInt("max",
                    obj.optInt("allowed",
                        obj.optInt("quota", -1)))))

        if (used < 0 || total <= 0) return null

        val name = obj.optString("model",
            obj.optString("model_name",
                obj.optString("name", defaultName)))
            .ifEmpty { defaultName }

        if (name.isEmpty()) return null

        return ModelLimit(
            modelName = formatModelName(name),
            used = used,
            total = total,
            unit = obj.optString("unit", "messages"),
            resetPeriod = obj.optString("reset_period",
                obj.optString("resets_at",
                    obj.optString("reset_time", "")))
        )
    }

    private fun formatModelName(raw: String): String {
        val lower = raw.lowercase()
        return when {
            lower.contains("opus") -> "Claude Opus"
            lower.contains("sonnet") -> "Claude Sonnet"
            lower.contains("haiku") -> "Claude Haiku"
            lower.contains("claude-4") || lower.contains("claude_4") -> "Claude 4"
            lower.contains("claude-3.5") || lower.contains("claude_3_5") -> "Claude 3.5"
            lower.contains("claude-3") || lower.contains("claude_3") -> "Claude 3"
            else -> raw.replaceFirstChar { it.uppercase() }
        }
    }

    @Throws(HttpException::class, IOException::class)
    private fun httpGet(path: String, cookies: String): String {
        val url = URL("$BASE_URL$path")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Cookie", cookies)
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            conn.setRequestProperty("Referer", "https://claude.ai/")
            conn.setRequestProperty("Origin", "https://claude.ai")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorBody = try {
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                } catch (_: Exception) { "" }
                throw HttpException(responseCode, errorBody)
            }

            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    class HttpException(val code: Int, val body: String) :
        Exception("HTTP $code: ${body.take(200)}")
}
