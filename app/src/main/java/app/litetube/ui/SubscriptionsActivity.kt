package app.litetube.ui

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import app.litetube.R
import app.litetube.data.ChannelItem
import app.litetube.data.Subscriptions
import app.litetube.databinding.ActivityListBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SubscriptionsActivity : BaseActivity() {

    private lateinit var b: ActivityListBinding
    private val adapter = ItemAdapter(
        onVideoClick = {},
        onChannelClick = { ChannelActivity.open(this, it.url, it.name) },
        onChannelLongClick = { _, item -> confirmUnsubscribe(item) },
    )

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) import(uri)
    }
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) export(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityListBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupToolbar(b.toolbar)
        supportActionBar?.setTitle(R.string.subscriptions)

        b.swipe.isEnabled = false
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        render()
    }

    private fun render() {
        val subs = Subscriptions.all()
        adapter.submit(subs.map { ChannelItem(it.url, it.name, it.avatar, -1) })
        if (subs.isEmpty()) b.state.show(getString(R.string.no_subscriptions)) else b.state.hide()
    }

    private fun confirmUnsubscribe(item: ChannelItem) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.unsubscribe_confirm, item.name))
            .setPositiveButton(R.string.unsubscribe) { _, _ ->
                Subscriptions.remove(item.url)
                adapter.remove(item)
                if (adapter.isEmpty) b.state.show(getString(R.string.no_subscriptions))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun import(uri: Uri) {
        lifecycleScope.launch {
            try {
                val subs = withContext(Dispatchers.IO) {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    Subscriptions.parseImport(text)
                }
                if (subs.isEmpty()) {
                    toast(getString(R.string.import_nothing))
                } else {
                    toast(getString(R.string.import_done, Subscriptions.addAll(subs)))
                    render()
                }
            } catch (e: Exception) {
                toast(getString(R.string.import_failed))
            }
        }
    }

    private fun export(uri: Uri) {
        val json = Subscriptions.exportJson()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                }
                toast(getString(R.string.export_done))
            } catch (e: Exception) {
                toast(getString(R.string.export_failed))
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_subscriptions, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_import -> {
            importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
            true
        }
        R.id.action_export -> {
            exportLauncher.launch("litetube_subscriptions.json")
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
