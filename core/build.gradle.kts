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
    // Skip Javadoc generation. Reason: Dokka (bundled with AGP 9.0.1 via
    //   :javaDocReleaseGeneration) uses an older ASM that doesn't support
    // Java 17 sealed-class PermittedSubclasses, so generation crashes on
    // sealed classes (e.g., PendingAction):
    //   UnsupportedOperationException: PermittedSubclasses requires ASM9
    // Re-enable (publishJavadocJar = true) when Dokka catches up to ASM 9
    // or after migrating away from Dokka here. Maven Central does require
    // a Javadoc JAR for release uploads, so the eventual fix is a release
    // prerequisite — tracked alongside the Phase 5 release work.
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
    if (project.hasProperty("signing.keyId") ||
        System.getenv("ORG_GRADLE_PROJECT_signing.keyId") != null) {
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

// Release-time hard gate: Maven Central will reject uploads that lack a
// Javadoc JAR. Until Dokka catches up to ASM 9 / Java 17 sealed classes
// (see the mavenPublishing.configure() block above), every Central upload
// from this module will fail at the server side. Fail FAST instead —
// refuse the publish task locally unless an operator explicitly bypasses
// with -Pzerosettle.bypassJavadocCheck=true (which exists so this gate
// doesn't block testing the publish pipeline itself). Real release fix
// is tracked alongside Phase 5 release work in the Flutter parity plan.
afterEvaluate {
    tasks.matching { it.name.startsWith("publish") && it.name.contains("Central") }
        .configureEach {
            doFirst {
                require(project.hasProperty("zerosettle.bypassJavadocCheck")) {
                    "Refusing to publish to Maven Central without a Javadoc JAR. " +
                        "Dokka skip is active (see mavenPublishing.configure() in " +
                        "core/build.gradle.kts). Central will reject the upload. " +
                        "Either re-enable Javadoc generation (publishJavadocJar = true) " +
                        "or pass -Pzerosettle.bypassJavadocCheck=true to override this gate."
                }
            }
        }
}
