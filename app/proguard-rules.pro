# Tink (pulled in by androidx.security.crypto for the Keystore-backed prefs) is
# compiled against ErrorProne annotations that are compile-time only and are not
# packaged. They are never needed at runtime.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# SQLCipher ships JNI entry points that R8 cannot see are used.
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }
-dontwarn net.zetetic.**

# Room generates implementations reflectively at runtime.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# kotlinx.serialization keeps its serializers in companion/synthetic members.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The legacy WebView bridge is called from JavaScript, so its methods must survive.
-keepclassmembers class xyz.photocleaner.session.GPhotosSession$LegacyBridge {
    @android.webkit.JavascriptInterface <methods>;
}
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
