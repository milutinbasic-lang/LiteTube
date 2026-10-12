package app.litetube.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import app.litetube.R
import app.litetube.data.YouTube
import app.litetube.databinding.ActivitySearchBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler

class SearchActivity : BaseActivity() {

    private lateinit var b: ActivitySearchBinding
    private val adapter = ItemAdapter(
        onVideoClick = { PlayerActivity.open(this, it.url) },
        onChannelClick = { ChannelActivity.open(this, it.url, it.name) },
        onVideoLongClick = { view, item -> showVideoMenu(this, view, item) },
    )
    private var handler: SearchQueryHandler? = null
    private var nextPage: Page? = null
    private var searchJob: Job? = null
    private var moreJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupToolbar(b.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.setHasFixedSize(true)
        b.list.adapter = adapter
        b.list.onNearEnd { loadMore() }
        b.state.hide()

        b.query.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || isEnter) {
                search(b.query.text.toString())
                true
            } else {
                false
            }
        }
        b.query.requestFocus()
    }

    private fun search(text: String) {
        val query = text.trim()
        if (query.isEmpty()) return
        hideKeyboard()
        searchJob?.cancel()
        moreJob?.cancel()
        handler = null
        nextPage = null
        adapter.submit(emptyList())
        b.state.loading()
        searchJob = lifecycleScope.launch {
            try {
                val (h, result) = YouTube.search(query)
                handler = h
                nextPage = result.next
                adapter.submit(result.items)
                if (result.items.isEmpty()) b.state.show(getString(R.string.no_results)) else b.state.hide()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                b.state.show(errorText(e), getString(R.string.retry)) { search(query) }
            }
        }
    }

    private fun loadMore() {
        val h = handler ?: return
        val page = nextPage ?: return
        if (moreJob?.isActive == true) return
        moreJob = lifecycleScope.launch {
            try {
                val result = YouTube.searchMore(h, page)
                nextPage = result.next
                adapter.append(result.items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(errorText(e))
            }
        }
    }

    private fun hideKeyboard() {
        getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(b.query.windowToken, 0)
        b.query.clearFocus()
    }
}
