import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/*
 * Release signing is read from <root>/keystore.properties (git-ignored). Without that file the
 * release variants are still built, but unsigned; debug builds are never affected.
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use(::load)
}
val hasReleaseKeystore = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !keystoreProps.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.oneproxyclick.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.oneproxyclick.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    /*
     * Distribution flavors:
     *  - foss: GitHub Releases / F-Droid style build, every feature unlocked, no Google libraries.
     *  - play: Google Play build; advanced features unlock with a one-time "OneProxyClick Pro"
     *          in-app purchase (Google Play Billing).
     */
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            versionNameSuffix = "-foss"
            buildConfigField("String", "DISTRIBUTION", "\"foss\"")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"play\"")
            buildConfigField("String", "PRO_PRODUCT_ID", "\"oneproxyclick_pro_lifetime\"")
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = false // minSdk 29: v2/v3 only
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Applies to every flavor (fossRelease and playRelease).
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ca-manager"))
    implementation(project(":core:proxy-engine"))
    implementation(project(":core:vpn-tunnel"))
    implementation(project(":core:interceptor"))
    implementation(project(":core:storage"))
    implementation(project(":feature:overlay"))
    implementation(project(":feature:repeater"))
    implementation(project(":feature:decoder"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    // Google Play Billing only in the Play flavor; the FOSS APK contains no proprietary libraries.
    "playImplementation"(libs.billing.ktx)
}
