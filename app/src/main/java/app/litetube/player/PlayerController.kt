package app.litetube.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import app.litetube.data.Prefs
import app.litetube.data.pickImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Owns the single app-wide player, so playback can continue when the player screen is closed,
 * the user switches apps, or the screen turns off. [PlaybackService] exposes it to the system
 * (notification, lock screen, headphone buttons, Bluetooth).
 */
@OptIn(UnstableApi::class)
object PlayerController {

    private lateinit var app: Context
    private val scope = MainScope()
    private val sources by lazy { MediaSources() }

    val player: ExoPlayer by lazy { buildPlayer() }

    var currentUrl: String? = null
        private set
    var info: StreamInfo? = null
        private set
    var current: Playback? = null
        private set
    var audioOnly = false
        private set
    private var maxHeight = 360
    private var usingHlsFallback = false

    private var segments: List<SponsorBlock.Segment> = emptyList()
    private var segmentJob: Job? = null
    private val skipMessages = ArrayList<PlayerMessage>()

    /** UI callbacks, set by the player screen while it exists. */
    var onChanged: (() -> Unit)? = null
    var onError: ((PlaybackException) -> Unit)? = null
    var onSkipped: ((SponsorBlock.Segment) -> Unit)? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun buildPlayer(): ExoPlayer {
        // Smaller buffers than the defaults (50s) keep RAM use and wasted data low.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 1_500, 3_000)
            .build()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val exo = ExoPlayer.Builder(app)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            // Keeps CPU and Wi-Fi awake while playing with the screen off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val i = info
                // Progressive URLs occasionally get rejected; YouTube's HLS manifest is a good second try.
                if (!usingHlsFallback && i != null && !i.hlsUrl.isNullOrEmpty() && !StreamPicker.isLive(i)) {
                    usingHlsFallback = true
                    play(exo.currentPosition)
                } else {
                    onError?.invoke(error)
                }
            }
        })
        return exo
    }

    /** Starts a new video. */
    fun start(url: String, streamInfo: StreamInfo, audioOnly: Boolean): Boolean {
        reset()
        currentUrl = url
        info = streamInfo
        this.audioOnly = audioOnly
        maxHeight = Prefs.maxQuality
        usingHlsFallback = false
        loadSegments(streamInfo)
        if (!play(0)) return false
        // Started while the app is visible; Media3 then promotes it to a foreground service while playing.
        app.startService(Intent(app, PlaybackService::class.java))
        return true
    }

    /** Pass a height to switch video quality, or null for audio only. */
    fun changeQuality(height: Int?) {
        if (height == null) {
            audioOnly = true
        } else {
            audioOnly = false
            maxHeight = height
        }
        usingHlsFallback = false
        play(player.currentPosition.coerceAtLeast(0))
    }

    fun toggleAudioOnly() {
        audioOnly = !audioOnly
        usingHlsFallback = false
        play(player.currentPosition.coerceAtLeast(0))
    }

    /** In the background, stop decoding video (big CPU/battery saving); audio keeps playing. */
    fun setInBackground(background: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, background)
            .build()
    }

    /** Stops playback and removes the notification. */
    fun stop() {
        reset()
        app.stopService(Intent(app, PlaybackService::class.java))
    }

    private fun reset() {
        segmentJob?.cancel()
        segments = emptyList()
        clearSkips()
        player.stop()
        player.clearMediaItems()
        currentUrl = null
        info = null
        current = null
    }

    private fun play(positionMs: Long): Boolean {
        val i = info ?: return false
        val playback = if (usingHlsFallback) {
            i.hlsUrl?.takeIf { it.isNotEmpty() }?.let { Playback(null, null, it, "Auto") }
        } else {
            StreamPicker.pick(i, maxHeight, Prefs.lowPower, audioOnly)
        } ?: return false
        current = playback

        val metadata = MediaMetadata.Builder()
            .setTitle(i.name)
            .setArtist(i.uploaderName)
            .setArtworkUri(pickImage(i.thumbnails, 480)?.let(Uri::parse))
            .build()
        val source = sources.build(playback, metadata)
        if (positionMs > 0 && !StreamPicker.isLive(i)) {
            player.setMediaSource(source, positionMs)
        } else {
            player.setMediaSource(source, true)
        }
        player.prepare()
        player.play()
        scheduleSkips()
        onChanged?.invoke()
        return true
    }

    private fun loadSegments(i: StreamInfo) {
        segmentJob?.cancel()
        if (!Prefs.sponsorBlock || StreamPicker.isLive(i)) return
        val url = currentUrl
        segmentJob = scope.launch {
            val found = runCatching { SponsorBlock.segments(i.id, Prefs.sponsorBlockCategories) }
                .getOrDefault(emptyList())
            if (currentUrl == url) {
                segments = found
                scheduleSkips()
            }
        }
    }

    /**
     * Uses player messages that fire exactly when playback reaches each segment,
     * instead of polling the position (cheaper on slow CPUs and works in the background).
     */
    private fun scheduleSkips() {
        clearSkips()
        for (segment in segments) {
            skipMessages += player.createMessage { _, _ -> skip(segment) }
                .setLooper(Looper.getMainLooper())
                .setPosition(segment.startMs)
                .setDeleteAfterDelivery(false)
                .send()
        }
    }

    private fun skip(segment: SponsorBlock.Segment) {
        val position = player.currentPosition
        if (position >= segment.startMs - 1_000 && position < segment.endMs) {
            player.seekTo(segment.endMs)
            onSkipped?.invoke(segment)
        }
    }

    private fun clearSkips() {
        skipMessages.forEach { it.cancel() }
        skipMessages.clear()
    }
}
