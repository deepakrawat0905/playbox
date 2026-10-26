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

class CatalogFragment : Fragment(R.layout.fragment_catalog) {
    private val vm: AppViewModel by activityViewModels()
    private val kind by lazy { requireArguments().getString("kind", "movies") }

    private lateinit var pageTitle: TextView
    private lateinit var subtitle: TextView
    private lateinit var playlistBadge: TextView
    private lateinit var licenseBadge: TextView
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView
    private lateinit var btnSearch: View
    private lateinit var emptyLayout: View
    private lateinit var searchInput: android.widget.EditText
    private lateinit var categoryIcon: android.widget.ImageView
    private lateinit var railRecycler: RecyclerView
    private lateinit var gridRecycler: RecyclerView

    private lateinit var railAdapter: CategoryRailAdapter
    private lateinit var movieAdapter: MovieCardAdapter

    private var allItems: List<Any> = emptyList()
    private var filteredItems: List<Any> = emptyList()
    private var selectedCategoryId = ""

    /** False until the user first moves into the rail; no category is lit before. */
    private var railTouched = false
    private var searchQuery = ""
    private lateinit var sortPill: View
    private lateinit var emptyText: TextView
    private lateinit var searchIcon: View
    private var initialFocusDone = false

    /**
     * The title opened from this page. Coming back from its detail page the
     * view is rebuilt, and the highlight returns to this poster rather than
     * jumping to the first one.
     */
    private var openedItemId: String? = null

    /** The poster the remote was last on; refills and returns land back on it. */
    private var focusedItemId: String? = null

    /** Current sort order; see StbDialogs.SORT_OPTIONS. */
    private var sortType = "default"

    companion object {
        fun newInstance(kind: String) = CatalogFragment().apply {
            arguments = Bundle().apply { putString("kind", kind) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // A fresh view (first open, or back from a detail page) lands again.
        initialFocusDone = false

        pageTitle = view.findViewById(R.id.catalogPageTitle)
        subtitle = view.findViewById(R.id.catalogSubtitle)
        playlistBadge = view.findViewById(R.id.playlistBadge)
        licenseBadge = view.findViewById(R.id.licenseBadge)
        clockTime = view.findViewById(R.id.clockTime)
        clockDate = view.findViewById(R.id.clockDate)
        btnSearch = view.findViewById(R.id.btnCatalogSearch)
        railRecycler = view.findViewById(R.id.catalogRailRecycler)
        gridRecycler = view.findViewById(R.id.catalogGridRecycler)

        emptyLayout = view.findViewById(R.id.catalogEmptyLayout)
        emptyText = view.findViewById(R.id.catalogEmptyText)
        searchInput = view.findViewById(R.id.catalogSearchInput)
        sortPill = view.findViewById(R.id.btnCatalogSort)

        sortType = vm.repo.catalogSort(kind)
        sortPill.setOnClickListener { showSortDialog() }
        searchIcon = view.findViewById(R.id.btnSearchIcon)
        setupSearchBox()
        categoryIcon = view.findViewById(R.id.categoryIcon)

        categoryIcon.setImageResource(
            if (kind == "movies") R.drawable.ic_nav_movies else R.drawable.ic_nav_tvseries
        )

        val account = vm.repo.account()
        playlistBadge.text = account?.name?.takeIf { it.isNotBlank() } ?: "Playlist"
        playlistBadge.visibility = View.VISIBLE

        // STB+ filters in place from this box rather than leaving the screen.
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString().orEmpty().trim()
                filterGrid(selectedCategoryId)
            }
        })

        setupAdapters()

