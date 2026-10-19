# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Preserve line number information for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep all native methods (JNI)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep all classes with native methods and their members
-keep class ab.inque.visiontechai.DepthNative { *; }
-keep class ab.inque.visiontechai.ResampleNative { *; }
-keep class ab.inque.visiontechai.TextTranslatorNative { *; }
-keep class ab.inque.visiontechai.VideoNative { *; }

# TensorFlow Lite / LiteRT (org.tensorflow.lite API + com.google.ai.edge.litert)
-keep class org.tensorflow.lite.** { *; }
-keep interface org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn com.google.ai.edge.litert.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Google Play Services (used by ML Kit)
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Play Billing Library — keep so Play Console can detect version 8.x in the AAB
-keep class com.android.billingclient.** { *; }
-keep class com.google.android.gms.internal.play_billing.** { *; }
-dontwarn com.android.billingclient.**

# Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# CameraX
-keep class androidx.camera.** { *; }
-keep interface androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Keep ViewBinding classes
-keep class ab.inque.visiontechai.databinding.** { *; }

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep custom views
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}