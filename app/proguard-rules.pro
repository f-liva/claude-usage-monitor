# Keep WebView JavaScript interfaces (anonymous classes in RefreshActivity)
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep data model classes used with JSON serialization
-keep class com.claudemonitor.app.data.model.** { *; }
