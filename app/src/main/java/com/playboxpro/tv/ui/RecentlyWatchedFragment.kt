package com.playboxpro.tv.ui

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.playboxpro.tv.MainActivity
import com.playboxpro.tv.R
import com.playboxpro.tv.model.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class RecentlyWatchedFragment : Fragment(R.layout.fragment_recently_watched) {
    private val vm: AppViewModel by activityViewModels()

    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var btnClearHistory: View
    private lateinit var licenseBadge: TextView
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView
    private lateinit var emptyView: View
    private lateinit var gridRecycler: RecyclerView

    private lateinit var historyAdapter: HistoryCardAdapter

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 15000)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Movies and series are loaded on demand.
        vm.wantCatalogue()

        titleText = view.findViewById(R.id.recentTitle)
        subtitleText = view.findViewById(R.id.recentSubtitle)
        btnClearHistory = view.findViewById(R.id.btnClearHistory)
        licenseBadge = view.findViewById(R.id.licenseBadge)
        clockTime = view.findViewById(R.id.clockTime)
        clockDate = view.findViewById(R.id.clockDate)
        emptyView = view.findViewById(R.id.recentEmptyView)
        gridRecycler = view.findViewById(R.id.recentGridRecycler)

        updateClock()
        clockHandler.postDelayed(clockRunnable, 15000)

        licenseBadge.text = "⏱  Active"

        val span = posterGridSpan(requireContext())
        gridRecycler.layoutManager = GridLayoutManager(requireContext(), span)
        historyAdapter = HistoryCardAdapter(emptyList()) { entry ->
            val lib = vm.library.value
            when (entry.kind) {
                "live" -> {
                    val ch = lib.channels.firstOrNull { it.id == entry.id }
                    (activity as? MainActivity)?.play(
                        url = ch?.url ?: entry.url,
                        title = entry.name,
                        poster = entry.poster,
                        kind = "live",
                        id = entry.id
                    )
                }
                "series", "episode" -> {
                    val targetId = entry.seriesId.ifBlank { entry.id }
                    val s = lib.series.firstOrNull { it.id == targetId }
                    if (s != null) {
                        (activity as? MainActivity)?.openSeriesDetail(s)
                    } else {
                        (activity as? MainActivity)?.openSeriesDetail(
                            Series(id = targetId, name = entry.seriesName.ifBlank { entry.name }, poster = entry.poster)
                        )
                    }
                }
                else -> {
                    val m = lib.movies.firstOrNull { it.id == entry.id }
                    if (m != null) {
                        (activity as? MainActivity)?.openMovieDetail(m)
                    } else {
                        (activity as? MainActivity)?.openMovieDetail(
                            Movie(id = entry.id, name = entry.name, poster = entry.poster, url = entry.url)
                        )
                    }
                }
            }
        }
        gridRecycler.adapter = historyAdapter

        btnClearHistory.setOnClickListener {
            AlertDialog.Builder(requireContext(), R.style.Theme_PlayBoxPro_Dialog)
                .setTitle("Clear watch history")
                .setMessage("Are you sure you want to clear your entire watch history? This cannot be undone.")
                .setPositiveButton("Clear") { _, _ ->
                    vm.clearHistory()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.history.collectLatest { list ->
                    subtitleText.text = "${list.size}"
                    if (list.isEmpty()) {
                        emptyView.visibility = View.VISIBLE
                        gridRecycler.visibility = View.GONE
                        btnClearHistory.visibility = View.GONE
                    } else {
                        emptyView.visibility = View.GONE
                        gridRecycler.visibility = View.VISIBLE
                        btnClearHistory.visibility = View.VISIBLE
                        historyAdapter.submitList(list)
                    }
                }
            }
        }
    }

    private fun updateClock() {
        val now = Date()
        val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val dateFmt = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        clockTime.text = timeFmt.format(now)
        clockDate.text = dateFmt.format(now)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clockHandler.removeCallbacks(clockRunnable)
    }

    class HistoryCardAdapter(
        private var items: List<HistoryEntry>,
        private val onClick: (HistoryEntry) -> Unit
    ) : RecyclerView.Adapter<HistoryCardAdapter.VH>() {

        private val dateFmt = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val root: View = v.findViewById(R.id.cardRoot)
            val img: ImageView = v.findViewById(R.id.posterImage)
            val title: TextView = v.findViewById(R.id.cardTitle)
            val meta: TextView = v.findViewById(R.id.cardMeta)
            val posterWrap: View = v.findViewById(R.id.posterWrap)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_movie_card, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.title.text = item.name
            holder.meta.text = dateFmt.format(Date(item.watchedAt))

            ImageLoader.loadPoster(holder.img, item.poster)

            // Same STB+ focus treatment as the catalogue cards: outline, no zoom.
            holder.root.setOnFocusChangeListener { _, hasFocus ->
                val ctx = holder.root.context
                holder.posterWrap.background =
                    if (hasFocus) androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.series_selector) else null
                holder.title.setTextColor(ctx.getColor(if (hasFocus) R.color.yellow else R.color.white))
            }

            holder.root.setOnClickListener { onClick(item) }
        }

        fun submitList(newList: List<HistoryEntry>) {
            items = newList
            notifyDataSetChanged()
        }
    }
}
