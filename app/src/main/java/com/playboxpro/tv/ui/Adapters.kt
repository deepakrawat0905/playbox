package com.playboxpro.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.playboxpro.tv.R
import com.playboxpro.tv.model.*

/**
 * Base for every list adapter in the app.
 *
 * RecyclerView forbids notify*() while it is laying out or scrolling, and on
 * a TV that is exactly when focus callbacks fire: a fast scroll recycles the
 * focused row mid-layout, focus jumps elsewhere, and the focus handler asks
 * for a refresh - which used to throw and take the whole app down. A refresh
 * that arrives mid-pass is now deferred to the next frame instead.
 */
abstract class SafeAdapter<VH : RecyclerView.ViewHolder> : RecyclerView.Adapter<VH>() {
    private var host: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        host = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (host === recyclerView) host = null
    }

    /** notifyDataSetChanged(), deferred if the list is busy right now. */
    fun refresh() {
        val rv = host
        if (rv != null && (rv.isComputingLayout || rv.scrollState != RecyclerView.SCROLL_STATE_IDLE)) {
            rv.post { refresh() }
        } else {
            notifyDataSetChanged()
        }
    }
}

/**
 * Category display name: uppercase, with the provider's ";" or ","
 * separators rendered as " | " ("News;Public" -> "NEWS | PUBLIC").
 *
 * Shared so the Live rail, the Movies/Series rail and the Settings dialogs
 * all label a category the same way.
 */
fun formatCategoryName(name: String): String =
    name.trim()
        .replace(Regex("\\s*[;,]\\s*"), " | ")
        .uppercase(java.util.Locale.getDefault())

/**
 * Number of poster columns that fit the content area.
 *
 * Derived from the actual card width rather than hardcoded breakpoints so the
 * compact STB+ poster geometry (90x125dp) fills the grid instead of leaving
 * wide gutters. [cardWidthRes] lets wider cell types ask for their own span.
 */
fun posterGridSpan(
    context: android.content.Context,
    cardWidthRes: Int = R.dimen.poster_card_w
): Int {
    val res = context.resources
    val density = res.displayMetrics.density
    val cellPx = res.getDimensionPixelSize(cardWidthRes) + (14 * density).toInt()
    val chromePx = res.getDimensionPixelSize(R.dimen.sidebar_width) +
        res.getDimensionPixelSize(R.dimen.safe_x) * 2
    val availablePx = res.displayMetrics.widthPixels - chromePx
    if (cellPx <= 0) return 4
    return (availablePx / cellPx).coerceIn(3, 12)
}

/**
 * 2:3 Vertical Movie / Series Poster Card Adapter
 */
private fun applyCardFocus(holder: MovieCardAdapter.VH, focused: Boolean) {
    val ctx = holder.root.context
    holder.posterWrap.background =
        if (focused) androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.series_selector) else null
    holder.title.setTextColor(ctx.getColor(if (focused) R.color.yellow else R.color.white))
    holder.title.isSelected = focused   // marquee runs only on the lit card
}

