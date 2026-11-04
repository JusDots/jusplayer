package com.jusplayer.platform

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.jusplayer.App

/**
 * Entry point shared by the Windows and Linux desktop shells.
 * The Compose UI itself lives in :shared, so both shells stay in sync with the web build.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "JusPlayer",
        state = WindowState(size = DpSize(1280.dp, 840.dp)),
    ) {
        LaunchedEffect(Unit) { }
        App()
    }
}
