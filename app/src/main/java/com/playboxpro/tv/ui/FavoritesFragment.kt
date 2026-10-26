package com.playboxpro.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.playboxpro.tv.MainActivity
import com.playboxpro.tv.R
import com.playboxpro.tv.model.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class FavoritesFragment : Fragment(R.layout.fragment_favorites) {
    private val vm: AppViewModel by activityViewModels()

    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var licenseBadge: TextView
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView
    private lateinit var railRecycler: RecyclerView
    private lateinit var gridRecycler: RecyclerView
    private lateinit var emptyView: View

    private lateinit var railAdapter: CategoryRailAdapter
    private lateinit var movieAdapter: MovieCardAdapter

    private var selectedKind: String = "all"
    private var allFavs: List<Any> = emptyList()
    private var filteredFavs: List<Any> = emptyList()

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 15000)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        titleText = view.findViewById(R.id.favTitle)
        subtitleText = view.findViewById(R.id.favSubtitle)
        licenseBadge = view.findViewById(R.id.licenseBadge)
        clockTime = view.findViewById(R.id.clockTime)
        clockDate = view.findViewById(R.id.clockDate)
        railRecycler = view.findViewById(R.id.favRailRecycler)
        gridRecycler = view.findViewById(R.id.favGridRecycler)
        emptyView = view.findViewById(R.id.favEmptyView)

        updateClock()
        clockHandler.postDelayed(clockRunnable, 15000)

        licenseBadge.text = "⏱  Active"

        setupAdapters()

        vm.load()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.library.collectLatest { lib ->
                    val favIds = vm.repo.favorites()
                    val favList = mutableListOf<Any>()
                    favIds.forEach { id ->
                        val ch = lib.channels.firstOrNull { it.id == id || it.url == id }
                        val m = lib.movies.firstOrNull { it.id == id || it.url == id }
                        val s = lib.series.firstOrNull { it.id == id }
                        if (ch != null) favList.add(ch)
                        else if (m != null) favList.add(m)
                        else if (s != null) favList.add(s)
                    }

                    allFavs = favList
                    subtitleText.text = "${allFavs.size}"
                    setupTabs(favList)
                    filterGrid(selectedKind)
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

    private fun setupAdapters() {
        railRecycler.layoutManager = LinearLayoutManager(requireContext())
        railAdapter = CategoryRailAdapter(emptyList(), onClick = { cat ->
            selectedKind = cat.id
            filterGrid(cat.id)
        })
        railRecycler.adapter = railAdapter

        val span = posterGridSpan(requireContext())
        gridRecycler.layoutManager = GridLayoutManager(requireContext(), span)
        movieAdapter = MovieCardAdapter(
            items = emptyList(),
            getProgress = { id -> vm.repo.getResume(id)?.progress ?: 0f },
            onClick = { item ->
                when (item) {
                    is Movie -> (activity as? MainActivity)?.openMovieDetail(item)
                    is Series -> (activity as? MainActivity)?.openSeriesDetail(item)
                    is Channel -> (activity as? MainActivity)?.play(
                        url = item.url,
                        title = item.name,
                        poster = item.logo,
                        kind = "live",
                        id = item.id
                    )
                }
            }
        )
        gridRecycler.adapter = movieAdapter
    }

    private fun setupTabs(favs: List<Any>) {
        val chCount = favs.count { it is Channel }
        val movCount = favs.count { it is Movie }
        val serCount = favs.count { it is Series }

        val tabs = listOf(
            Category("all", "All", "fav", favs.size),
            Category("channels", "Channels", "fav", chCount),
            Category("movies", "Movies", "fav", movCount),
            Category("series", "Series", "fav", serCount)
        )
        railAdapter.submitList(tabs, selectedKind)
    }

    private fun filterGrid(kind: String) {
        filteredFavs = when (kind) {
            "channels" -> allFavs.filterIsInstance<Channel>()
            "movies" -> allFavs.filterIsInstance<Movie>()
            "series" -> allFavs.filterIsInstance<Series>()
            else -> allFavs
        }

        if (filteredFavs.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            gridRecycler.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            gridRecycler.visibility = View.VISIBLE
            movieAdapter.submitList(filteredFavs)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clockHandler.removeCallbacks(clockRunnable)
    }
}