class MovieCardAdapter(
    private var items: List<Any>,
    private val getProgress: ((String) -> Float)? = null,
    /** STB+ item_movie_stream: the 90x125 card the info-page rails use. */
    private val compact: Boolean = false,
    /** Told which poster the remote is on, so a page can come back to it. */
    private val onItemFocused: ((Any) -> Unit)? = null,
    private val onClick: (Any) -> Unit
) : SafeAdapter<MovieCardAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v.findViewById(R.id.cardRoot)
        val img: ImageView = v.findViewById(R.id.posterImage)
        val title: TextView = v.findViewById(R.id.cardTitle)
        val meta: TextView = v.findViewById(R.id.cardMeta)
        val ratingContainer: View = v.findViewById(R.id.ratingContainer)
        val ratingText: TextView = v.findViewById(R.id.ratingText)
        val ratingDial: ProgressBar? = v.findViewById(R.id.circularProgressBar)
        val lockBadge: ImageView = v.findViewById(R.id.lockBadge)
        val posterWrap: View = v.findViewById(R.id.posterWrap)
        val progressBar: ProgressBar = v.findViewById(R.id.progressBar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (compact) R.layout.item_movie_card_small else R.layout.item_movie_card
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        if (!compact && parent is RecyclerView && parent.layoutManager is LinearLayoutManager && (parent.layoutManager as LinearLayoutManager).orientation == LinearLayoutManager.HORIZONTAL) {
            val lp = v.layoutParams
            lp.width = (160 * parent.context.resources.displayMetrics.density).toInt()
            v.layoutParams = lp
        }
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    /**
     * STB+ always shows the rating dial on a poster, reading "N/A" when the
     * provider gave no score, rather than hiding the badge entirely.
     */
    private fun bindRating(holder: VH, rating: Double?) {
        if (compact) return
        holder.ratingContainer.visibility = View.VISIBLE
        val percent = ((rating ?: 0.0) * 10).toInt().coerceIn(0, 100)
        holder.ratingDial?.progress = percent
        holder.ratingText.text = if (percent > 0) "$percent%" else "N/A"
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        when (item) {
            is Movie -> {
                holder.title.text = item.name
                holder.meta.text = listOfNotNull(item.year?.toString(), item.categoryName.ifBlank { item.genre }.takeIf { it.isNotBlank() }).joinToString(" • ")
                bindRating(holder, item.rating)
                holder.lockBadge.visibility = if (item.adult) View.VISIBLE else View.GONE

                val progress = getProgress?.invoke(item.id) ?: 0f
                if (progress > 0.02f && progress < 0.95f) {
                    holder.progressBar.visibility = View.VISIBLE
                    holder.progressBar.progress = (progress * 100).toInt()
                } else {
                    holder.progressBar.visibility = View.GONE
                }

                ImageLoader.loadPoster(holder.img, item.poster.ifBlank { item.backdrop })
            }
            is Series -> {
                holder.title.text = item.name
                holder.meta.text = listOfNotNull(item.year?.toString(), item.categoryName.ifBlank { item.genre }.takeIf { it.isNotBlank() }).joinToString(" • ")
                bindRating(holder, item.rating)
                holder.lockBadge.visibility = if (item.adult) View.VISIBLE else View.GONE
                holder.progressBar.visibility = View.GONE

                ImageLoader.loadPoster(holder.img, item.poster.ifBlank { item.backdrop })
            }
            is Channel -> {
                holder.title.text = item.name
                holder.meta.text = item.categoryName
                holder.ratingContainer.visibility = View.GONE
                holder.lockBadge.visibility = if (item.adult) View.VISIBLE else View.GONE
                holder.progressBar.visibility = View.GONE

                ImageLoader.loadPoster(holder.img, item.logo)
            }
        }

        // STB+ (d7/a, f7/a): the focused card gets a yellow outline around
        // the poster and a yellow title. No zoom - a scaled card spills over
        // its neighbours in a 5-column grid.
        applyCardFocus(holder, holder.root.isFocused)
        holder.root.setOnFocusChangeListener { _, hasFocus ->
            applyCardFocus(holder, hasFocus)
            if (hasFocus) onItemFocused?.invoke(item)
        }

        holder.root.setOnClickListener { onClick(item) }
    }

    // Stable ids let the grid keep the highlighted poster focused when the
    // catalogue is re-published; without them each refill dropped focus onto
    // whatever came first on screen (the search button).
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = when (val item = items[position]) {
        is Movie -> item.id.hashCode().toLong()
        is Series -> item.id.hashCode().toLong()
        is Channel -> item.id.hashCode().toLong()
        else -> position.toLong()
    }

    fun submitList(newItems: List<Any>) {
        items = newItems
        refresh()
    }
}

/**
 * 16:9 Wide Card Adapter for Continue Watching
 */
class WideCardAdapter(
    private var items: List<ResumeEntry>,
    private val onClick: (ResumeEntry) -> Unit
) : SafeAdapter<WideCardAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v.findViewById(R.id.wideCardRoot)
        val img: ImageView = v.findViewById(R.id.wideThumbnail)
        val title: TextView = v.findViewById(R.id.wideTitle)
        val subtitle: TextView = v.findViewById(R.id.wideSubtitle)
        val progress: ProgressBar = v.findViewById(R.id.wideProgress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_wide_card, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.subtitle.text = if (item.remainingMinutes > 0) "${item.remainingMinutes}m left" else "Resume playback"
        holder.progress.progress = (item.progress * 100).toInt()

        val imageUrl = item.backdrop.ifBlank { item.poster }
        ImageLoader.loadBackdrop(holder.img, imageUrl, cornerRadiusDp = 14)

        holder.root.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                holder.title.setTextColor(holder.root.context.getColor(R.color.accent))
            } else {
                holder.title.setTextColor(holder.root.context.getColor(R.color.text))
            }
        }

        holder.root.setOnClickListener { onClick(item) }
    }

    fun submitList(newItems: List<ResumeEntry>) {
        items = newItems
        refresh()
    }
}

/**
 * Compact Channel Tile Adapter for Home screen rows
 */
