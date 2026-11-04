import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "com.jusplayer.platform.DesktopMainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "jusplayer"
            packageVersion = "0.1.0"
            description = "JusPlayer - privacy focused music player"
            vendor = "JusPlayer"
            copyright = "© 2026 JusPlayer"
            linux {
                menuGroup = "AudioVideo"
                shortcut = true
                appCategory = "AudioVideo"
                debMaintainer = "team@jusplayer.local"
            }
        }
    }
}
