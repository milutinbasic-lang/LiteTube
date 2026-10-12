package app.litetube.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** All YouTube access goes through NewPipeExtractor: no API key, no account, no Google services. */
object YouTube {
    private val service get() = ServiceList.YouTube

    class PageResult(val items: List<ListItem>, val next: Page?)

    class ChannelResult(
        val url: String,
        val name: String,
        val avatar: String?,
        val subscribers: Long,
        val description: String?,
        val videosTab: ListLinkHandler?,
        val first: PageResult,
    )

    suspend fun search(query: String): Pair<SearchQueryHandler, PageResult> = io {
        val handler = service.searchQHFactory.fromQuery(query)
        val info = SearchInfo.getInfo(service, handler)
        handler to PageResult(info.relatedItems.toListItems(), info.nextPage.validOrNull())
    }

    suspend fun searchMore(handler: SearchQueryHandler, page: Page): PageResult = io {
        val p = SearchInfo.getMoreItems(service, handler, page)
        PageResult(p.items.toListItems(), p.nextPage.validOrNull())
    }

    suspend fun stream(url: String): StreamInfo = io { StreamInfo.getInfo(service, url) }

    suspend fun channel(url: String): ChannelResult = io {
        val info = ChannelInfo.getInfo(service, url)
        val tab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
        val first = if (tab != null) {
            val tabInfo = ChannelTabInfo.getInfo(service, tab)
            PageResult(tabInfo.relatedItems.toListItems(), tabInfo.nextPage.validOrNull())
        } else {
            PageResult(emptyList(), null)
        }
        ChannelResult(
            url = info.url,
            name = info.name,
            avatar = pickImage(info.avatars, 176),
            subscribers = info.subscriberCount,
            description = info.description,
            videosTab = tab,
            first = first,
        )
    }

    suspend fun channelMore(tab: ListLinkHandler, page: Page): PageResult = io {
        val p = ChannelTabInfo.getMoreItems(service, tab, page)
        PageResult(p.items.toListItems(), p.nextPage.validOrNull())
    }

    /**
     * Latest uploads of a channel. Uses the channel's RSS feed first: it's a tiny request,
     * which matters when refreshing many subscriptions on a slow connection.
     */
    suspend fun latestVideos(channelUrl: String): List<VideoItem> = io {
        try {
            val extractor = service.getFeedExtractor(channelUrl) ?: error("No feed")
            extractor.fetchPage()
            extractor.initialPage.items.map { it.toVideoItem() }
        } catch (e: Exception) {
            val info = ChannelInfo.getInfo(service, channelUrl)
            val tab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
                ?: return@io emptyList()
            ChannelTabInfo.getInfo(service, tab).relatedItems
                .filterIsInstance<StreamInfoItem>()
                .map { it.toVideoItem() }
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun Page?.validOrNull(): Page? = if (Page.isValid(this)) this else null
}

fun List<InfoItem>.toListItems(): List<ListItem> = mapNotNull {
    when (it) {
        is StreamInfoItem -> it.toVideoItem()
        is ChannelInfoItem -> it.toChannelItem()
        else -> null // playlists are not supported yet
    }
}

fun StreamInfoItem.toVideoItem() = VideoItem(
    url = url,
    title = name.orEmpty(),
    uploader = uploaderName.orEmpty(),
    uploaderUrl = uploaderUrl,
    thumbnail = pickImage(thumbnails, 320),
    durationSec = duration,
    views = viewCount,
    uploadedText = textualUploadDate,
    uploadedMillis = runCatching { uploadDate?.instant?.toEpochMilli() }.getOrNull() ?: 0L,
    isLive = streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM,
)

fun ChannelInfoItem.toChannelItem() = ChannelItem(
    url = url,
    name = name.orEmpty(),
    avatar = pickImage(thumbnails, 88),
    subscribers = subscriberCount,
)

/**
 * Picks the smallest image that is at least [targetWidth] px wide, so we never download
 * a 1280px thumbnail just to show it at 160dp.
 */
fun pickImage(images: List<Image>?, targetWidth: Int): String? {
    if (images.isNullOrEmpty()) return null
    fun width(i: Image) = when {
        i.width > 0 -> i.width
        i.height > 0 -> i.height * 16 / 9
        else -> -1
    }
    val known = images.filter { width(it) > 0 }.sortedBy { width(it) }
    if (known.isEmpty()) return images.first().url
    return (known.firstOrNull { width(it) >= targetWidth } ?: known.last()).url
}
