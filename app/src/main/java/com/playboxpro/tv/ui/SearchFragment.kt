package com.playboxpro.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.playboxpro.tv.MainActivity
import com.playboxpro.tv.R
import com.playboxpro.tv.model.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Search: one query box feeding three tabs - Live, Series and Movies - each
 * carrying its own count. Results are rows of tiles that scroll sideways;
 * with nothing typed the whole catalogue of the tab is shown.
 */
class SearchFragment : Fragment(R.layout.fragment_search) {
    private val vm: AppViewModel by activityViewModels()

    private lateinit var searchInput: EditText
    private lateinit var noResults: View
    private lateinit var clock: TextView

    private lateinit var tabLive: TextView
    private lateinit var tabSeries: TextView
    private lateinit var tabMovies: TextView

    private lateinit var rowsRecycler: RecyclerView
    private lateinit var rowsAdapter: SearchRowsAdapter

    private var currentTab = TAB_LIVE

    private var matchedLive: List<Channel> = emptyList()
    private var matchedSeries: List<Series> = emptyList()
    private var matchedMovies: List<Movie> = emptyList()

    /** Order of every result list; see StbDialogs.SORT_OPTIONS. */
    private var sortType = "added"

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 15_000)
        }
    }

    /** The view focused before the current one, so tabs know where the remote came from. */
    private var lastFocus: View? = null
    private val focusTracker = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, now -> lastFocus = now }

    /** Typing re-runs the search once the keys stop, not on every letter. */
    private val searchHandler = Handler(Looper.getMainLooper())
    private val searchRunnable = Runnable { performSearch(searchInput.text?.toString().orEmpty()) }

    companion object {
        private const val TAB_LIVE = "live"
        private const val TAB_SERIES = "series"
        private const val TAB_MOVIES = "movies"

        /** Tiles per row before the next row starts. */
        private const val ROW_SIZE = 30
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        searchInput = view.findViewById(R.id.searchInput)
        noResults = view.findViewById(R.id.searchNoResults)
        clock = view.findViewById(R.id.searchClock)

        tabLive = view.findViewById(R.id.btnSearchLive)
        tabSeries = view.findViewById(R.id.btnSearchSeries)
        tabMovies = view.findViewById(R.id.btnSearchMovies)
        rowsRecycler = view.findViewById(R.id.searchRows)

        view.findViewById<TextView>(R.id.searchProfileName).text =
            vm.repo.account()?.name?.takeIf { it.isNotBlank() } ?: "Pro Play Box"
        view.findViewById<View>(R.id.menuPill).setOnClickListener {
            (activity as? MainActivity)?.openDrawer()
        }

        updateClock()
        clockHandler.postDelayed(clockRunnable, 15_000)

        rowsAdapter = SearchRowsAdapter { item -> open(item) }
        rowsRecycler.layoutManager = LinearLayoutManager(requireContext())
        rowsRecycler.adapter = rowsAdapter
        rowsRecycler.setItemViewCacheSize(4)

        // A tab is chosen with OK, or by moving along the tab row. Coming up
        // from the results lands back on the tab already showing instead.
        val tabs = listOf(tabLive to TAB_LIVE, tabSeries to TAB_SERIES, tabMovies to TAB_MOVIES)
        for ((tab, id) in tabs) {
            tab.setOnClickListener { selectTab(id) }
            tab.setOnFocusChangeListener { _, has ->
                if (!has || currentTab == id) return@setOnFocusChangeListener
                val fromRow = tabs.any { it.first === lastFocus }
                if (fromRow) selectTab(id)
                else tabs.first { it.second == currentTab }.first.post { tabs.first { it.second == currentTab }.first.requestFocus() }
            }
        }
        view.viewTreeObserver.addOnGlobalFocusChangeListener(focusTracker)

        sortType = vm.repo.catalogSort("search")
        view.findViewById<View>(R.id.btnSearchSort).setOnClickListener { showSortDialog() }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                searchHandler.removeCallbacks(searchRunnable)
                searchHandler.postDelayed(searchRunnable, 300)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        searchInput.setOnEditorActionListener { _, _, _ ->
            searchHandler.removeCallbacks(searchRunnable)
            performSearch(searchInput.text?.toString().orEmpty())
            hideKeyboard()
            focusFirstTile()
            true
        }

        selectTab(TAB_LIVE)
        performSearch("")
        // Movies and series are loaded on demand; results follow as they arrive.
        vm.wantCatalogue()
        viewLifecycleOwner.lifecycleScope.launch {
            vm.library.collect { if (isAdded) performSearch(searchInput.text?.toString().orEmpty()) }
        }
        // Land on the Live tab, as the remote's starting point.
        tabLive.post { if (isAdded) tabLive.requestFocus() }
    }

    private fun open(item: Any) {
        val main = activity as? MainActivity ?: return
        when (item) {
            is Channel -> {
                // The live player, with UP / DOWN stepping through these results.
                val list = matchedLive
                val index = list.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                main.playLive(list, index)
            }
            is Series -> main.openSeriesDetail(item)
            is Movie -> main.openMovieDetail(item)
        }
    }

    /** The time, e.g. "8:29 pm". */
    private fun updateClock() {
        clock.text = SimpleDateFormat("h:mm a", Locale.getDefault())
            .format(Date())
            .lowercase(Locale.getDefault())
    }

    private fun selectTab(tab: String) {
        currentTab = tab
        tabLive.isSelected = tab == TAB_LIVE
        tabSeries.isSelected = tab == TAB_SERIES
        tabMovies.isSelected = tab == TAB_MOVIES
        showRows()
    }

    private fun performSearch(query: String) {
        val q = query.trim().lowercase(Locale.getDefault())
        val lib = vm.library.value

        if (q.isBlank()) {
            matchedLive = lib.channels
            matchedSeries = lib.series
            matchedMovies = lib.movies
        } else {
            matchedLive = lib.channels.filter {
                it.name.lowercase(Locale.getDefault()).contains(q) ||
                    it.categoryName.lowercase(Locale.getDefault()).contains(q)
            }
            matchedSeries = lib.series.filter {
                it.name.lowercase(Locale.getDefault()).contains(q) ||
                    it.genre.lowercase(Locale.getDefault()).contains(q)
            }
            matchedMovies = lib.movies.filter {
                it.name.lowercase(Locale.getDefault()).contains(q) ||
                    it.genre.lowercase(Locale.getDefault()).contains(q)
            }
        }

        // Parental control hides flagged titles here too.
        if (vm.repo.lockAdult) {
            matchedLive = matchedLive.filterNot { it.adult }
            matchedSeries = matchedSeries.filterNot { it.adult }
            matchedMovies = matchedMovies.filterNot { it.adult }
        }

        matchedLive = sorted(matchedLive, { it.name }, { 0.0 }, { it.added })
        matchedSeries = sorted(matchedSeries, { it.name }, { it.rating ?: 0.0 }, { it.added })
        matchedMovies = sorted(matchedMovies, { it.name }, { it.rating ?: 0.0 }, { it.added })

        tabLive.text = if (matchedLive.isEmpty()) "Live" else "Live ${matchedLive.size}"
        tabSeries.text = if (matchedSeries.isEmpty()) "Series" else "Series ${matchedSeries.size}"
        tabMovies.text = if (matchedMovies.isEmpty()) "Movies" else "Movies ${matchedMovies.size}"

        showRows()
    }

    private fun currentList(): List<Any> = when (currentTab) {
        TAB_SERIES -> matchedSeries
        TAB_MOVIES -> matchedMovies
        else -> matchedLive
    }

    private fun showRows() {
        val list = currentList()
        rowsAdapter.submitRows(list.chunked(ROW_SIZE))
        rowsRecycler.scrollToPosition(0)
        noResults.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun focusFirstTile(attempt: Int = 0) {
        if (!isAdded) return
        val row = rowsRecycler.findViewHolderForAdapterPosition(0) as? SearchRowsAdapter.VH
        val tile = row?.tiles?.findViewHolderForAdapterPosition(0)?.itemView
        if (tile != null && tile.requestFocus()) return
        if (attempt < 20) rowsRecycler.postDelayed({ focusFirstTile(attempt + 1) }, 50L)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    /** The same Select Sort Type dialog as the Movies and Series pages. */
    private fun showSortDialog() {
        StbDialogs.showSelection(requireContext(), "Please Select Sort Type", StbDialogs.SORT_OPTIONS, sortType) { picked ->
            sortType = picked
            vm.repo.setCatalogSort("search", picked)
            performSearch(searchInput.text?.toString().orEmpty())
        }
    }

    /** "Default" keeps the provider's order; channels carry no rating. */
    private fun <T> sorted(items: List<T>, name: (T) -> String, rating: (T) -> Double, added: (T) -> Long): List<T> =
        when (sortType) {
            "added" -> items.sortedByDescending(added)
            "rating" -> items.sortedByDescending(rating)
            "az" -> items.sortedBy { name(it).lowercase(Locale.getDefault()) }
            "za" -> items.sortedByDescending { name(it).lowercase(Locale.getDefault()) }
            else -> items
        }

    override fun onDestroyView() {
        view?.viewTreeObserver?.removeOnGlobalFocusChangeListener(focusTracker)
        super.onDestroyView()
        clockHandler.removeCallbacks(clockRunnable)
        searchHandler.removeCallbacks(searchRunnable)
    }
}
