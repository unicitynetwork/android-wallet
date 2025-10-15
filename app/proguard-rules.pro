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
-keep class org.unicitylabs.wallet.data.model.** { *; }

# Jackson - Critical for TypeReference to work with generics
-keep class com.fasterxml.jackson.** { *; }
-keepattributes *Annotation*,EnclosingMethod,Signature,InnerClasses
-keepnames class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.databind.**

# Keep generic signatures for Jackson TypeReference - CRITICAL
-keepattributes Signature
-keep class com.fasterxml.jackson.core.type.TypeReference { *; }
-keep class * extends com.fasterxml.jackson.core.type.TypeReference { *; }

# Prevent ANY optimization of TypeReference anonymous classes
-keep class **$$TypeReference { *; }
-keep class **$TypeReference$* { *; }

# Keep all data model classes used with Jackson - no obfuscation at all
-keep,allowobfuscation class org.unicitylabs.wallet.data.model.** { *; }
-keep,allowobfuscation class org.unicitylabs.wallet.data.remote.** { *; }
-keepclassmembers class org.unicitylabs.wallet.data.model.** { *; }
-keepclassmembers class org.unicitylabs.wallet.data.remote.** { *; }

# BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.** { *; }
-keepnames class okio.** { *; }

# Unicity SDK
-keep class org.unicitylabs.sdk.** { *; }
-keepclassmembers class org.unicitylabs.sdk.** {
    public <methods>;
    public <fields>;
}

# SLF4J and Logback
-keep class org.slf4j.** { *; }
-keep class ch.qos.logback.** { *; }
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**

# Unicity Nostr SDK
-keep class org.unicitylabs.nostr.** { *; }
-keepclassmembers class org.unicitylabs.nostr.** {
    public <methods>;
    public <fields>;
}

# Secp256k1
-keep class fr.acinq.secp256k1.** { *; }
-dontwarn fr.acinq.secp256k1.**

# SpongyCastle
-keep class com.madgag.spongycastle.** { *; }
-dontwarn com.madgag.spongycastle.**

# Guava
-dontwarn com.google.common.**
-dontwarn com.google.errorprone.**
-dontwarn com.google.j2objc.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**

# Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# Keep all Retrofit service interfaces
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Keep API service interfaces and their return types
-keep interface org.unicitylabs.wallet.data.remote.** { *; }
-keep class org.unicitylabs.wallet.data.remote.** { *; }

-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Google Play Services
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# BitcoinJ (for BIP-39)
-keep class org.bitcoinj.** { *; }
-dontwarn org.bitcoinj.**

# Keep all data classes and their members (critical for serialization)
-keep class org.unicitylabs.wallet.data.** { *; }
-keep class org.unicitylabs.wallet.model.** { *; }
-keepclassmembers class org.unicitylabs.wallet.data.** { *; }
-keepclassmembers class org.unicitylabs.wallet.model.** { *; }

# Prevent obfuscation of any class used with Jackson/Retrofit
-keepnames class org.unicitylabs.wallet.data.** { *; }
-keepnames class org.unicitylabs.wallet.model.** { *; }

# LibPhoneNumber
-keep class com.google.i18n.phonenumbers.** { *; }
-dontwarn com.google.i18n.phonenumbers.**