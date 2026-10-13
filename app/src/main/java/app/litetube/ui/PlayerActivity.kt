package app.litetube.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
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
import app.litetube.download.DownloadJob
import app.litetube.download.DownloadOptions
import app.litetube.download.DownloadService
import app.litetube.download.SaveTarget
import app.litetube.player.Captions
import app.litetube.player.PlayerController
import app.litetube.player.StreamPicker
import app.litetube.player.Summarizer
import app.litetube.player.YoutubeRequests
import coil.imageLoader
import coil.load
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
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
        private const val CONTROLS_HIDE_MS = 3_000L

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

    // Android 5–9 need the storage permission to save into Movies/Music/Pictures.
    private var afterStoragePermission: (() -> Unit)? = null
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterStoragePermission?.invoke() else toast(getString(R.string.storage_permission_needed))
        afterStoragePermission = null
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val hideControls = Runnable { if (player.isPlaying) b.playerView.hideController() }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            b.playerView.keepScreenOn = isPlaying
            // Get the controls out of the way once playback (re)starts; they come back on tap or pause.
            b.playerView.removeCallbacks(hideControls)
            if (isPlaying) b.playerView.postDelayed(hideControls, CONTROLS_HIDE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerController.closingToHome = false
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        // The layout manager must be set before inflating anything with the list as parent.
        b.list.layoutManager = LinearLayoutManager(this)
        header = HeaderVideoBinding.inflate(layoutInflater, b.list, false)
        b.list.adapter = ConcatAdapter(SingleViewAdapter(header.root), related)

        b.playerView.player = player
        b.playerView.setShowNextButton(false)
        b.playerView.setShowPreviousButton(false)
        // Go to the state the button asks for (never toggle): Media3 also calls this listener when
        // we merely sync the button icon after a rotation, and toggling there caused an endless
        // portrait/landscape flicker loop.
        b.playerView.setFullscreenButtonClickListener { fullscreen -> setFullscreen(fullscreen) }
        b.playerView.controllerShowTimeoutMs = CONTROLS_HIDE_MS.toInt()
        b.playerView.controllerHideOnTouch = true
        player.addListener(playerListener)

        PlayerController.onChanged = { updatePlaybackUi() }
        PlayerController.onError = { toast(getString(R.string.error_playback, it.errorCodeName)) }
        PlayerController.onSkipped = { toast(getString(R.string.sponsor_skipped)) }

        header.home.setOnClickListener { goHome() }
        header.quality.setOnClickListener { chooseQuality() }
        header.captions.setOnClickListener { chooseCaptions() }
        header.audioOnly.setOnClickListener { PlayerController.toggleAudioOnly() }
        header.comments.setOnClickListener { currentUrl?.let { CommentsSheet(this, it).show() } }
        header.more.setOnClickListener { showTools() }
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
            } catch (e: Throwable) {
                // Throwable, not Exception: a broken extractor can throw Errors, and showing
                // them beats crashing back to the previous screen.
                Log.e("LiteTube", "Couldn't open $url", e)
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
        header.captions.text = PlayerController.captionLanguage?.uppercase() ?: getString(R.string.captions_off)
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

    // --- Captions ---

    private fun chooseCaptions() {
        val info = PlayerController.info ?: return
        val options = Captions.options(info)
        if (options.isEmpty()) {
            toast(getString(R.string.no_captions))
            return
        }
        val labels = listOf(getString(R.string.captions_off_long)) + options.map { it.label }
        val checked = options.indexOfFirst { it.language == PlayerController.captionLanguage } + 1
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.captions)
            .setSingleChoiceItems(labels.toTypedArray<CharSequence>(), checked) { dialog, which ->
                dialog.dismiss()
                PlayerController.setCaptions(options.getOrNull(which - 1)?.language)
            }
            .show()
    }

    // --- Tools ("More" sheet) ---

    private fun showTools() {
        val info = PlayerController.info ?: return
        val live = StreamPicker.isLive(info)
        val tools = ArrayList<ToolsSheet.Tool>()
        if (!live) {
            tools += ToolsSheet.Tool(R.drawable.ic_download, R.string.tool_download_video, null) { downloadVideo(info) }
            tools += ToolsSheet.Tool(R.drawable.ic_music, R.string.tool_download_audio, R.string.tool_download_audio_hint) { downloadAudio(info) }
        }
        tools += ToolsSheet.Tool(R.drawable.ic_image, R.string.tool_save_frame, null) { saveFrame(info) }
        if (!live) {
            tools += ToolsSheet.Tool(R.drawable.ic_summary, R.string.tool_summarize, R.string.tool_summarize_hint) { summarize(info) }
        }
        tools += ToolsSheet.Tool(R.drawable.ic_share, R.string.share, null) {
            currentUrl?.let { share(this, info.name, it) }
        }
        ToolsSheet(this).show(tools)
    }

    private fun withStorage(action: () -> Unit) {
        val granted = Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            afterStoragePermission = action
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun startDownload(job: DownloadJob) {
        withStorage {
            // Optional: lets the progress notification show on Android 13+.
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            DownloadService.enqueue(this, job)
            toast(getString(R.string.download_started))
        }
    }

    private fun downloadVideo(info: StreamInfo) {
        val options = DownloadOptions.video(info)
        if (options.isEmpty()) {
            toast(getString(R.string.download_unavailable))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tool_download_video)
            .setItems(options.map { it.label }.toTypedArray<CharSequence>()) { _, which ->
                val o = options[which]
                startDownload(DownloadJob(info.name, SaveTarget.Kind.VIDEO, o.videoUrl, o.audioUrl, o.label))
            }
            .show()
    }

    private fun downloadAudio(info: StreamInfo) {
        val audio = DownloadOptions.audio(info)
        if (audio == null) {
            toast(getString(R.string.download_unavailable))
            return
        }
        startDownload(DownloadJob(info.name, SaveTarget.Kind.AUDIO, null, audio.content, "M4A"))
    }

    private fun saveFrame(info: StreamInfo) {
        if (PlayerController.current?.isAudioOnly == true) {
            toast(getString(R.string.frame_no_video))
            return
        }
        withStorage {
            val positionMs = player.currentPosition
            captureFrame { bitmap ->
                if (bitmap == null) {
                    toast(getString(R.string.frame_failed))
                    return@captureFrame
                }
                lifecycleScope.launch {
                    try {
                        val name = "${info.name} ${Format.duration(positionMs / 1000).replace(':', '.')}.jpg"
                        val location = withContext(Dispatchers.IO) {
                            val target = SaveTarget.create(this@PlayerActivity, SaveTarget.Kind.IMAGE, name, "image/jpeg")
                            try {
                                val out = target.file?.let { FileOutputStream(it) }
                                    ?: FileOutputStream(target.descriptor!!.fileDescriptor)
                                out.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                                target.publish()
                            } catch (e: Throwable) {
                                target.discard()
                                throw e
                            }
                            target.location
                        }
                        toast(getString(R.string.frame_saved, location))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        toast(getString(R.string.frame_failed))
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    /** Copies the frame on screen at full video resolution (Android 7+), else asks the stream. */
    private fun captureFrame(callback: (Bitmap?) -> Unit) {
        val size = player.videoSize
        val surface = b.playerView.videoSurfaceView
        if (Build.VERSION.SDK_INT >= 24 && surface is SurfaceView && size.width > 0 && size.height > 0) {
            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(surface, bitmap, { result ->
                    callback(if (result == PixelCopy.SUCCESS) bitmap else null.also { bitmap.recycle() })
                }, Handler(Looper.getMainLooper()))
            } catch (e: IllegalArgumentException) {
                bitmap.recycle()
                callback(null)
            }
            return
        }
        val url = PlayerController.current?.videoUrl ?: return callback(null)
        val positionUs = player.currentPosition * 1000
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(url, YoutubeRequests.headersFor(url))
                    retriever.getFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (e: Exception) {
                    null
                } finally {
                    runCatching { retriever.release() }
                }
            }
            callback(bitmap)
        }
    }

    private fun summarize(info: StreamInfo) {
        toast(getString(R.string.summarizing))
        lifecycleScope.launch {
            try {
                val summary = Summarizer.summarize(info, PlayerController.captionLanguage)
                if (summary.chapters.isEmpty() && summary.keyMoments.isEmpty()) {
                    toast(getString(R.string.summary_unavailable))
                } else {
                    showSummary(summary)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(errorText(e))
            }
        }
    }

    private fun showSummary(summary: Summarizer.Summary) {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, (8 * density).toInt(), padding, padding)
        }
        val ripple = android.util.TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tool_summarize)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(android.R.string.ok, null)
            .create()

        fun section(title: Int) = content.addView(TextView(this).apply {
            setText(title)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
        })
        fun moment(m: Summarizer.Moment) = content.addView(TextView(this).apply {
            text = "${Format.duration(m.positionMs / 1000).ifEmpty { "0:00" }}   ${m.text}"
            textSize = 14f
            setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            setBackgroundResource(ripple)
            setOnClickListener {
                player.seekTo(m.positionMs)
                dialog.dismiss()
            }
        })

        if (summary.chapters.isNotEmpty()) {
            section(R.string.summary_chapters)
            summary.chapters.forEach(::moment)
        }
        if (summary.keyMoments.isNotEmpty()) {
            section(R.string.summary_key_moments)
            summary.keyMoments.forEach(::moment)
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.summary_note, summary.captionLanguage ?: "-")
            textSize = 12f
            setPadding(0, (12 * density).toInt(), 0, 0)
        })
        dialog.show()
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

    private fun setFullscreen(fullscreen: Boolean) {
        if (fullscreen == isLandscape()) return
        requestedOrientation = if (fullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        }
    }

    private fun exitFullscreen() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    }

    private fun applyOrientation(config: Configuration) {
        val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        Log.i("LiteTube", "orientation landscape=$landscape requested=$requestedOrientation")
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
        if (isFinishing && !PlayerController.closingToHome) return
        if (Prefs.backgroundPlay) {
            PlayerController.setInBackground(true)
        } else {
            player.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        b.playerView.removeCallbacks(hideControls)
        player.removeListener(playerListener)
        b.playerView.player = null
        PlayerController.onChanged = null
        PlayerController.onError = null
        PlayerController.onSkipped = null
        // Back button closes the player for good; Home and recreation (e.g. theme change) keep playing.
        if (isFinishing && !PlayerController.closingToHome) PlayerController.stop()
        PlayerController.closingToHome = false
    }
}
