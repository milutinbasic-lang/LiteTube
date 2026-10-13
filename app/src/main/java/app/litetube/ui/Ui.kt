package app.litetube.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.litetube.R
import app.litetube.data.VideoItem
import app.litetube.databinding.ViewStateBinding
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import java.io.IOException

fun Context.toast(text: CharSequence) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

fun Context.errorText(e: Throwable): String = when (e) {
    is ReCaptchaException -> getString(R.string.error_captcha)
    is SignInConfirmNotBotException -> getString(R.string.error_bot_check)
    is ContentNotAvailableException -> e.message?.takeIf { it.isNotBlank() } ?: getString(R.string.error_unavailable)
    is IOException -> getString(R.string.error_network)
    // Include the type: it's what makes a report from a user's phone actionable.
    else -> getString(R.string.error_generic, e.javaClass.name + (e.message?.let { ": $it" } ?: ""))
}

fun ViewStateBinding.loading() {
    root.isVisible = true
    progress.isVisible = true
    message.isVisible = false
    action.isVisible = false
}

fun ViewStateBinding.show(text: CharSequence, actionText: CharSequence? = null, onAction: (() -> Unit)? = null) {
    root.isVisible = true
    progress.isVisible = false
    message.isVisible = true
    message.text = text
    action.isVisible = actionText != null && onAction != null
    action.text = actionText
    action.setOnClickListener { onAction?.invoke() }
}

fun ViewStateBinding.hide() {
    root.isVisible = false
}

/** Calls [block] when the list is scrolled close to its end (for loading the next page). */
fun RecyclerView.onNearEnd(block: () -> Unit) {
    addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy <= 0) return
            val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
            if (lm.findLastVisibleItemPosition() >= lm.itemCount - 4) block()
        }
    })
}

fun share(context: Context, title: String?, url: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TEXT, url)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share)))
}

fun showVideoMenu(activity: Activity, anchor: View, video: VideoItem) {
    val popup = PopupMenu(activity, anchor)
    popup.menu.add(0, 1, 0, R.string.play_audio_only)
    if (video.uploaderUrl != null) popup.menu.add(0, 2, 1, R.string.open_channel)
    popup.menu.add(0, 3, 2, R.string.share)
    popup.setOnMenuItemClickListener {
        when (it.itemId) {
            1 -> PlayerActivity.open(activity, video.url, audioOnly = true)
            2 -> video.uploaderUrl?.let { url -> ChannelActivity.open(activity, url, video.uploader) }
            3 -> share(activity, video.title, video.url)
        }
        true
    }
    popup.show()
}

/** Shows one fixed view as the first row of a list (used for headers via ConcatAdapter). */
class SingleViewAdapter(private val view: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        object : RecyclerView.ViewHolder(view) {}
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
}
