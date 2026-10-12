package app.litetube.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import app.litetube.R
import app.litetube.data.Prefs
import app.litetube.data.Subscription
import app.litetube.data.Subscriptions
import app.litetube.data.YouTube
import app.litetube.databinding.ActivityListBinding
import app.litetube.databinding.HeaderChannelBinding
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

class ChannelActivity : BaseActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"

        fun open(context: Context, url: String, name: String?) {
            context.startActivity(
                Intent(context, ChannelActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_NAME, name)
            )
        }
    }

    private lateinit var b: ActivityListBinding
    private lateinit var header: HeaderChannelBinding
    private val adapter = ItemAdapter(
        onVideoClick = { PlayerActivity.open(this, it.url) },
        onVideoLongClick = { view, item -> showVideoMenu(this, view, item) },
    )
    private lateinit var url: String
    private var channel: YouTube.ChannelResult? = null
    private var nextPage: Page? = null
    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private var descriptionExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        b = ActivityListBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupToolbar(b.toolbar)
        supportActionBar?.title = intent.getStringExtra(EXTRA_NAME).orEmpty()

        header = HeaderChannelBinding.inflate(layoutInflater, b.list, false)
        header.root.isVisible = false
        header.subscribe.setOnClickListener { toggleSubscription() }
        header.description.setOnClickListener {
            descriptionExpanded = !descriptionExpanded
            header.description.maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3
        }

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = ConcatAdapter(SingleViewAdapter(header.root), adapter)
        b.list.onNearEnd { loadMore() }
        b.swipe.setOnRefreshListener { load() }
        load()
    }

    private fun load() {
        loadJob?.cancel()
        moreJob?.cancel()
        if (channel == null) b.state.loading()
        loadJob = lifecycleScope.launch {
            try {
                val result = YouTube.channel(url)
                channel = result
                nextPage = result.first.next
                bindHeader(result)
                adapter.submit(result.first.items)
                b.state.hide()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (channel == null) {
                    b.state.show(errorText(e), getString(R.string.retry)) { load() }
                } else {
                    toast(errorText(e))
                }
            } finally {
                b.swipe.isRefreshing = false
            }
        }
    }

    private fun loadMore() {
        val tab = channel?.videosTab ?: return
        val page = nextPage ?: return
        if (moreJob?.isActive == true || loadJob?.isActive == true) return
        moreJob = lifecycleScope.launch {
            try {
                val result = YouTube.channelMore(tab, page)
                nextPage = result.next
                adapter.append(result.items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(errorText(e))
            }
        }
    }

    private fun bindHeader(c: YouTube.ChannelResult) {
        supportActionBar?.title = c.name
        header.root.isVisible = true
        header.name.text = c.name
        header.meta.text = Format.subscribers(this, c.subscribers).orEmpty()
        header.meta.isVisible = c.subscribers >= 0
        header.avatar.isVisible = Prefs.showThumbnails && c.avatar != null
        if (header.avatar.isVisible) {
            header.avatar.load(c.avatar) { transformations(CircleCropTransformation()) }
        }
        header.description.text = c.description
        header.description.isVisible = !c.description.isNullOrBlank()
        // Fill in the avatar/name for subscriptions that were imported without them.
        if (Subscriptions.isSubscribed(c.url)) Subscriptions.updateDetails(Subscription(c.url, c.name, c.avatar))
        updateSubscribeButton()
    }

    private fun toggleSubscription() {
        val c = channel ?: return
        if (Subscriptions.isSubscribed(c.url)) {
            Subscriptions.remove(c.url)
        } else {
            Subscriptions.add(Subscription(c.url, c.name, c.avatar))
        }
        updateSubscribeButton()
    }

    private fun updateSubscribeButton() {
        val subscribed = Subscriptions.isSubscribed(channel?.url)
        header.subscribe.setText(if (subscribed) R.string.unsubscribe else R.string.subscribe)
    }
}
