package app.litetube.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import app.litetube.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService.LinkType

/** Invisible entry point for YouTube links opened or shared from other apps. */
class LinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { URL.find(it)?.value }
            else -> intent?.dataString
        }
        val type = url?.let { runCatching { ServiceList.YouTube.getLinkTypeByUrl(it) }.getOrNull() }
        when {
            url != null && type == LinkType.STREAM -> PlayerActivity.open(this, url)
            url != null && type == LinkType.CHANNEL -> ChannelActivity.open(this, url, null)
            else -> Toast.makeText(applicationContext, R.string.unsupported_link, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private companion object {
        val URL = Regex("https?://\\S+")
    }
}
