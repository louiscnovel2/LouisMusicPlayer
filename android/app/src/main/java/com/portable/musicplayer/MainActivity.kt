package com.portable.musicplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.portable.musicplayer.ui.MusicApp
import com.portable.musicplayer.ui.Palette

class MainActivity : ComponentActivity() {

    private val playerViewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw the player UI edge-to-edge so the dark screen fills the display.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            Box(Modifier.fillMaxSize().background(Palette.ScreenBgBottom)) {
                MusicApp(playerViewModel)
            }
        }
    }

    override fun onStop() {
        // Covers the "pause -> close app" flow even when the process is killed
        // before ViewModel.onCleared() runs.
        playerViewModel.persistSession()
        super.onStop()
    }
}
