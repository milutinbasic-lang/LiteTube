package app.litetube.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import app.litetube.R
import app.litetube.data.Prefs
import app.litetube.data.Subscription
import app.litetube.data.Subscriptions
import app.litetube.data.VideoItem
import app.litetube.data.YouTube
import app.litetube.data.pickImage
import app.litetube.data.toListItems
import app.litetube.databinding.ActivityPlayerBinding
import app.litetube.databinding.HeaderVideoBinding
import app.litetube.player.MediaSources
import app.litetube.player.Playback
import app.litetube.player.StreamPicker
import coil.imageLoader
import coil.load
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo

@OptIn(UnstableApi::class)
class PlayerActivity : BaseActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_AUDIO_ONLY = "audio_only"

        fun open(context: Context, url: String, audioOnly: Boolean = false) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_AUDIO_ONLY, audioOnly)
            )
        }
    }

    private lateinit var b: ActivityPlayerBinding
    private lateinit var header: HeaderVideoBinding
    private lateinit var player: ExoPlayer
    private val sources = MediaSources()
    private val related = ItemAdapter(
        onVideoClick = { load(it.url, pushHistory = true) },
        onVideoLongClick = { view, item -> showVideoMenu(this, view, item) },
    )

    // Videos opened from "Up next" reuse this screen (one player instance), with their own back stack.
    private val history = ArrayDeque<String>()
    private var currentUrl: String? = null
    private var info: StreamInfo? = null
    private var maxHeight = Prefs.maxQuality
    private var audioOnly = Prefs.audioOnly
    private var usingHlsFallback = false
    private var loadJob: Job? = null
    private var descriptionExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        header = HeaderVideoBinding.inflate(layoutInflater, b.list, false)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = ConcatAdapter(SingleViewAdapter(header.root), related)

        player = buildPlayer()
        b.playerView.player = player
        b.playerView.setShowNextButton(false)
        b.playerView.setShowPreviousButton(false)
        b.playerView.setFullscreenButtonClickListener { toggleFullscreen() }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                b.playerView.keepScreenOn = isPlaying
            }

            override fun onPlayerError(error: PlaybackException) {
                onPlaybackError(error)
            }
        })

        header.quality.setOnClickListener { chooseQuality() }
        header.audioOnly.setOnClickListener {
            audioOnly = !audioOnly
            usingHlsFallback = false
            restartPlayback()
        }
        header.share.setOnClickListener { currentUrl?.let { share(this, info?.name, it) } }
        header.subscribe.setOnClickListener { toggleSubscription() }
        header.description.movementMethod = LinkMovementMethod.getInstance()
        header.description.setOnClickListener {
            descriptionExpanded = !descriptionExpanded
            header.description.maxLines = if (descriptionExpanded) Int.MAX_VALUE else 4
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isLandscape() -> exitFullscreen()
                    history.isNotEmpty() -> load(history.removeLast(), pushHistory = false)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        applyOrientation(resources.configuration)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        if (intent.getBooleanExtra(EXTRA_AUDIO_ONLY, false)) audioOnly = true
        load(url, pushHistory = true)
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
        return ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    private fun load(url: String, pushHistory: Boolean) {
        if (pushHistory) currentUrl?.takeIf { it != url }?.let { history.addLast(it) }
        while (history.size > 20) history.removeFirst()

        currentUrl = url
        info = null
        usingHlsFallback = false
        descriptionExpanded = false
        loadJob?.cancel()
        player.stop()
        player.clearMediaItems()
        b.playerView.setDefaultArtwork(null)
        related.submit(emptyList())
        header.details.isVisible = false
        header.state.loading()
        b.list.scrollToPosition(0)

        loadJob = lifecycleScope.launch {
            try {
                val streamInfo = YouTube.stream(url)
                info = streamInfo
                bindDetails(streamInfo)
                header.state.hide()
                header.details.isVisible = true
                startPlayback(0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                header.state.show(errorText(e), getString(R.string.retry)) { load(url, pushHistory = false) }
            }
        }
    }

    private fun bindDetails(i: StreamInfo) {
        header.title.text = i.name
        header.meta.text = listOfNotNull(
            Format.views(this, i.viewCount),
            Format.age(runCatching { i.uploadDate?.instant?.toEpochMilli() }.getOrNull() ?: 0L, i.textualUploadDate),
            i.likeCount.takeIf { it >= 0 }?.let { getString(R.string.likes, Format.compact(it)) },
        ).joinToString(" · ")

        header.channelName.text = i.uploaderName
        val avatar = pickImage(i.uploaderAvatars, 80)
        header.avatar.isVisible = Prefs.showThumbnails && avatar != null
        if (header.avatar.isVisible) header.avatar.load(avatar) { transformations(CircleCropTransformation()) }
        header.channelRow.setOnClickListener {
            i.uploaderUrl?.let { ChannelActivity.open(this, it, i.uploaderName) }
        }
        updateSubscribeButton()

        val description = i.description
        val content = description?.content.orEmpty()
        header.description.text = if (description?.type == Description.HTML) {
            HtmlCompat.fromHtml(content, HtmlCompat.FROM_HTML_MODE_LEGACY)
        } else {
            content
        }
        header.description.maxLines = 4
        header.description.isVisible = content.isNotBlank()

        val items = if (Prefs.showRelated) i.relatedItems.toListItems().filterIsInstance<VideoItem>() else emptyList()
        related.submit(items)
        header.relatedLabel.isVisible = items.isNotEmpty()
    }

    private fun startPlayback(positionMs: Long) {
        val i = info ?: return
        val playback = if (usingHlsFallback) {
            i.hlsUrl?.takeIf { it.isNotEmpty() }?.let { Playback(null, null, it, "Auto") }
        } else {
            StreamPicker.pick(i, maxHeight, Prefs.lowPower, audioOnly)
        }
        if (playback == null) {
            toast(getString(R.string.error_no_streams))
            return
        }

        header.quality.text = playback.label
        header.audioOnly.setText(if (playback.isAudioOnly) R.string.show_video else R.string.audio_only)
        if (playback.isAudioOnly) loadArtwork(i) else b.playerView.setDefaultArtwork(null)

        val source = sources.build(playback)
        if (positionMs > 0 && !StreamPicker.isLive(i)) {
            player.setMediaSource(source, positionMs)
        } else {
            player.setMediaSource(source, true)
        }
        player.prepare()
        player.play()
    }

    private fun restartPlayback() = startPlayback(player.currentPosition.coerceAtLeast(0))

    private fun onPlaybackError(error: PlaybackException) {
        val i = info
        // Progressive URLs occasionally get rejected; YouTube's HLS manifest is a good second try.
        if (!usingHlsFallback && i != null && !i.hlsUrl.isNullOrEmpty() && !StreamPicker.isLive(i)) {
            usingHlsFallback = true
            restartPlayback()
        } else {
            toast(getString(R.string.error_playback, error.errorCodeName))
        }
    }

    private fun loadArtwork(i: StreamInfo) {
        val url = pickImage(i.thumbnails, 480) ?: return
        val request = ImageRequest.Builder(this)
            .data(url)
            .target { drawable -> b.playerView.setDefaultArtwork(drawable) }
            .build()
        imageLoader.enqueue(request)
    }

    private fun chooseQuality() {
        val i = info ?: return
        val heights = StreamPicker.availableHeights(i, Prefs.lowPower)
        if (heights.isEmpty()) {
            toast(getString(R.string.quality_fixed))
            return
        }
        val labels = heights.map { "${it}p" } + getString(R.string.audio_only)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.quality)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == heights.size) {
                    audioOnly = true
                } else {
                    audioOnly = false
                    maxHeight = heights[which]
                }
                usingHlsFallback = false
                restartPlayback()
            }
            .show()
    }

    private fun toggleSubscription() {
        val i = info ?: return
        val url = i.uploaderUrl ?: return
        if (Subscriptions.isSubscribed(url)) {
            Subscriptions.remove(url)
        } else {
            Subscriptions.add(Subscription(url, i.uploaderName.orEmpty(), pickImage(i.uploaderAvatars, 88)))
        }
        updateSubscribeButton()
    }

    private fun updateSubscribeButton() {
        val url = info?.uploaderUrl
        header.subscribe.isVisible = url != null
        header.subscribe.setText(if (Subscriptions.isSubscribed(url)) R.string.unsubscribe else R.string.subscribe)
    }

    // --- Fullscreen: landscape = fullscreen, portrait = 16:9 player above the details ---

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
    }

    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun toggleFullscreen() {
        requestedOrientation = if (isLandscape()) {
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun exitFullscreen() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    }

    private fun applyOrientation(config: Configuration) {
        val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        val params = b.playerBox.layoutParams
        params.height = if (landscape) {
            ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            (config.screenWidthDp * resources.displayMetrics.density * 9f / 16f).toInt()
        }
        b.playerBox.layoutParams = params
        b.list.isVisible = !landscape
        b.playerView.setFullscreenButtonState(landscape)

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (landscape) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }
}
