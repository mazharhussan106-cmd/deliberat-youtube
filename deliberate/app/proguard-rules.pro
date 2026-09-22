# Minification is off for this build (see app/build.gradle.kts).
# If you ever turn it on, keep the WebView JS bridge surface:
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
