import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional release-signing credentials. Generate a keystore with
// `keytool -genkey -v -keystore zerosettle-sample.jks -alias zerosettle
//   -keyalg RSA -keysize 2048 -validity 10000`, then create
// `sample/keystore.properties` (gitignored) with:
//     storeFile=zerosettle-sample.jks
//     storePassword=...
//     keyAlias=zerosettle
//     keyPassword=...
// Without the file, release builds fall back to debug signing (unusable
// for Play Console upload but lets `:sample:assembleRelease` finish).
val keystorePropsFile = rootProject.file("sample/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.zerosettle.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zerosettle.sample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // Minify on, so the :core / :ui consumer ProGuard rules get exercised.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Use the release keystore if keystore.properties is present;
            // otherwise debug-sign so the build doesn't fail outright.
            signingConfig = if (keystoreProps.isNotEmpty())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin { jvmToolchain(17) }

    buildFeatures { compose = true }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    // Google Play Billing — declared explicitly so manifest merging picks
    // up the com.android.vending.BILLING permission Play Console requires
    // before it'll enable IAP product configuration for this app. `:core`
    // also depends on this via api(libs.billing.ktx); the explicit
    // declaration here documents intent for sample-app readers.
    implementation(libs.billing.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)
}
