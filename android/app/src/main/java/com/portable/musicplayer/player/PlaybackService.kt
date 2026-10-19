package com.portable.musicplayer.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.portable.musicplayer.MainActivity

/**
 * Hosts the ExoPlayer inside a [MediaSessionService] so playback continues in
 * the background and the system media notification (play / pause / next /
 * previous, plus cover art) is shown automatically by Media3.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        LiveEffects.init(this)

        val player = ExoPlayer.Builder(this, EqualizerRenderersFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.repeatMode = Player.REPEAT_MODE_ALL

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setBitmapLoader(LocalArtworkBitmapLoader(this))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** Keep playing in the background; stop the service when nothing is active. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}

/**
 * Replaces the default audio sink with one that runs the 10-band equalizer
 * (and per-track gain) plus the Live Effect inside the playback pipeline.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class EqualizerRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
        enableOffload: Boolean,
    ): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setOffloadMode(
                if (enableOffload) {
                    DefaultAudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED
                } else {
                    DefaultAudioSink.OFFLOAD_MODE_DISABLED
                }
            )
            .setAudioProcessors(
                arrayOf<AudioProcessor>(EqualizerAudioProcessor(), LiveAudioProcessor())
            )
            .build()
}
