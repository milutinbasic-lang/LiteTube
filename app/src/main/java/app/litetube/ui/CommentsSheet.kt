package app.litetube.ui

import android.annotation.SuppressLint
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.litetube.R
import app.litetube.data.Comment
import app.litetube.data.YouTube
import app.litetube.databinding.ItemCommentBinding
import app.litetube.databinding.SheetListBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

/** Comments load only when this sheet is opened, so they cost no data otherwise. */
class CommentsSheet(private val activity: AppCompatActivity, private val videoUrl: String) {

    private val dialog = BottomSheetDialog(activity)
    private val b = SheetListBinding.inflate(LayoutInflater.from(activity))
    private val adapter = CommentsAdapter()
    private var nextPage: Page? = null
    private var job: Job? = null

    fun show() {
        b.title.setText(R.string.comments)
        b.list.layoutManager = LinearLayoutManager(activity)
        b.list.adapter = adapter
        b.list.onNearEnd { loadMore() }

        dialog.setContentView(b.root)
        // Take most of the screen, like YouTube's comments panel.
        val height = (activity.resources.displayMetrics.heightPixels * 0.75).toInt()
        b.root.layoutParams = b.root.layoutParams.apply { this.height = height }
        dialog.behavior.peekHeight = height
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.setOnDismissListener { job?.cancel() }
        dialog.show()
        load()
    }

    private fun load() {
        b.state.loading()
        job = activity.lifecycleScope.launch {
            try {
                val result = YouTube.comments(videoUrl)
                when {
                    result.disabled -> b.state.show(activity.getString(R.string.comments_disabled))
                    result.comments.isEmpty() -> b.state.show(activity.getString(R.string.no_comments))
                    else -> {
                        b.state.hide()
                        if (result.count > 0) {
                            b.title.text = activity.getString(R.string.comments_count, Format.compact(result.count.toLong()))
                        }
                        adapter.append(result.comments, at = 0)
                        nextPage = result.next
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                b.state.show(activity.errorText(e), activity.getString(R.string.retry)) { load() }
            }
        }
    }

    private fun loadMore() {
        val page = nextPage ?: return
        if (job?.isActive == true) return
        job = activity.lifecycleScope.launch {
            try {
                val (comments, next) = YouTube.moreComments(videoUrl, page)
                nextPage = next
                adapter.append(comments, at = adapter.itemCount)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                activity.toast(activity.errorText(e))
            }
        }
    }

    private fun loadReplies(parent: Comment) {
        val page = parent.repliesPage ?: return
        activity.lifecycleScope.launch {
            try {
                val (replies, _) = YouTube.moreComments(videoUrl, page, asReplies = true)
                adapter.insertReplies(parent, replies)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                activity.toast(activity.errorText(e))
            }
        }
    }

    private inner class CommentsAdapter : RecyclerView.Adapter<CommentsAdapter.Holder>() {
        private val items = ArrayList<Comment>()
        private val expanded = HashSet<Comment>()

        fun append(list: List<Comment>, at: Int) {
            items.addAll(at, list)
            notifyItemRangeInserted(at, list.size)
        }

        @SuppressLint("NotifyDataSetChanged")
        fun insertReplies(parent: Comment, replies: List<Comment>) {
            val index = items.indexOf(parent)
            if (index < 0) return
            expanded += parent
            items.addAll(index + 1, replies)
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(val b: ItemCommentBinding) : RecyclerView.ViewHolder(b.root) {
            init {
                b.text.movementMethod = LinkMovementMethod.getInstance()
            }

            fun bind(c: Comment) {
                val context = b.root.context
                val density = context.resources.displayMetrics.density
                val start = ((if (c.isReply) 56 else 16) * density).toInt()
                b.root.setPadding(start, b.root.paddingTop, b.root.paddingRight, b.root.paddingBottom)

                b.author.text = listOfNotNull(
                    if (c.pinned) context.getString(R.string.pinned) else null,
                    c.author.ifEmpty { null },
                    c.date,
                ).joinToString(" · ")
                b.text.text = c.textHtml?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY) } ?: c.textPlain
                b.likes.text = listOfNotNull(
                    c.likes.takeIf { it > 0 }?.let { "👍 " + Format.compact(it.toLong()) },
                    if (c.hearted) "❤" else null,
                ).joinToString("  ")
                b.likes.isVisible = b.likes.text.isNotEmpty()

                val canExpand = !c.isReply && c.repliesPage != null && c !in expanded
                b.replies.isVisible = canExpand
                if (canExpand) {
                    b.replies.text = if (c.replyCount > 0) {
                        context.resources.getQuantityString(R.plurals.show_replies, c.replyCount, c.replyCount)
                    } else {
                        context.getString(R.string.show_replies_unknown)
                    }
                    b.replies.setOnClickListener {
                        b.replies.isEnabled = false
                        loadReplies(c)
                    }
                    b.replies.isEnabled = true
                }
            }
        }
    }
}
