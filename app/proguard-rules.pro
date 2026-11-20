# ---------------------------------------------------------------------------------------------
# OneProxyClick - R8 rules (release: minify + shrinkResources).
# AndroidX, Compose, Room, DataStore, coroutines, OkHttp and Play Billing ship consumer rules;
# the rules below cover what they don't plus explicit safety nets.
# ---------------------------------------------------------------------------------------------

-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions
-keepattributes *Annotation*,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Netty --------------------------------------------------------------------------------------
# Netty uses reflection heavily (ChannelHandler @Sharable/@Skip detection, Unsafe/PlatformDependent,
# ReferenceCounted leak detector, SslContext provider lookup), so it is kept untouched.
-keep class io.netty.** { *; }
-keep interface io.netty.** { *; }
-keepclassmembers class * extends io.netty.channel.ChannelHandler { *; }
-keepclassmembernames class io.netty.util.internal.shaded.org.jctools.** { *; }
-dontwarn io.netty.**
-dontwarn sun.misc.Unsafe
-dontwarn sun.security.**
-dontwarn org.slf4j.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn reactor.blockhound.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn org.conscrypt.**
-dontwarn org.eclipse.jetty.**
-dontwarn com.google.protobuf.**
-dontwarn io.netty.internal.tcnative.**
-dontwarn org.jboss.marshalling.**
-dontwarn com.oracle.svm.**
-dontwarn org.graalvm.**

# --- Certificates / TLS (BouncyCastle + JCA) ------------------------------------------------------
# JCA instantiates provider SPI classes by reflection from string names (KeyPairGenerator,
# Signature, CertificateFactory, KeyStore...), so BouncyCastle must be kept in full.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
# Our own TrustManagers / KeyManagers / SNI mapping are called back by the platform TLS stack.
-keep class * implements javax.net.ssl.X509TrustManager { *; }
-keep class * extends javax.net.ssl.X509ExtendedTrustManager { *; }
-keep class * extends javax.net.ssl.X509ExtendedKeyManager { *; }
-keep class * implements javax.net.ssl.X509KeyManager { *; }
-keep class * implements javax.net.ssl.HostnameVerifier { *; }
-keep class * extends java.security.Provider { *; }

# --- Brotli decoder -----------------------------------------------------------------------------
-keep class org.brotli.dec.** { *; }

# --- OkHttp (consumer rules cover most; platform probing is reflective) ----------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.openjsse.**

# --- Kotlinx Serialization ------------------------------------------------------------------------
# Not a dependency today (JSON is handled with org.json); these rules keep @Serializable models
# safe if it is added later and are no-ops otherwise.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** serializer(...);
}
-dontwarn kotlinx.serialization.**

# --- Google Play Billing (play flavor) -----------------------------------------------------------
# The library ships consumer rules; this is a safety net for AIDL / Parcelable reflection.
-keep class com.android.billingclient.** { *; }
-keep class com.android.vending.billing.** { *; }
-dontwarn com.android.billingclient.**
# Pro managers are selected per flavor; keep them even though only one is referenced.
-keep class com.oneproxyclick.app.pro.** { *; }
-keep class com.oneproxyclick.core.common.pro.** { *; }

# --- App components / models --------------------------------------------------------------------
# Manifest components get keep rules from AAPT; models serialized to JSON by name are kept here.
-keep class com.oneproxyclick.core.interceptor.model.** { *; }
-keep class com.oneproxyclick.core.storage.** { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
