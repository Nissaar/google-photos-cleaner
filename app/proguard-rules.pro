# Tink (pulled in by androidx.security.crypto for the Keystore-backed prefs) is
# compiled against ErrorProne annotations that are compile-time only and are not
# packaged. They are never needed at runtime.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# SQLCipher ships JNI entry points that R8 cannot see are used.
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }
-dontwarn net.zetetic.**

# Room and kotlinx.serialization ship their own consumer rules, and this app uses no
# @Serializable classes, so neither needs anything here.

# The legacy WebView bridge is called from JavaScript, so its methods must survive.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Strip logging from release builds so nothing about the session can leak to logcat.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
