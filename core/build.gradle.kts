plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.zerosettle.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "ZEROSETTLE_SDK_VERSION", "\"${project.findProperty("VERSION_NAME")}\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin { jvmToolchain(17) }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    api(libs.billing.ktx)
    implementation(libs.stripe)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.startup.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.truth)
    testImplementation(libs.androidx.lifecycle.runtime.testing)
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    // Maven Central requires a Javadoc JAR. AGP's Dokka-based javadoc task
    // crashes deserializing Java 17 sealed classes (ASM < 9), so generation
    // is disabled here and an empty Javadoc JAR is published instead — see
    // the `emptyJavadocJar` block below.
    configure(
        com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = false,
        )
    )
    // Sign publications only when GPG signing properties are configured
    // (typically in CI for releases, or in ~/.gradle/gradle.properties for
    // local release work). Local dev `publishToMavenLocal` runs without
    // signing — the cross-repo Flutter plugin workflow consumes those
    // unsigned local artifacts. Maven Central enforces signed uploads
    // at the server side, so unsigned dev builds can't accidentally ship.
    //
    // Accepts two signing flavours:
    //  - keyring file: `signing.keyId` / `signing.password` / `signing.secretKeyRingFile`
    //    (the local-dev path, matches `~/.gradle/gradle.properties`).
    //  - in-memory key: `signingInMemoryKey` / `signingInMemoryKeyId` /
    //    `signingInMemoryKeyPassword` (the CI path; vanniktech's plugin reads these
    //    directly when present, so we just need to flip on signAllPublications()).
    if (project.hasProperty("signing.keyId") ||
        System.getenv("ORG_GRADLE_PROJECT_signing.keyId") != null ||
        project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
    coordinates(
        project.findProperty("GROUP") as String,
        "zerosettle-android",
        project.findProperty("VERSION_NAME") as String,
    )

    pom {
        name.set("ZeroSettle Android SDK")
        description.set("Headless core: Merchant of Record infrastructure for Android — web checkout, Play Billing sync, entitlement state, offers.")
        url.set("https://github.com/ArkTrek/ZeroSettle-Android")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("zerosettle")
                name.set("ZeroSettle")
                url.set("https://zerosettle.io")
            }
        }
        scm {
            url.set("https://github.com/ArkTrek/ZeroSettle-Android")
            connection.set("scm:git:https://github.com/ArkTrek/ZeroSettle-Android.git")
            developerConnection.set("scm:git:ssh://git@github.com/ArkTrek/ZeroSettle-Android.git")
        }
    }
}

// Maven Central requires a `-javadoc.jar`. AGP's Dokka-based javadoc task
// crashes deserializing Java 17 sealed classes (ASM < 9 / PermittedSubclasses),
// so `publishJavadocJar` is disabled above; publish an EMPTY Javadoc JAR
// instead — Central validates the artifact's presence, not its contents.
val emptyJavadocJar = tasks.register<org.gradle.api.tasks.bundling.Jar>("emptyJavadocJar") {
    archiveClassifier.set("javadoc")
}
extensions.configure<org.gradle.api.publish.PublishingExtension> {
    publications.withType(org.gradle.api.publish.maven.MavenPublication::class.java)
        .configureEach { artifact(emptyJavadocJar) }
}
