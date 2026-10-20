package dev.dreamz.btreceiver

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.service.media.MediaBrowserService
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Talks to the Bluetooth stack's AVRCP-controller media session. In speaker mode its transport
 * controls are forwarded over AVRCP to the device that's sending audio, and its metadata is
 * what that device reports as now playing.
 */
class SourceMedia(private val context: Context) {

    data class NowPlaying(
        val connected: Boolean = false,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val playing: Boolean = false,
        /** e.g. "Bluetooth audio disconnected" when no source is connected. */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null
    private var started = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = reconnectLater()
    }

    private val connectionCallback = object : MediaBrowser.ConnectionCallback() {
        override fun onConnected() {
            val b = browser ?: return
            controller = MediaController(context, b.sessionToken).also { it.registerCallback(controllerCallback, main) }
            publish()
        }
        // The Bluetooth process is killed on every mode switch, so keep retrying while started.
        override fun onConnectionSuspended() = reconnectLater()
        override fun onConnectionFailed() = reconnectLater()
    }

    fun start() {
        started = true
        connect()
    }

    fun stop() {
        started = false
        main.removeCallbacksAndMessages(null)
        disconnect()
    }

    /** Call after a mode switch so the session is picked up from the new Bluetooth process. */
    fun reconnect() {
        disconnect()
        if (started) connect()
    }

    val hasSession: Boolean get() = controller != null

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }
    fun stopPlayback() { controller?.transportControls?.stop() }

    private fun connect() {
        if (browser != null) return
        browser = MediaBrowser(context, findBrowserService(), connectionCallback, null)
            .also {
                try {
                    it.connect()
                } catch (_: Exception) {
                    reconnectLater()
                }
            }
    }

    private fun disconnect() {
        controller?.unregisterCallback(controllerCallback)
        controller = null
        browser?.let { runCatching { it.disconnect() } }
        browser = null
        _state.value = NowPlaying()
    }

    private fun reconnectLater() {
        disconnect()
        if (!started) return
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ if (started) connect() }, 2000)
    }

    private fun publish() {
        val c = controller
        val md = c?.metadata
        _state.value = NowPlaying(
            connected = c != null,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() },
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() },
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() },
            playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING,
            error = c?.playbackState?.takeIf { it.state == PlaybackState.STATE_ERROR }?.errorMessage?.toString(),
        )
    }

    /** The Bluetooth package is com.android.bluetooth on most phones, com.google.android.bluetooth on some. */
    private fun findBrowserService(): ComponentName =
        context.packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }
            .firstOrNull { it.name.endsWith(".avrcpcontroller.BluetoothMediaBrowserService") }
            ?.let { ComponentName(it.packageName, it.name) }
            ?: ComponentName("com.android.bluetooth", "com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService")
}
