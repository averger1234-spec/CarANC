package com.example.caranc.shared.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log

/**
 * Makes CarANC a real "now playing" music source for Android Auto.
 * Raw AudioTrack USAGE_MEDIA without a session is often mixed as overlay/speech (HP, 沙).
 */
class AncMediaSession(context: Context) {

    val session: MediaSessionCompat = MediaSessionCompat(context, "CarANC").apply {
        setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        val pi = PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        setSessionActivity(pi)
        setCallback(object : MediaSessionCompat.Callback() {})
        setPlaybackToLocal(android.media.AudioManager.STREAM_MUSIC)
        isActive = true
        setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "CarANC")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Active noise cancellation")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Cabin")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
                .build()
        )
        setPlaying(true)
    }

    fun setPlaying(playing: Boolean) {
        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        Log.i(TAG, "mediaSession playing=$playing")
    }

    fun release() {
        try {
            session.isActive = false
            session.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "AncMediaSession"
    }
}
