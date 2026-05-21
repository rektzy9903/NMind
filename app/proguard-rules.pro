-keep class com.claudecodesetup.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keep public class * extends android.webkit.WebViewClient
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Preserve JNI native method declarations
-keepclasseswithmembernames class * {
    native <methods>;
}
