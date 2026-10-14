# Keep JS interface methods
-keepclassmembers class com.ascend.health.** {
    @android.webkit.JavascriptInterface <methods>;
}
