package com.playboxpro.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.playboxpro.tv.R
import com.playboxpro.tv.model.Channel
import com.playboxpro.tv.model.Movie
import com.playboxpro.tv.model.Series

/**
 * Search results as rows of tiles. Each row scrolls sideways on its own,
 * with an arrow at either end while there is more to see that way.
 */
class SearchRowsAdapter(
    private val onClick: (Any) -> Unit
) : SafeAdapter<SearchRowsAdapter.VH>() {

    private var rows: List<List<Any>> = emptyList()
    private val pool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 60) }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tiles: RecyclerView = v.findViewById(R.id.rowTiles)
        val left: View = v.findViewById(R.id.rowArrowLeft)
        val right: View = v.findViewById(R.id.rowArrowRight)
        val adapter = SearchTileAdapter()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_row, parent, false)
        val holder = VH(v)
        holder.tiles.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
        holder.tiles.setRecycledViewPool(pool)
        holder.tiles.adapter = holder.adapter
        holder.tiles.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateArrows(holder)
        })
        holder.tiles.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateArrows(holder) }
        return holder
    }

    private fun updateArrows(holder: VH) {
        holder.left.visibility = if (holder.tiles.canScrollHorizontally(-1)) View.VISIBLE else View.INVISIBLE
        holder.right.visibility = if (holder.tiles.canScrollHorizontally(1)) View.VISIBLE else View.INVISIBLE
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.adapter.submit(rows[position], onClick)
        holder.tiles.scrollToPosition(0)
        holder.tiles.post { updateArrows(holder) }
    }

    fun submitRows(newRows: List<List<Any>>) {
        rows = newRows
        refresh()
    }
}

class SearchTileAdapter : SafeAdapter<SearchTileAdapter.VH>() {

    private var items: List<Any> = emptyList()
    private var onClick: (Any) -> Unit = {}

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v.findViewById(R.id.tileRoot)
        val image: ImageView = v.findViewById(R.id.tileImage)
        val name: TextView = v.findViewById(R.id.tileName)
        val badgeLeft: TextView = v.findViewById(R.id.tileBadgeLeft)
        val badgeRight: TextView = v.findViewById(R.id.tileBadgeRight)
    }

    fun submit(list: List<Any>, click: (Any) -> Unit) {
        items = list
        onClick = click
        refresh()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_search_tile, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val d = holder.root.resources.displayMetrics.density
        when (item) {
            is Channel -> {
                holder.name.text = item.name
                // Logos sit in the upper part of the tile, clear of the name.
                holder.image.scaleType = ImageView.ScaleType.FIT_CENTER
                holder.image.setPadding((14 * d).toInt(), (22 * d).toInt(), (14 * d).toInt(), (42 * d).toInt())
                ImageLoader.loadLogo(holder.image, item.logo)
                badge(holder.badgeLeft, countryOf(item.name, item.categoryName) ?: "TV")
                badge(holder.badgeRight, qualityOf(item.name))
            }
            is Movie -> {
                holder.name.text = item.name
                holder.image.scaleType = ImageView.ScaleType.CENTER_CROP
                holder.image.setPadding(0, 0, 0, 0)
                ImageLoader.loadPoster(holder.image, item.poster.ifBlank { item.backdrop }, 10)
                badge(holder.badgeLeft, "MOVIE")
                badge(holder.badgeRight, qualityOf(item.name) ?: ratingOf(item.rating))
            }
            is Series -> {
                holder.name.text = item.name
                holder.image.scaleType = ImageView.ScaleType.CENTER_CROP
                holder.image.setPadding(0, 0, 0, 0)
                ImageLoader.loadPoster(holder.image, item.poster.ifBlank { item.backdrop }, 10)
                badge(holder.badgeLeft, "SERIES")
                badge(holder.badgeRight, qualityOf(item.name) ?: ratingOf(item.rating))
            }
        }

        // The focused tile lifts a little above its neighbours.
        holder.root.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f)
                .translationZ(if (has) 8 * d else 0f).setDuration(120).start()
        }
        holder.root.setOnClickListener { onClick(item) }
    }

    override fun onViewRecycled(holder: VH) {
        holder.root.animate().cancel()
        holder.root.scaleX = 1f; holder.root.scaleY = 1f; holder.root.translationZ = 0f
    }

    private fun badge(view: TextView, text: String?) {
        view.text = text.orEmpty()
        view.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private companion object {
        val COUNTRY_PREFIX = Regex("^\\s*([A-Za-z]{2,3})\\s*[|:\\-]")

        /** "UK: CHANNEL 5" / "UK | ENTERTAINMENT" -> "UK". */
        fun countryOf(name: String, category: String): String? =
            (COUNTRY_PREFIX.find(name) ?: COUNTRY_PREFIX.find(category))
                ?.groupValues?.get(1)?.uppercase()

        fun qualityOf(name: String): String? {
            val n = name.uppercase()
            return when {
                "4K" in n || "UHD" in n || "2160" in n -> "4K"
                "FHD" in n || "1080" in n -> "FHD"
                Regex("\\bHD\\b").containsMatchIn(n) -> "HD"
                Regex("\\bSD\\b").containsMatchIn(n) -> "SD"
                else -> null
            }
        }

        fun ratingOf(rating: Double?): String? =
            rating?.takeIf { it > 0 }?.let { String.format(java.util.Locale.US, "★ %.1f", it) }
    }
}
