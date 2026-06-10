import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":kwave"))
                implementation(compose.desktop.currentOs)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                // material3 only for the sample's slider / control UI.
                implementation(libs.compose.material3)
                // WaveConfig.layers is an ImmutableList; the sample re-passes it into the
                // WaveConfig(...) constructor, so it needs this type on its own classpath.
                implementation(libs.kotlinx.collections.immutable)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "red.rankorr.kwave.sample.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "KWaveSample"
            packageVersion = "1.0.0"
        }
    }
}

// Dev-only task: render the preview GIFs headlessly (no external tools).
tasks.register<JavaExec>("generateGif") {
    group = "kwave"
    description = "Renders the preview GIFs to docs/screenshots/wave-*.gif."
    workingDir = rootProject.projectDir
    val mainCompilation = kotlin.jvm().compilations.getByName("main")
    dependsOn(mainCompilation.compileTaskProvider)
    classpath = files(mainCompilation.output.allOutputs, mainCompilation.runtimeDependencyFiles)
    mainClass.set("red.rankorr.kwave.sample.GifGeneratorKt")
}
