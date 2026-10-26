package com.playboxpro.tv.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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

class CollectionFragment : Fragment(R.layout.fragment_simple) {
    private val vm: AppViewModel by activityViewModels()
    private val mode by lazy { requireArguments().getString("mode", "favorites") }

    companion object {
        fun newInstance(m: String) = CollectionFragment().apply {
            arguments = Bundle().apply { putString("mode", m) }
        }
    }

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        // Movies and series are loaded on demand.
        vm.wantCatalogue()

        val root = v as android.widget.ScrollView
        val box = root.getChildAt(0) as android.widget.LinearLayout

        val titleView = v.findViewById<TextView>(R.id.title)
        val bodyView = v.findViewById<TextView>(R.id.body)

        titleView.text = when (mode) {
            "favorites" -> "Favorites"
            "continue" -> "Continue Watching"
            "recent" -> "Recently Watched"
            else -> "Collection"
        }

        bodyView.text = "Loading items…"

        val span = if (mode == "continue") {
            posterGridSpan(requireContext(), R.dimen.wide_card_w)
        } else {
            posterGridSpan(requireContext())
        }
        val rv = RecyclerView(requireContext()).apply {
            layoutManager = GridLayoutManager(requireContext(), span)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
        }

        box.addView(rv)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (mode == "continue") {
                    vm.continueWatching.collectLatest { cwList ->
                        if (cwList.isEmpty()) {
                            bodyView.visibility = View.VISIBLE
                            bodyView.text = "No continue watching items yet."
                            rv.visibility = View.GONE
                        } else {
                            bodyView.visibility = View.GONE
                            rv.visibility = View.VISIBLE
                            rv.layoutManager = GridLayoutManager(requireContext(), posterGridSpan(requireContext(), R.dimen.wide_card_w))
                            rv.adapter = WideCardAdapter(cwList) { entry ->
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
                        }
                    }
                } else {
                    vm.library.collectLatest { lib ->
                        val ids = when (mode) {
                            "favorites" -> vm.repo.favorites()
                            "recent" -> vm.repo.recent().toSet()
                            else -> emptySet()
                        }

                        val list: List<Any> = buildList {
                            addAll(lib.movies.filter { it.id in ids || it.url in ids })
                            addAll(lib.series.filter { it.id in ids })
                            addAll(lib.channels.filter { it.id in ids || it.url in ids })
                        }

                        if (list.isEmpty()) {
                            bodyView.visibility = View.VISIBLE
                            bodyView.text = if (mode == "favorites") "No favorites added yet. Press ★ on any movie, series or channel to add it here." else "No recently watched items."
                            rv.visibility = View.GONE
                        } else {
                            bodyView.visibility = View.GONE
                            rv.visibility = View.VISIBLE
                            rv.adapter = MovieCardAdapter(
                                items = list,
                                getProgress = { id -> vm.repo.getResume(id)?.progress ?: 0f },
                                onClick = { x ->
                                    when (x) {
                                        is Movie -> (activity as? MainActivity)?.openMovieDetail(x)
                                        is Series -> (activity as? MainActivity)?.openSeriesDetail(x)
                                        is Channel -> (activity as? MainActivity)?.play(
                                            url = x.url,
                                            title = x.name,
                                            poster = x.logo,
                                            kind = "live",
                                            id = x.id
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