class ChannelTileAdapter(
    private var items: List<Channel>,
    private val onClick: (Channel) -> Unit
) : SafeAdapter<ChannelTileAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v.findViewById(R.id.channelTileRoot)
        val logo: ImageView = v.findViewById(R.id.tileLogo)
        val name: TextView = v.findViewById(R.id.tileName)
        val category: TextView = v.findViewById(R.id.tileCategory)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel_tile, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.category.text = item.categoryName

        ImageLoader.loadLogo(holder.logo, item.logo)

        holder.root.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                holder.name.setTextColor(holder.root.context.getColor(R.color.accent))
            } else {
                holder.name.setTextColor(holder.root.context.getColor(R.color.text))
            }
        }

        holder.root.setOnClickListener { onClick(item) }
    }

    fun submitList(newItems: List<Channel>) {
        items = newItems
        refresh()
    }
}

/**
 * Channel Row Adapter for Live TV Center Column
 */
class ChannelRowAdapter(
    private var items: List<Channel>,
    private var selectedChannelId: String = "",
    private val isFav: (String) -> Boolean = { false },
    private val onFocusChange: (Channel) -> Unit,
    private val onClick: (Channel) -> Unit,
    /** Long press toggles favourite, the way STB+ does it. */
    private val onLongPress: ((Channel) -> Unit)? = null,
    /** Handles LEFT back to the category rail, by list order not geometry. */
    private val onLeftKey: (() -> Boolean)? = null,
    /**
     * Handles UP / DOWN by adapter position. Android's geometric focus search
     * loses the row during a fast scroll and hands focus to whatever is
     * nearest - the category rail - which STB+ never does: the highlight
     * stays in the column it is in until LEFT / RIGHT says otherwise.
     */
    private val onVerticalKey: ((position: Int, delta: Int) -> Boolean)? = null,
    /** RIGHT / MENU on a channel: the player options (audio, aspect). */
    private val onOptionsKey: (() -> Unit)? = null
) : SafeAdapter<ChannelRowAdapter.VH>() {

    init {
        // Keeps the focused channel put when the list rebinds, e.g. after a
        // long press toggles a favourite.
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        items[position].id.hashCode().toLong()

    /** The one play marker currently showing. */
    private var markedPlaying: View? = null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v.findViewById(R.id.channelRowRoot)
        val num: TextView = v.findViewById(R.id.chNumber)
        val logo: ImageView = v.findViewById(R.id.chLogo)
        val name: TextView = v.findViewById(R.id.chName)
        val archive: ImageView = v.findViewById(R.id.chArchive)
        val playing: ImageView = v.findViewById(R.id.chPlaying)
        val favStar: ImageView = v.findViewById(R.id.chFavoriteStar)
    }

    /**
     * STB+ p6/e: the row under the remote gets Roboto Regular for the title,
     * Roboto Medium for the number and the black catch-up marker; every other
     * row is Light / Regular / white marker.
     */
    private fun applyFocusStyle(holder: VH, focused: Boolean) {
        val ctx = holder.itemView.context
        holder.name.typeface = androidx.core.content.res.ResourcesCompat.getFont(
            ctx, if (focused) R.font.robotoregular else R.font.robotolight
        )
        holder.num.typeface = androidx.core.content.res.ResourcesCompat.getFont(
            ctx, if (focused) R.font.robotomedium else R.font.robotoregular
        )
        holder.archive.setImageResource(if (focused) R.drawable.ic_autoplay_black else R.drawable.ic_autoplay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel_row, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val isSelected = (item.id == selectedChannelId)

        // STB+ numbers channels by their place in the current list, not by
        // the provider's global channel number.
        holder.num.text = (position + 1).toString()
        holder.name.text = item.name
        holder.favStar.visibility = if (isFav(item.id)) View.VISIBLE else View.GONE
        holder.archive.visibility = if (item.archive) View.VISIBLE else View.GONE
        // STB+ marks the channel on air with a small play icon after its name.
        holder.playing.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.root.isSelected = isSelected
        applyFocusStyle(holder, holder.root.isFocused)

        ImageLoader.loadLogo(holder.logo, item.logo)

        if (isSelected) markedPlaying = holder.playing

        holder.root.setOnFocusChangeListener { _, hasFocus ->
            applyFocusStyle(holder, hasFocus)
            if (hasFocus) {
                selectedChannelId = item.id
                // Exactly one row carries the marker. The old row's focus-loss
                // callback can run after this gain, so it is cleared here
                // rather than trusted to clear itself.
                markedPlaying?.takeIf { it !== holder.playing }?.visibility = View.GONE
                holder.playing.visibility = View.VISIBLE
                markedPlaying = holder.playing
                onFocusChange(item)
            }
        }

        holder.root.setOnClickListener { onClick(item) }

        holder.root.setOnLongClickListener {
            onLongPress?.invoke(item)
            onLongPress != null
        }

        holder.root.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                    if (event.repeatCount > 0) true else onLeftKey?.invoke() ?: false
                // Nothing lives to the right of the channel list in STB+, so
                // RIGHT (or MENU) opens the player options for the preview.
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_MENU -> {
                    if (event.repeatCount == 0) onOptionsKey?.invoke()
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP ->
                    onVerticalKey?.invoke(holder.bindingAdapterPosition, -1) ?: false
                android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                    onVerticalKey?.invoke(holder.bindingAdapterPosition, +1) ?: false
                else -> false
            }
        }
    }

    fun submitList(newItems: List<Channel>, selectedId: String = "") {
        items = newItems
        selectedChannelId = selectedId
        markedPlaying = null
        refresh()
    }
}

