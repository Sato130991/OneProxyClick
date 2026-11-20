plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.oneproxyclick.core.proxy"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(project(":core:ca-manager"))
    api(project(":core:interceptor"))
    // Only the Netty modules actually needed (netty-all drags native transports and
    // codecs that cannot load on Android and duplicate META-INF resources).
    implementation(libs.netty.handler)
    implementation(libs.netty.codec.http)
}
