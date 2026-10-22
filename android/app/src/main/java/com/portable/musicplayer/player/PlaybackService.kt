package com.portable.musicplayer.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
    private var player: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tailTrack: AudioTrack? = null
    private var tailThread: Thread? = null
    private var tailResume = false
    private var tailEarly = false
    private var tailBuilding = false
    private var tailHandled = false
    private var tailGeneration = 0

    private val tailPoll = object : Runnable {
        override fun run() {
            maybeStartEarlyTail()
            mainHandler.postDelayed(this, 200L)
        }
    }

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
        this.player = player

        player.setPauseAtEndOfMediaItems(LiveEffects.settings.enabled)
        LiveEffects.settingsListener = { enabled ->
            mainHandler.post {
                player.setPauseAtEndOfMediaItems(enabled)
                if (!enabled) {
                    val wasTailing = tailTrack != null || tailBuilding
                    stopTail(resume = wasTailing)
                }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady &&
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
                ) {
                    startTail(continuePlayback = shouldContinueAfterTail(player))
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // The last item with repeat off goes straight to ENDED instead
                // of pausing at its end, so cover that case too.
                if (playbackState == Player.STATE_ENDED) {
                    startTail(continuePlayback = false)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val wasTailing = tailTrack != null || tailBuilding
                tailHandled = false
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    stopTail(resume = wasTailing)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    val wasTailing = tailTrack != null || tailBuilding
                    tailHandled = false
                    stopTail(resume = wasTailing)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) stopTail(resume = false)
            }
        })
        mainHandler.postDelayed(tailPoll, 200L)

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
        mainHandler.removeCallbacks(tailPoll)
        LiveEffects.settingsListener = null
        stopTail(resume = false)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    private fun shouldContinueAfterTail(p: ExoPlayer): Boolean =
        p.repeatMode == Player.REPEAT_MODE_ALL ||
            p.repeatMode == Player.REPEAT_MODE_ONE ||
            p.hasNextMediaItem()

    /** Start the post-roll at the detected fade-out, before trailing silence. */
    private fun maybeStartEarlyTail() {
        val p = player ?: return
        if (!LiveEffects.settings.enabled || LiveEffects.current() == null) return
        if (!p.playWhenReady || p.playbackState != Player.STATE_READY) return
        if (p.mediaItemCount == 0) return
        if (tailHandled || tailTrack != null || tailBuilding) return
        val duration = p.duration
        if (duration <= 0L) return
        val scheduledDuration = LiveEffects.durationMs
        if (scheduledDuration <= 0L ||
            duration < scheduledDuration - 500L ||
            duration > scheduledDuration + 500L
        ) return
        val outroAt = LiveEffects.outroAtMs
        if (outroAt <= 0L || outroAt >= duration) return
        // Endings within half a second keep the natural end transition.
        if (duration - outroAt <= 500L) return
        val position = p.currentPosition
        if (position < outroAt || position >= duration - 150L) return
        startTail(shouldContinueAfterTail(p), early = true)
    }

    private fun startTail(continuePlayback: Boolean, early: Boolean = false) {
        if (tailHandled || tailTrack != null || tailBuilding) return
        tailHandled = true
        val p = player ?: return
        if (!LiveEffects.settings.enabled || LiveEffects.current() == null) {
            if (continuePlayback) p.play()
            return
        }
        val settings = LiveEffects.settings
        tailResume = continuePlayback
        tailEarly = early
        tailBuilding = true
        val generation = ++tailGeneration
        p.pause()
        tailThread = Thread({
            val buffer = try {
                LiveEffects.buildOutro(settings)
            } catch (_: Throwable) {
                null
            }
            mainHandler.post {
                if (generation != tailGeneration) return@post
                tailBuilding = false
                tailThread = null
                if (buffer == null) {
                    if (tailResume) p.play()
                } else {
                    playTail(p, buffer)
                }
            }
        }, "live-outro").apply { isDaemon = true }
        tailThread?.start()
    }

    private fun playTail(p: ExoPlayer, buffer: OutroBuffer) {
        val frames = buffer.data.size / 2
        if (frames <= 0) {
            if (tailResume) p.play()
            return
        }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(buffer.sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    PlatformAudioAttributes.Builder()
                        .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                        .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(buffer.data.size * 4)
                .build()
        } catch (_: Throwable) {
            if (tailResume) p.play()
            return
        }
        try {
            track.write(buffer.data, 0, buffer.data.size, AudioTrack.WRITE_BLOCKING)
            track.setVolume(p.volume)
            track.setNotificationMarkerPosition(frames)
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack?) = finishTail()

                    override fun onPeriodicNotification(t: AudioTrack?) {}
                },
                mainHandler,
            )
            tailTrack = track
            track.play()
            val generation = tailGeneration
            val timeoutMs = frames.toLong() * 1000L / buffer.sampleRate + 300L
            mainHandler.postDelayed({
                if (generation == tailGeneration && tailTrack === track) finishTail()
            }, timeoutMs)
        } catch (_: Throwable) {
            try {
                track.release()
            } catch (_: Throwable) {
                // ignore
            }
            if (tailResume) p.play()
        }
    }

    private fun finishTail() {
        val resume = tailResume
        val early = tailEarly
        stopTail(resume = false)
        val p = player ?: return
        if (!resume) return
        if (early) {
            advanceAfterEarlyTail(p)
        } else {
            p.play()
        }
    }

    /** The song's remaining silence is skipped after an early post-roll. */
    private fun advanceAfterEarlyTail(p: ExoPlayer) {
        when {
            p.repeatMode == Player.REPEAT_MODE_ONE -> {
                p.seekToDefaultPosition(p.currentMediaItemIndex)
                p.play()
            }
            p.hasNextMediaItem() -> {
                p.seekToNextMediaItem()
                p.play()
            }
            p.repeatMode == Player.REPEAT_MODE_ALL -> {
                p.seekToDefaultPosition(0)
                p.play()
            }
            else -> {
                // No next track: finish quietly at the paused fade-out point.
                p.pause()
            }
        }
    }

    private fun stopTail(resume: Boolean) {
        tailGeneration++
        tailBuilding = false
        tailEarly = false
        val thread = tailThread
        tailThread = null
        thread?.interrupt()
        val track = tailTrack
        tailTrack = null
        if (track != null) {
            try {
                track.pause()
                track.stop()
                track.release()
            } catch (_: Throwable) {
                // ignore
            }
        }
        if (resume) player?.play()
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
