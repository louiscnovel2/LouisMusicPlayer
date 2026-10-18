package com.portable.musicplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.portable.musicplayer.ui.MusicApp
import com.portable.musicplayer.ui.Palette

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw the player UI edge-to-edge so the dark screen fills the display.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            Box(Modifier.fillMaxSize().background(Palette.ScreenBgBottom)) {
                MusicApp()
            }
        }
    }
}