/**
 * Category Rail Adapter for left sidebars in Catalog / Live TV
 */
class CategoryRailAdapter(
    private var items: List<Category>,
    private var selectedId: String = "all",
    private val onClick: (Category) -> Unit,
    /**
     * Fired as focus moves through the rail so the panel beside it can load
     * immediately, without the user having to press OK on every category.
     */
    private val onFocus: ((Category) -> Unit)? = null,
    /**
     * Handles RIGHT off the rail. Without it Android picks the neighbouring
     * column by vertical position, so leaving row 2 of the categories lands
     * on row 2 of the channels instead of the top of the list.
     */
    private val onRightKey: (() -> Boolean)? = null,
    /** UP / DOWN by position, for the same reason as the channel list. */
    private val onVerticalKey: ((position: Int, delta: Int) -> Boolean)? = null,
    /** STB+ long-presses OK on a category to lock / unlock it behind a PIN. */
    private val onLongPress: ((Category) -> Unit)? = null
) : SafeAdapter<CategoryRailAdapter.VH>() {

    /**
     * The row currently marked selected. Tracked directly rather than through
     * notifyDataSetChanged, which would rebind mid-traversal and drop focus.
     */
    private var selectedRow: View? = null

    init {
        // Stable ids let RecyclerView keep the focused row across a rebind.
        // Without them the library's second emission wipes every holder and
        // focus snaps back to row 0.
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        items[position].id.hashCode().toLong()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v.findViewById(R.id.catRailRoot)
        val icon: TextView = v.findViewById(R.id.catIcon)
        val lock: ImageView = v.findViewById(R.id.catLock)
        val name: TextView = v.findViewById(R.id.catName)
        val count: TextView = v.findViewById(R.id.catCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_category_rail, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cat = items[position]
        val isSelected = (cat.id == selectedId)
        applyWeight(holder, holder.root.isFocused)

        holder.name.text = cat.name
        holder.count.text = if (cat.count > 0) cat.count.toString() else ""
        holder.lock.visibility = if (cat.adult) View.VISIBLE else View.GONE
        holder.root.isSelected = isSelected

        when (cat.id) {
            "all" -> {
                holder.icon.text = if (cat.kind == "fav") "☆" else "≡"
                holder.icon.visibility = View.VISIBLE
            }
            "channels" -> {
                holder.icon.text = "📺"
                holder.icon.visibility = View.VISIBLE
            }
            "movies" -> {
                holder.icon.text = "🗂"
                holder.icon.visibility = View.VISIBLE
            }
            "series" -> {
                holder.icon.text = "🎬"
                holder.icon.visibility = View.VISIBLE
            }
            "recent" -> {
                holder.icon.text = "↺"
                holder.icon.visibility = View.VISIBLE
            }
            "favorites" -> {
                holder.icon.text = "★"
                holder.icon.visibility = View.VISIBLE
            }
            else -> {
                holder.icon.visibility = View.GONE
            }
        }

        if (isSelected) selectedRow = holder.root

        // Landing on a category is enough to load it.
        holder.root.setOnFocusChangeListener { v, hasFocus ->
            applyWeight(holder, hasFocus)
            if (hasFocus && selectedId != cat.id) {
                selectedId = cat.id
                selectedRow?.takeIf { it !== v }?.isSelected = false
                v.isSelected = true
                selectedRow = v
                onFocus?.invoke(cat)
            }
        }

        holder.root.setOnClickListener {
            selectedId = cat.id
            selectedRow?.takeIf { it !== holder.root }?.isSelected = false
            holder.root.isSelected = true
            selectedRow = holder.root
            onClick(cat)
        }

        holder.root.setOnLongClickListener {
            onLongPress?.invoke(cat)
            onLongPress != null
        }

        holder.root.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                // STB+ acts on the first press only; a held RIGHT does nothing more.
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (event.repeatCount > 0) true else onRightKey?.invoke() ?: false
                android.view.KeyEvent.KEYCODE_DPAD_UP ->
                    onVerticalKey?.invoke(holder.bindingAdapterPosition, -1) ?: false
                android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                    onVerticalKey?.invoke(holder.bindingAdapterPosition, +1) ?: false
                else -> false
            }
        }
    }

    /** STB+ sets Roboto Regular on the current category and Light on the rest. */
    private fun applyWeight(holder: VH, current: Boolean) {
        val ctx = holder.itemView.context
        holder.name.typeface = androidx.core.content.res.ResourcesCompat.getFont(
            ctx, if (current) R.font.robotoregular else R.font.robotolight
        )
    }

    /** Row position of a category id, or -1. */
    fun indexOf(categoryId: String): Int = items.indexOfFirst { it.id == categoryId }

    fun categoryAt(position: Int): Category? = items.getOrNull(position)

    /** Drops the lit row, e.g. after a focus that was never a real choice. */
    fun clearSelection() {
        selectedId = ""
        selectedRow?.isSelected = false
        selectedRow = null
    }

    fun submitList(newItems: List<Category>, currentSelectedId: String = "all") {
        items = newItems
        selectedId = currentSelectedId
        refresh()
    }
}