        vm.load()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.library.collectLatest { lib ->
                    allItems = if (kind == "movies") lib.movies else lib.series
                    val unit = if (kind == "movies") "Movies" else "Series"
                    subtitle.text = "Total $unit: ${allItems.size}"
                    setupRail(lib)
                    filterGrid(selectedCategoryId)
                    if (!initialFocusDone) {
                        landOnGrid()
                    } else if (searchQuery.isBlank()) {
                        // The catalogue arrives in several passes and each
                        // re-bind can drop the lit card; put the highlight
                        // back on the poster the remote was on, not the first.
                        gridRecycler.post {
                            val f = activity?.currentFocus
                            if (isAdded && (f == null || (f === searchInput && !railTouched))) focusRememberedCard()
                        }
                    }
                }
            }
        }
    }

    private fun setupAdapters() {
        railRecycler.layoutManager = LinearLayoutManager(requireContext())
        railAdapter = CategoryRailAdapter(
            items = emptyList(),
            onClick = { cat -> railTouched = true; selectCategory(cat) },
            // Moving through the rail loads that category straight away -
            // unless the focus is Android's stray first focus during landing.
            onFocus = { cat ->
                // Android hands the rail a stray focus while the page is
                // still landing; that one always lands on the row already
                // selected, so only a move to a different row counts as the
                // user actually using the rail.
                if (!initialFocusDone && (cat.id == selectedCategoryId || filteredItems.isNotEmpty())) {
                    // Still landing (first open, or back from a title): the
                    // rail never takes the highlight away from the grid.
                    if (cat.id == selectedCategoryId) railAdapter.clearSelection()
                } else {
                    initialFocusDone = true
                    railTouched = true
                    selectCategory(cat)
                }
            },
            // RIGHT enters the grid at the first poster, not at whichever
            // card happens to sit level with the focused rail row.
            onRightKey = {
                if (filteredItems.isEmpty()) false else { focusFirstCard(); true }
            }
        )
        railRecycler.adapter = railAdapter

        // STB+ pins the grid at five columns.
        gridRecycler.layoutManager = GridLayoutManager(requireContext(), 5)
        // Phones are wider than a TV for the same height: as many poster
        // columns as fit, instead of five spread apart.
        if (!ScreenFit.isTv(requireContext())) {
            val dm = resources.displayMetrics
            val gridDp = maxOf(dm.widthPixels, dm.heightPixels) / dm.density * 0.71f - 40f
            (gridRecycler.layoutManager as GridLayoutManager).spanCount = (gridDp / 126f).toInt().coerceAtLeast(5)
        }
        movieAdapter = MovieCardAdapter(
            items = emptyList(),
            getProgress = { id -> vm.repo.getResume(id)?.progress ?: 0f },
            onItemFocused = { item -> focusedItemId = itemId(item) },
            onClick = { item ->
                when (item) {
                    is Movie -> { openedItemId = item.id; (activity as? MainActivity)?.openMovieDetail(item) }
                    is Series -> { openedItemId = item.id; (activity as? MainActivity)?.openSeriesDetail(item) }
                }
            }
        )
        gridRecycler.adapter = movieAdapter
    }

    /**
     * Moves focus onto the first poster once the grid has laid out. Retries
     * because the cards do not exist yet when the data first arrives.
     */
    private fun focusFirstCard(attempt: Int = 0) {
        if (!isAdded) return

        val card = gridRecycler.findViewHolderForAdapterPosition(0)?.itemView
        if (card != null && card.requestFocus()) return

        if (attempt < 20) {
            gridRecycler.postDelayed({ focusFirstCard(attempt + 1) }, 50L)
        }
    }

    /**
     * Puts the highlight back on the poster the remote was last on (or the
     * first one), scrolling to it when the refill moved it off screen.
     */
    private fun focusRememberedCard(attempt: Int = 0) {
        if (!isAdded) return
        val index = focusedItemId?.let { id -> filteredItems.indexOfFirst { itemId(it) == id } }
            ?.takeIf { it >= 0 } ?: 0
        if (attempt == 0 && index > 0) gridRecycler.scrollToPosition(index)
        val card = gridRecycler.findViewHolderForAdapterPosition(index)?.itemView
        if (card != null && card.requestFocus()) return
        if (attempt < 20) gridRecycler.postDelayed({ focusRememberedCard(attempt + 1) }, 50L)
    }

    private fun selectCategory(cat: Category) {
        selectedCategoryId = cat.id
        pageTitle.text = cat.name
        filterGrid(cat.id)
    }

    private fun setupRail(lib: Library) {
        val rawCategories = if (kind == "movies") lib.movieCategories else lib.seriesCategories

        // Calculate accurate counts per category
        val counts = mutableMapOf<String, Int>()
        for (item in allItems) {
            val catId = when (item) {
                is Movie -> item.categoryId
                is Series -> item.categoryId
                else -> ""
            }
            counts[catId] = (counts[catId] ?: 0) + 1
        }

        // STB+ opens the rail with Favorite and Recently Watched, then the
        // provider's categories uppercased with pipe separators.
        // Categories hidden from Settings never reach the rail.
        val hidden = vm.repo.hiddenCategories(if (kind == "movies") "movie" else "series")

        val railList = buildList {
            add(Category("favorites", "Favorite", kind, 0))
            add(Category("recent", "Recently Watched", kind, 0))
            for (cat in rawCategories) {
                if (cat.id in hidden) continue
                val c = counts[cat.id] ?: cat.count
                add(Category(cat.id, formatCategoryName(cat.name), cat.kind, c, cat.adult))
            }
        }

        // Land on the first real category, never on Favorite or Recently
        // Watched - both are empty for a new user, which made the grid look
        // broken. Waits for a real category to exist, because the library
        // emits once while still loading.
        val firstRealCategory = railList
            .firstOrNull { it.id != "favorites" && it.id != "recent" }?.id

        // The rail is rebuilt as the library arrives, and the first pass can
        // be the two synthetic rows alone. Once real categories exist, a page
        // still parked on Favorite / Recently Watched - which the user has
        // not chosen - moves onto the first real one rather than sitting on
        // an empty grid.
        val parkedOnSynthetic = !railTouched &&
            (selectedCategoryId == "favorites" || selectedCategoryId == "recent")
        if (railList.none { it.id == selectedCategoryId } || parkedOnSynthetic) {
            selectedCategoryId = firstRealCategory ?: railList.firstOrNull()?.id.orEmpty()
        }

        railAdapter.submitList(railList, if (railTouched) selectedCategoryId else "")
        pageTitle.text = railList.firstOrNull { it.id == selectedCategoryId }?.name.orEmpty()
    }

    /**
     * Lands the first focus on the first poster. The catalogue can arrive
     * seconds after the channels do, so this keeps trying until a card
     * actually exists; until then any focus the rail receives is ignored.
     */
    // ------------------------------------------------------------------
    // Search: a round button that opens the box (STB+)
    // ------------------------------------------------------------------

    private fun setupSearchBox() {
        searchIcon.setOnClickListener { openSearch() }

        // The keyboard's search key, or BACK with nothing typed, closes the
        // box again; a query that is still filtering the grid stays up.
        searchInput.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            if (searchQuery.isBlank()) closeSearch() else focusFirstCard()
            true
        }
        searchInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                hideKeyboard()
                if (searchQuery.isBlank()) closeSearch() else focusFirstCard()
                true
            } else false
        }
        searchInput.setOnFocusChangeListener { _, has ->
            if (!has && searchQuery.isBlank()) closeSearch(refocus = false)
        }
    }

    private fun openSearch() {
        searchIcon.visibility = View.GONE
        btnSearch.visibility = View.VISIBLE
        searchInput.requestFocus()
        searchInput.post {
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeSearch(refocus: Boolean = true) {
        if (btnSearch.visibility != View.VISIBLE) return
        btnSearch.visibility = View.GONE
        searchIcon.visibility = View.VISIBLE
        if (refocus) searchIcon.post { searchIcon.requestFocus() }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    /**
     * The Select Sort Type dialog, with STB+'s own five orders. The choice
     * is remembered per section and applied to every category in it.
     */
    private fun showSortDialog() {
        StbDialogs.showSelection(
            requireContext(),
            "Please Select Sort Type",
            StbDialogs.SORT_OPTIONS,
            sortType
        ) { picked ->
            sortType = picked
            vm.repo.setCatalogSort(kind, picked)
            filterGrid(selectedCategoryId)
            focusFirstCard()
        }
    }

    /**
     * "Default" is the order the provider sent, which is what every other
     * screen shows; the rest re-order that list without touching it.
     */
    private fun applySort(items: List<Any>): List<Any> {
        fun name(x: Any) = itemName(x)
        fun rating(x: Any) = when (x) {
            is Movie -> x.rating ?: 0.0
            is Series -> x.rating ?: 0.0
            else -> 0.0
        }
        fun added(x: Any) = when (x) {
            is Movie -> x.added
            is Series -> x.added
            else -> 0L
        }
        return when (sortType) {
            "added" -> items.sortedByDescending { added(it) }
            "rating" -> items.sortedByDescending { rating(it) }
            "az" -> items.sortedBy { name(it).lowercase() }
            "za" -> items.sortedByDescending { name(it).lowercase() }
            else -> items
        }
    }

    private fun landOnGrid(attempt: Int = 0) {
        if (!isAdded || initialFocusDone) return
        val returnTo = (openedItemId ?: focusedItemId)?.let { id -> filteredItems.indexOfFirst { itemId(it) == id } } ?: -1
        val index = if (returnTo >= 0) returnTo else 0
        if (attempt == 0 && index > 0) gridRecycler.scrollToPosition(index)
        val card = gridRecycler.findViewHolderForAdapterPosition(index)?.itemView
        if (card != null && card.requestFocus()) {
            initialFocusDone = true
            return
        }
        if (attempt < 200) gridRecycler.postDelayed({ landOnGrid(attempt + 1) }, 100L)
    }

    private fun filterGrid(catId: String) {
        // Parental control hides flagged titles outright.
        val allItems = if (vm.repo.lockAdult) {
            this.allItems.filterNot {
                (it as? Movie)?.adult == true || (it as? Series)?.adult == true
            }
        } else {
            this.allItems
        }

        val byCategory = when (catId) {
            "favorites" -> allItems.filter { vm.repo.isFavorite(itemId(it)) }
            "recent" -> {
                // Most recently watched first, whatever sort is chosen.
                val byId = allItems.associateBy { itemId(it) }
                // An episode counts towards its series.
                vm.repo.getHistory().mapNotNull { byId[it.seriesId.ifBlank { it.id }] ?: byId[it.id] }.distinct()
            }
            else -> allItems.filter {
                when (it) {
                    is Movie -> it.categoryId == catId
                    is Series -> it.categoryId == catId
                    else -> true
                }
            }
        }

        val keepOrder = catId == "recent"
        filteredItems = (if (keepOrder) ({ l: List<Any> -> l }) else ::applySort)(
            if (searchQuery.isBlank()) {
                byCategory
            } else {
                byCategory.filter { itemName(it).contains(searchQuery, ignoreCase = true) }
            }
        )

        movieAdapter.submitList(filteredItems)

        emptyLayout.visibility = if (filteredItems.isEmpty()) View.VISIBLE else View.GONE
        // The category list arrives with the channels, seconds before the
        // titles themselves; until they are in, say so rather than claim
        // the category is empty.
        val stillLoading = allItems.isEmpty() && catId != "favorites" && catId != "recent"
        emptyText.text = if (stillLoading) "Loading..." else "No Contents"
        gridRecycler.visibility = if (filteredItems.isEmpty()) View.INVISIBLE else View.VISIBLE

        // Header tracks the visible category and its count.
        val unit = if (kind == "movies") "Movies" else "Series"
        subtitle.text = "Total $unit: ${filteredItems.size}"
    }

    private fun itemId(item: Any): String = when (item) {
        is Movie -> item.id
        is Series -> item.id
        is Channel -> item.id
        else -> ""
    }

    private fun itemName(item: Any): String = when (item) {
        is Movie -> item.name
        is Series -> item.name
        is Channel -> item.name
        else -> ""
    }

}
