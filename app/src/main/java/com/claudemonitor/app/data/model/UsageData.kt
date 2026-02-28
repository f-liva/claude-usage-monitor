package com.claudemonitor.app.data.model

data class UsageData(
    val planName: String = "",
    val modelLimits: List<ModelLimit> = emptyList(),
    val resetTime: String = "",
    val lastUpdated: Long = System.currentTimeMillis(),
    val isLoading: Boolean = false,
    val error: String? = null
)

data class ModelLimit(
    val modelName: String,
    val used: Int,
    val total: Int,
    val unit: String = "messages",
    val resetPeriod: String = ""
) {
    val percentage: Float
        get() = if (total > 0) (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

    val remaining: Int
        get() = (total - used).coerceAtLeast(0)

    val isNearLimit: Boolean
        get() = percentage >= 0.8f

    val isAtLimit: Boolean
        get() = percentage >= 1.0f
}

enum class LoginState {
    LOADING,
    NOT_LOGGED_IN,
    LOGGING_IN,
    LOGGED_IN,
    ERROR
}

data class SessionState(
    val loginState: LoginState = LoginState.NOT_LOGGED_IN,
    val sessionToken: String? = null,
    val accountEmail: String? = null,
    val errorMessage: String? = null
)
