# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep Gson classes
-keep class com.google.gson.** { *; }
-keep class com.unicity.nfcwalletdemo.data.model.** { *; }

# Jackson
-keep class com.fasterxml.jackson.** { *; }
-keepattributes *Annotation*,EnclosingMethod,Signature
-keepnames class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.databind.**

# BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.** { *; }
-keepnames class okio.** { *; }

# Unicity SDK
-keep class com.unicity.sdk.** { *; }
-keepclassmembers class com.unicity.sdk.** {
    public <methods>;
    public <fields>;
}

# SLF4J
-keep class org.slf4j.** { *; }
-keep class ch.qos.logback.** { *; }
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**