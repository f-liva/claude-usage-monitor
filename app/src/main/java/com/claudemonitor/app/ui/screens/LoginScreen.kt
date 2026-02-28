package com.claudemonitor.app.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.claudemonitor.app.R
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: (cookies: String) -> Unit,
    onBack: (() -> Unit)?
) {
    var isLoading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var loginDetected by remember { mutableStateOf(false) }

    fun checkLoginCookies(): Boolean {
        val cookies = CookieManager.getInstance().getCookie("https://claude.ai") ?: ""
        return cookies.contains("sessionKey")
    }

    fun completeLogin() {
        if (loginDetected) return
        val cookies = CookieManager.getInstance().getCookie("https://claude.ai") ?: ""
        if (cookies.isNotEmpty() && cookies.contains("sessionKey")) {
            loginDetected = true
            onLoginSuccess(cookies)
        }
    }

    LaunchedEffect(Unit) {
        if (checkLoginCookies()) {
            completeLogin()
        }
    }

    LaunchedEffect(Unit) {
        while (!loginDetected) {
            delay(2000)
            if (checkLoginCookies() && currentUrl.isNotEmpty()) {
                val path = currentUrl.removePrefix("https://claude.ai")
                    .split("?").first().split("#").first()
                val isAuthPage = path.startsWith("/login") ||
                        path.startsWith("/oauth") ||
                        path.startsWith("/signup") ||
                        path.startsWith("/sso")
                if (!isAuthPage) {
                    completeLogin()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.login_title), fontWeight = FontWeight.Bold)
                        if (currentUrl.isNotEmpty()) {
                            Text(
                                currentUrl.take(50) + if (currentUrl.length > 50) "..." else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.login_banner),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { completeLogin() },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.login_continue), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                factory = { context ->
                    @SuppressLint("SetJavaScriptEnabled")
                    val webView = WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            userAgentString =
                                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                                if (newProgress == 100) isLoading = false
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                currentUrl = url ?: ""
                                isLoading = false

                                if (url != null && url.startsWith("https://claude.ai")) {
                                    val path = url.removePrefix("https://claude.ai")
                                        .split("?").first().split("#").first()
                                    val isAuthPage = path.startsWith("/login") ||
                                            path.startsWith("/oauth") ||
                                            path.startsWith("/signup") ||
                                            path.startsWith("/sso")
                                    if (!isAuthPage && checkLoginCookies()) {
                                        completeLogin()
                                    }
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false

                                if (url.startsWith("https://claude.ai")) {
                                    val path = url.removePrefix("https://claude.ai")
                                        .split("?").first().split("#").first()
                                    val isAuthPage = path.startsWith("/login") ||
                                            path.startsWith("/oauth") ||
                                            path.startsWith("/signup") ||
                                            path.startsWith("/sso")
                                    if (!isAuthPage && checkLoginCookies()) {
                                        completeLogin()
                                        return true
                                    }
                                }

                                if (url.contains("accounts.google.com") ||
                                    url.contains("claude.ai") ||
                                    url.contains("anthropic.com") ||
                                    url.contains("apple.com") ||
                                    url.contains("login") ||
                                    url.contains("oauth") ||
                                    url.contains("auth")
                                ) {
                                    return false
                                }
                                return true
                            }
                        }

                        loadUrl("https://claude.ai/login")
                    }
                    webView
                }
            )
        }
    }
}
