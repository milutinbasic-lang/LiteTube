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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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
import app.litetube.player.PlayerController
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

/**
 * Video screen. The player itself lives in [PlayerController] so audio keeps going in the
 * background. Leaving with Home / screen off keeps playing; leaving with Back stops playback.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : BaseActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_AUDIO_ONLY = "audio_only"
        const val EXTRA_RESUME = "resume"

        fun open(context: Context, url: String, audioOnly: Boolean = false) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_AUDIO_ONLY, audioOnly)
                    // One player screen at a time, since they all share the one player.
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
    }

    private lateinit var b: ActivityPlayerBinding
    private lateinit var header: HeaderVideoBinding
    private val player get() = PlayerController.player
    private val related = ItemAdapter(
        onVideoClick = { load(it.url, pushHistory = true, audioOnly = false) },
        onVideoLongClick = { view, item -> showVideoMenu(this, view, item) },
    )

    // Videos opened from "Up next" reuse this screen, with their own back stack.
    private val history = ArrayDeque<String>()
    private var currentUrl: String? = null
    private var loadJob: Job? = null
    private var descriptionExpanded = false

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            b.playerView.keepScreenOn = isPlaying
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        header = HeaderVideoBinding.inflate(layoutInflater, b.list, false)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = ConcatAdapter(SingleViewAdapter(header.root), related)

        b.playerView.player = player
        b.playerView.setShowNextButton(false)
        b.playerView.setShowPreviousButton(false)
        b.playerView.setFullscreenButtonClickListener { toggleFullscreen() }
        player.addListener(playerListener)

        PlayerController.onChanged = { updatePlaybackUi() }
        PlayerController.onError = { toast(getString(R.string.error_playback, it.errorCodeName)) }
        PlayerController.onSkipped = { toast(getString(R.string.sponsor_skipped)) }

        header.quality.setOnClickListener { chooseQuality() }
        header.audioOnly.setOnClickListener { PlayerController.toggleAudioOnly() }
        header.share.setOnClickListener { currentUrl?.let { share(this, PlayerController.info?.name, it) } }
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
                    history.isNotEmpty() -> load(history.removeLast(), pushHistory = false, audioOnly = false)
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
        // Opened from the notification: show whatever is playing.
        val url = intent.getStringExtra(EXTRA_URL)
            ?: PlayerController.currentUrl.takeIf { intent.getBooleanExtra(EXTRA_RESUME, false) }
            ?: return finish()
        load(url, pushHistory = true, audioOnly = intent.getBooleanExtra(EXTRA_AUDIO_ONLY, false))
    }

    private fun load(url: String, pushHistory: Boolean, audioOnly: Boolean) {
        if (pushHistory) currentUrl?.takeIf { it != url }?.let { history.addLast(it) }
        while (history.size > 20) history.removeFirst()
        currentUrl = url
        descriptionExpanded = false
        loadJob?.cancel()

        // Already playing (returning from background, notification tap, or theme change):
        // just show it, don't restart.
        val playing = PlayerController.info
        if (url == PlayerController.currentUrl && playing != null) {
            showDetails(playing)
            if (audioOnly && !PlayerController.audioOnly) PlayerController.toggleAudioOnly()
            return
        }

        PlayerController.stop()
        related.submit(emptyList())
        header.details.isVisible = false
        header.state.loading()
        b.list.scrollToPosition(0)

        loadJob = lifecycleScope.launch {
            try {
                val info = YouTube.stream(url)
                showDetails(info)
                if (!PlayerController.start(url, info, audioOnly || Prefs.audioOnly)) {
                    toast(getString(R.string.error_no_streams))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                header.state.show(errorText(e), getString(R.string.retry)) {
                    load(url, pushHistory = false, audioOnly = audioOnly)
                }
            }
        }
    }

    private fun showDetails(i: StreamInfo) {
        header.state.hide()
        header.details.isVisible = true
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
        updateSubscribeButton(i)

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
        updatePlaybackUi()
    }

    private fun updatePlaybackUi() {
        val playback = PlayerController.current
        header.quality.text = playback?.label ?: getString(R.string.quality)
        val audio = playback?.isAudioOnly == true
        header.audioOnly.setText(if (audio) R.string.show_video else R.string.audio_only)
        val info = PlayerController.info
        if (audio && info != null) loadArtwork(info) else b.playerView.setDefaultArtwork(null)
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
        val i = PlayerController.info ?: return
        val heights = StreamPicker.availableHeights(i, Prefs.lowPower)
        if (heights.isEmpty()) {
            toast(getString(R.string.quality_fixed))
            return
        }
        val labels = heights.map { "${it}p" } + getString(R.string.audio_only)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.quality)
            .setItems(labels.toTypedArray()) { _, which ->
                PlayerController.changeQuality(heights.getOrNull(which))
            }
            .show()
    }

    private fun toggleSubscription() {
        val i = PlayerController.info ?: return
        val url = i.uploaderUrl ?: return
        if (Subscriptions.isSubscribed(url)) {
            Subscriptions.remove(url)
        } else {
            Subscriptions.add(Subscription(url, i.uploaderName.orEmpty(), pickImage(i.uploaderAvatars, 88)))
        }
        updateSubscribeButton(i)
    }

    private fun updateSubscribeButton(i: StreamInfo) {
        val url = i.uploaderUrl
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

    // --- Background playback ---

    override fun onStart() {
        super.onStart()
        PlayerController.setInBackground(false)
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) return
        if (Prefs.backgroundPlay) {
            PlayerController.setInBackground(true)
        } else {
            player.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player.removeListener(playerListener)
        b.playerView.player = null
        PlayerController.onChanged = null
        PlayerController.onError = null
        PlayerController.onSkipped = null
        // Back button closes the player for good; recreation (e.g. theme change) keeps playing.
        if (isFinishing) PlayerController.stop()
    }
}