/**
 * Episode Adapter for Series Detail view
 */
class EpisodeCardAdapter(
    private var items: List<Episode>,
    private val getProgress: ((String) -> Float)? = null,
    private val onClick: (Episode) -> Unit
) : SafeAdapter<EpisodeCardAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v.findViewById(R.id.episodeRoot)
        val still: ImageView = v.findViewById(R.id.epStill)
        val tag: TextView = v.findViewById(R.id.epTag)
        val title: TextView = v.findViewById(R.id.epTitle)
        val duration: TextView = v.findViewById(R.id.epDuration)
        val plot: TextView = v.findViewById(R.id.epPlot)
        val progress: ProgressBar = v.findViewById(R.id.epProgress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_episode_card, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ep = items[position]
        holder.tag.text = "Episode ${ep.episode}"
        holder.title.text = ep.name.ifBlank { "Episode ${ep.episode}" }
        holder.duration.text = ep.duration?.let { "${it / 60}m" } ?: ""
        holder.plot.text = ep.plot.ifBlank { "No episode description." }

        val p = getProgress?.invoke(ep.id) ?: 0f
        if (p > 0.02f && p < 0.95f) {
            holder.progress.visibility = View.VISIBLE
            holder.progress.progress = (p * 100).toInt()
        } else {
            holder.progress.visibility = View.GONE
        }

        ImageLoader.loadBackdrop(holder.still, ep.still, cornerRadiusDp = 4)

        holder.root.setOnClickListener { onClick(ep) }
    }

    fun submitList(newItems: List<Episode>) {
        items = newItems
        refresh()
    }
}

/**
 * Dialog Option Selector Adapter (Audio, Subtitles, Aspect Ratio, Theme, Subtitle Size)
 */
class DialogOptionAdapter(
    private val options: List<Pair<String, String>>, // id to label
    private var selectedId: String,
    private val onSelect: (String) -> Unit
) : SafeAdapter<DialogOptionAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        // STB+ item_dialog_selection: pill row, 16sp Roboto Regular, and a
        // text colour that flips to black when the pill fills white on focus.
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        val pad = (10 * density).toInt()
        val margin = (3 * density).toInt()

        val tv = TextView(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(margin, margin, margin, margin) }
            setPadding(pad + (10 * density).toInt(), pad, pad, pad)
            setTextColor(
                androidx.core.content.ContextCompat.getColorStateList(
                    ctx, R.color.selector_dialog_text
                )
            )
            textSize = 16f
            typeface = androidx.core.content.res.ResourcesCompat.getFont(
                ctx, R.font.robotoregular
            )
            gravity = android.view.Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = ctx.getDrawable(R.drawable.bg_btn_light_pill)
        }
        return VH(tv)
    }

    override fun getItemCount(): Int = options.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (id, label) = options[position]
        holder.text.text = if (id == selectedId) "✓  $label" else "    $label"
        holder.text.isSelected = (id == selectedId)
        holder.text.setOnClickListener { onSelect(id) }
    }
}
