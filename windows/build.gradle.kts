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
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "JusPlayer"
            packageVersion = "0.1.0"
            description = "JusPlayer - privacy focused music player"
            vendor = "JusPlayer"
            copyright = "© 2026 JusPlayer"
            windows {
                menuGroup = "JusPlayer"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "5d1f3b0e-7f2a-4c8b-9a11-2b6c9d4e7f10"
            }
        }
    }
}
