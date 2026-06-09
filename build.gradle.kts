plugins {
    // Applied false here so each plugin's classes are loaded once into the root
    // classloader and the actual `apply` happens in the subproject that needs it.
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.roborazzi) apply false

    // Detekt and the binary-compatibility-validator are project-wide tools that
    // are most ergonomic applied at the root.
    alias(libs.plugins.detekt)
    alias(libs.plugins.binaryCompatibilityValidator)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    source.setFrom(
        "kwave/src/commonMain/kotlin",
        "kwave/src/androidMain/kotlin",
        "kwave/src/iosMain/kotlin",
        "kwave/src/jvmMain/kotlin",
        "sample/src/jvmMain/kotlin",
    )
}

// Binary-compatibility-validator: track only the published library; the sample
// app is not part of the public API surface.
apiValidation {
    ignoredProjects.add("sample")
}
