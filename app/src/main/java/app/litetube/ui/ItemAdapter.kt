package app.litetube.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import app.litetube.R
import app.litetube.data.ChannelItem
import app.litetube.data.ListItem
import app.litetube.data.Prefs
import app.litetube.data.VideoItem
import app.litetube.databinding.ItemChannelBinding
import app.litetube.databinding.ItemVideoBinding
import coil.dispose
import coil.load
import coil.transform.CircleCropTransformation

/** One plain RecyclerView adapter for videos and channels: cheap to bind, no diffing overhead. */
class ItemAdapter(
    private val onVideoClick: (VideoItem) -> Unit,
    private val onChannelClick: (ChannelItem) -> Unit = {},
    private val onVideoLongClick: ((View, VideoItem) -> Unit)? = null,
    private val onChannelLongClick: ((View, ChannelItem) -> Unit)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<ListItem>()
    private val showThumbnails = Prefs.showThumbnails

    val isEmpty get() = items.isEmpty()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<ListItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<ListItem>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun remove(item: ListItem) {
        val index = items.indexOf(item)
        if (index >= 0) {
            items.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) =
        if (items[position] is VideoItem) TYPE_VIDEO else TYPE_CHANNEL

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_VIDEO) {
            VideoHolder(ItemVideoBinding.inflate(inflater, parent, false))
        } else {
            ChannelHolder(ItemChannelBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is VideoItem -> (holder as VideoHolder).bind(item)
            is ChannelItem -> (holder as ChannelHolder).bind(item)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is VideoHolder -> holder.b.thumb.dispose()
            is ChannelHolder -> holder.b.avatar.dispose()
        }
    }

    inner class VideoHolder(val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(v: VideoItem) {
            val context = b.root.context
            b.title.text = v.title
            b.meta.text = Format.videoMeta(context, v, includeDuration = !showThumbnails)
            b.thumbBox.isVisible = showThumbnails
            if (showThumbnails) {
                b.thumb.load(v.thumbnail)
                b.duration.text = if (v.isLive) context.getString(R.string.live) else Format.duration(v.durationSec)
                b.duration.isVisible = b.duration.text.isNotEmpty()
            }
            b.root.setOnClickListener { onVideoClick(v) }
            val longClick = onVideoLongClick
            if (longClick != null) {
                b.root.setOnLongClickListener { longClick(it, v); true }
            } else {
                b.root.setOnLongClickListener(null)
            }
        }
    }

    inner class ChannelHolder(val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(c: ChannelItem) {
            b.name.text = c.name
            b.meta.text = Format.subscribers(b.root.context, c.subscribers).orEmpty()
            b.meta.isVisible = c.subscribers >= 0
            b.avatar.isVisible = showThumbnails
            if (showThumbnails) {
                b.avatar.load(c.avatar) { transformations(CircleCropTransformation()) }
            }
            b.root.setOnClickListener { onChannelClick(c) }
            val longClick = onChannelLongClick
            if (longClick != null) {
                b.root.setOnLongClickListener { longClick(it, c); true }
            } else {
                b.root.setOnLongClickListener(null)
            }
        }
    }

    private companion object {
        const val TYPE_VIDEO = 0
        const val TYPE_CHANNEL = 1
    }
}
