# Keep WebView JavaScript interface
-keepclassmembers class com.claudemonitor.app.service.ClaudeWebScraper$JSInterface {
    public *;
}
-keepattributes JavascriptInterface
