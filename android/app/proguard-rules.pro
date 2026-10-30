# Keep the JS bridge methods (only relevant if minify is enabled).
-keepclassmembers class com.kosign.minibank.Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
