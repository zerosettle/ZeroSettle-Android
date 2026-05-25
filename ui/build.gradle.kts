plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.zerosettle.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin { jvmToolchain(17) }

    buildFeatures { compose = true }

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
    api(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    // Maven Central requires a Javadoc JAR. AGP's Dokka-based javadoc task
    // crashes on Java 17 sealed classes (ASM < 9), so generation is disabled
    // and an empty Javadoc JAR is published instead — see `emptyJavadocJar`.
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
        "zerosettle-android-ui",
        project.findProperty("VERSION_NAME") as String,
    )
    pom {
        name.set("ZeroSettle Android UI")
        description.set("Optional Compose components for ZeroSettle: offer tip, pending-action banner, checkout sheet, cancel flow, upgrade offer.")
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
            developer { id.set("zerosettle"); name.set("ZeroSettle"); url.set("https://zerosettle.io") }
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
