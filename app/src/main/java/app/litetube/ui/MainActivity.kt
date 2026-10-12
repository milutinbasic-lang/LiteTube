package app.litetube.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import app.litetube.R
import app.litetube.data.FeedCache
import app.litetube.data.Prefs
import app.litetube.data.Subscriptions
import app.litetube.data.VideoItem
import app.litetube.data.YouTube
import app.litetube.databinding.ActivityListBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Home: newest videos from local subscriptions, newest first. No algorithmic recommendations. */
class MainActivity : BaseActivity() {

    private lateinit var b: ActivityListBinding
    private val adapter = ItemAdapter(
        onVideoClick = { PlayerActivity.open(this, it.url) },
        onVideoLongClick = { view, item -> showVideoMenu(this, view, item) },
    )
    private var refreshJob: Job? = null
    private var loadedVersion = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityListBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupToolbar(b.toolbar, showUp = false)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.setHasFixedSize(true)
        b.list.adapter = adapter
        b.swipe.setOnRefreshListener { refresh() }
    }

    override fun onStart() {
        super.onStart()
        if (Subscriptions.all().isEmpty()) {
            refreshJob?.cancel()
            b.swipe.isRefreshing = false
            b.swipe.isEnabled = false
            adapter.submit(emptyList())
            b.state.show(getString(R.string.empty_feed), getString(R.string.search)) {
                startActivity(Intent(this, SearchActivity::class.java))
            }
            loadedVersion = Subscriptions.version
            return
        }
        b.swipe.isEnabled = true

        if (loadedVersion == -1) {
            loadedVersion = Subscriptions.version
            lifecycleScope.launch {
                val snapshot = withContext(Dispatchers.IO) { FeedCache.load() }
                showItems(snapshot.items)
                val stale = System.currentTimeMillis() - snapshot.savedAt > STALE_AFTER_MS
                if (snapshot.items.isEmpty() || Prefs.autoRefresh && stale) refresh()
            }
        } else if (loadedVersion != Subscriptions.version) {
            loadedVersion = Subscriptions.version
            refresh()
        }
    }

    private fun showItems(items: List<VideoItem>) {
        adapter.submit(items)
        if (items.isEmpty()) b.state.show(getString(R.string.feed_pull_to_refresh)) else b.state.hide()
    }

    private fun refresh() {
        val subs = Subscriptions.all()
        if (subs.isEmpty()) {
            b.swipe.isRefreshing = false
            return
        }
        refreshJob?.cancel()
        b.swipe.isRefreshing = true
        b.state.hide()
        refreshJob = lifecycleScope.launch {
            try {
                // A few channels at a time: fast enough, but gentle on weak CPUs and slow networks.
                val limit = Semaphore(4)
                var failures = 0
                val results = subs.map { sub ->
                    async {
                        limit.withPermit {
                            try {
                                YouTube.latestVideos(sub.url)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failures++
                                emptyList()
                            }
                        }
                    }
                }.awaitAll()

                val items = results.flatten()
                    .distinctBy { it.url }
                    .sortedByDescending { it.uploadedMillis }
                    .take(MAX_ITEMS)
                if (items.isNotEmpty() || failures == 0) {
                    withContext(Dispatchers.IO) { FeedCache.save(items) }
                    showItems(items)
                } else if (adapter.isEmpty) {
                    b.state.show(getString(R.string.error_network), getString(R.string.retry)) { refresh() }
                }
                if (failures > 0) toast(getString(R.string.feed_failures, failures, subs.size))
            } finally {
                b.swipe.isRefreshing = false
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val target = when (item.itemId) {
            R.id.action_search -> SearchActivity::class.java
            R.id.action_subscriptions -> SubscriptionsActivity::class.java
            R.id.action_settings -> SettingsActivity::class.java
            else -> return super.onOptionsItemSelected(item)
        }
        startActivity(Intent(this, target))
        return true
    }

    private companion object {
        const val STALE_AFTER_MS = 60 * 60 * 1000L
        const val MAX_ITEMS = 300
    }
}
