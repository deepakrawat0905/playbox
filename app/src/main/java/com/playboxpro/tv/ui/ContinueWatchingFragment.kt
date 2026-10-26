package com.playboxpro.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
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

class ContinueWatchingFragment : Fragment(R.layout.fragment_continue_watching) {
    private val vm: AppViewModel by activityViewModels()

    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var btnRemoveCw: View
    private lateinit var licenseBadge: TextView
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView
    private lateinit var emptyView: View
    private lateinit var gridRecycler: RecyclerView

    private lateinit var cwAdapter: CwCardAdapter
    private var focusedResumeEntry: ResumeEntry? = null

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 15000)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        titleText = view.findViewById(R.id.cwTitle)
        subtitleText = view.findViewById(R.id.cwSubtitle)
        btnRemoveCw = view.findViewById(R.id.btnRemoveCw)
        licenseBadge = view.findViewById(R.id.licenseBadge)
        clockTime = view.findViewById(R.id.clockTime)
        clockDate = view.findViewById(R.id.clockDate)
        emptyView = view.findViewById(R.id.cwEmptyView)
        gridRecycler = view.findViewById(R.id.cwGridRecycler)

        updateClock()
        clockHandler.postDelayed(clockRunnable, 15000)

        licenseBadge.text = "⏱  Active"

        val span = posterGridSpan(requireContext(), R.dimen.wide_card_w)
        gridRecycler.layoutManager = GridLayoutManager(requireContext(), span)
        cwAdapter = CwCardAdapter(
            items = emptyList(),
            onFocus = { entry ->
                focusedResumeEntry = entry
                btnRemoveCw.visibility = View.VISIBLE
            },
            onClick = { entry ->
                (activity as? MainActivity)?.play(
                    url = entry.url,
                    title = entry.title,
                    poster = entry.poster,
                    backdrop = entry.backdrop,
                    kind = entry.kind,
                    seriesId = entry.seriesId,
                    season = entry.seasonNumber,
                    episode = entry.episodeNumber,
                    id = entry.id
                )
            }
        )
        gridRecycler.adapter = cwAdapter

        btnRemoveCw.setOnClickListener {
            focusedResumeEntry?.let { entry ->
                vm.clearResume(entry.id)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.continueWatching.collectLatest { list ->
                    subtitleText.text = "${list.size}"
                    if (list.isEmpty()) {
                        emptyView.visibility = View.VISIBLE
                        gridRecycler.visibility = View.GONE
                        btnRemoveCw.visibility = View.GONE
                        focusedResumeEntry = null
                    } else {
                        emptyView.visibility = View.GONE
                        gridRecycler.visibility = View.VISIBLE
                        cwAdapter.submitList(list)
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

    class CwCardAdapter(
        private var items: List<ResumeEntry>,
        private val onFocus: (ResumeEntry) -> Unit,
        private val onClick: (ResumeEntry) -> Unit
    ) : RecyclerView.Adapter<CwCardAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val root: View = v.findViewById(R.id.wideCardRoot)
            val img: ImageView = v.findViewById(R.id.wideThumbnail)
            val title: TextView = v.findViewById(R.id.wideTitle)
            val meta: TextView = v.findViewById(R.id.wideSubtitle)
            val progressBar: ProgressBar = v.findViewById(R.id.wideProgress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_wide_card, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.title.text = item.title

            val leftSec = if (item.durationMs > item.positionMs) ((item.durationMs - item.positionMs) / 1000).toInt() else 0
            val mins = leftSec / 60
            val hrs = mins / 60
            val remMins = mins % 60
            holder.meta.text = if (hrs > 0) "${hrs}h ${remMins}m left" else "${mins}m left"

            holder.progressBar.progress = (item.progress * 100).toInt()

            ImageLoader.loadBackdrop(holder.img, item.backdrop.ifBlank { item.poster }, cornerRadiusDp = 12)

            holder.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    holder.title.setTextColor(holder.root.context.getColor(R.color.accent))
                    onFocus(item)
                } else {
                    holder.title.setTextColor(holder.root.context.getColor(R.color.text))
                }
            }

            holder.root.setOnClickListener { onClick(item) }
        }

        fun submitList(newList: List<ResumeEntry>) {
            items = newList
            notifyDataSetChanged()
        }
    }
}
