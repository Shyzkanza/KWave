import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.mavenPublish)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        publishLibraryVariants("release")
    }

    // Do NOT force isStatic. Consumers integrate via SPM/CocoaPods/regular
    // framework as they prefer. Default (dynamic) framework is fine for a library.
    iosArm64()
    iosSimulatorArm64()

    // JVM powers the Desktop sample and the fast JVM/common unit tests.
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.kotlin.testJunit)
                implementation(libs.compose.uiTestJunit4)
                implementation(libs.androidx.compose.uiTestManifest)
                implementation(libs.robolectric)
                implementation(libs.roborazzi)
                implementation(libs.roborazzi.compose)
                implementation(libs.roborazzi.junit.rule)
            }
        }
    }
}

android {
    namespace = "red.rankorr.kwave"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // NO productFlavors. Those are app-only concerns.

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Roborazzi records/verifies golden images; allow the host JVM access.
        unitTests.all {
            it.systemProperties["robolectric.graphicsMode"] = "NATIVE"
        }
    }
}

// ---------------------------------------------------------------------------
// Publishing to Maven Central via the Sonatype Central Portal.
// GROUP / VERSION_NAME / POM_* are read from gradle.properties by the plugin.
// Coordinates + POM are also set here explicitly so the build is self-describing.
// ---------------------------------------------------------------------------
mavenPublishing {
    // groupId (GROUP), version (VERSION_NAME) and the base POM fields come from
    // gradle.properties. artifactId defaults to the module name ("kwave").
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
    signAllPublications()

    pom {
        name.set("KWave")
        description.set(
            "Animated, customizable layered wave hero backgrounds for " +
                "Compose Multiplatform (Android, iOS, JVM).",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/Shyzkanza/KWave")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("shyzkanza")
                name.set("Jessy Bonnotte")
                url.set("https://github.com/Shyzkanza")
            }
        }
        scm {
            url.set("https://github.com/Shyzkanza/KWave")
            connection.set("scm:git:git://github.com/Shyzkanza/KWave.git")
            developerConnection.set("scm:git:ssh://git@github.com/Shyzkanza/KWave.git")
        }
    }
}
