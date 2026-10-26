package com.playboxpro.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.playboxpro.tv.MainActivity
import com.playboxpro.tv.R
import com.playboxpro.tv.model.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class HomeFragment : Fragment(R.layout.fragment_home) {
    private val vm: AppViewModel by activityViewModels()

    private lateinit var greetingTitle: TextView
    private lateinit var greetingSubtitle: TextView
    private lateinit var playlistBadge: TextView
    private lateinit var licenseBadge: TextView
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView
    private lateinit var heroContainer: FrameLayout
    private lateinit var rowsContainer: LinearLayout

    // Hero banner persistent view elements
    private var bannerView: View? = null
    private var bannerBackdrop: ImageView? = null
    private var bannerKickerIcon: TextView? = null
    private var bannerKickerText: TextView? = null
    private var bannerTitle: TextView? = null
    private var bannerDotsContainer: LinearLayout? = null

    private var featuredItems: List<Any> = emptyList()
    private var featuredIndex = 0
    private var isHeroFocused = false

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 15000)
        }
    }

    private val bannerHandler = Handler(Looper.getMainLooper())
    private val bannerRunnable = object : Runnable {
        override fun run() {
            if (isAdded && featuredItems.size > 1 && !isHeroFocused) {
                featuredIndex = (featuredIndex + 1) % featuredItems.size
                displayHeroSlide(featuredIndex)
            }
            bannerHandler.postDelayed(this, 8000)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        greetingTitle = view.findViewById(R.id.greetingTitle)
        greetingSubtitle = view.findViewById(R.id.greetingSubtitle)
        playlistBadge = view.findViewById(R.id.playlistBadge)
        licenseBadge = view.findViewById(R.id.licenseBadge)
        clockTime = view.findViewById(R.id.clockTime)
        clockDate = view.findViewById(R.id.clockDate)
        heroContainer = view.findViewById(R.id.heroBannerContainer)
        rowsContainer = view.findViewById(R.id.homeRowsContainer)

        updateGreeting()
        updateClock()
        clockHandler.postDelayed(clockRunnable, 15000)
        bannerHandler.postDelayed(bannerRunnable, 8000)

        // Active account badge
        val account = vm.repo.account()
        if (account != null && account.name.isNotBlank()) {
            playlistBadge.text = "≡  ${account.name}"
            playlistBadge.visibility = View.VISIBLE
        } else {
            playlistBadge.text = "≡  Default"
            playlistBadge.visibility = View.VISIBLE
        }

        licenseBadge.text = "⏱  Active"

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.library.collectLatest { lib ->
                    renderHome(lib)
                }
            }
        }

        vm.load()
    }

    private fun updateGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        greetingTitle.text = when {
            hour < 12 -> "Good morning"
            hour < 18 -> "Good afternoon"
            else -> "Good evening"
        }
        greetingSubtitle.text = "Browse"
    }

    private fun updateClock() {
        val now = Date()
        val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val dateFmt = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        clockTime.text = timeFmt.format(now)
        clockDate.text = dateFmt.format(now)
    }

    private fun renderHome(lib: Library) {
        rowsContainer.removeAllViews()

        // 1. Featured items for Hero Carousel (pick up to 8 items)
        val featured = mutableListOf<Any>()
        if (lib.movies.isNotEmpty()) featured.addAll(lib.movies.take(4))
        if (lib.series.isNotEmpty()) featured.addAll(lib.series.take(2))
        if (lib.channels.isNotEmpty()) featured.addAll(lib.channels.take(2))

        featuredItems = featured
        if (featuredItems.isNotEmpty()) {
            if (featuredIndex >= featuredItems.size) featuredIndex = 0
            ensureHeroBannerLayout()
            displayHeroSlide(featuredIndex)
        } else {
            heroContainer.removeAllViews()
            bannerView = null
        }

        // 2. Row: Recent channels (if any)
        val recentIds = vm.repo.recentChannels().toSet()
        if (recentIds.isNotEmpty()) {
            val recentChannels = lib.channels.filter { it.id in recentIds || it.url in recentIds }.take(10)
            if (recentChannels.isNotEmpty()) {
                addChannelRow("Recent channels", recentChannels.size, recentChannels)
            }
        }

        // 3. Row: Continue Watching (if any)
        val cw = vm.continueWatching.value
        if (cw.isNotEmpty()) {
            addWideRow("Continue watching", cw.size, cw)
        }

        // 4. Row: Live now
        if (lib.channels.isNotEmpty()) {
            addChannelRow("Live now", lib.channels.size, lib.channels.take(40))
        }

        // 4. Row: New movies
        if (lib.movies.isNotEmpty()) {
            addMovieRow("New movies", lib.movies.size, lib.movies.take(40))
        }

        // 5. Row: New series
        if (lib.series.isNotEmpty()) {
            addMovieRow("New series", lib.series.size, lib.series.take(40))
        }

        // 6. Row: Favorites (if any)
        val favs = vm.favorites.value
        if (favs.isNotEmpty()) {
            val favItems = mutableListOf<Any>()
            favs.forEach { id ->
                val ch = lib.channels.firstOrNull { it.id == id }
                val m = lib.movies.firstOrNull { it.id == id }
                val s = lib.series.firstOrNull { it.id == id }
                if (ch != null) favItems.add(ch)
                else if (m != null) favItems.add(m)
                else if (s != null) favItems.add(s)
            }
            if (favItems.isNotEmpty()) {
                addCardsRow("Favorites", favItems.size, favItems)
            }
        }
    }

    private fun ensureHeroBannerLayout() {
        if (bannerView != null && heroContainer.childCount > 0) return

        heroContainer.removeAllViews()
        val bv = LayoutInflater.from(requireContext()).inflate(R.layout.item_hero_banner, heroContainer, false)
        val root = bv.findViewById<View>(R.id.heroRoot)

        bannerBackdrop = bv.findViewById(R.id.heroBackdrop)
        bannerKickerIcon = bv.findViewById(R.id.heroKickerIcon)
        bannerKickerText = bv.findViewById(R.id.heroKickerText)
        bannerTitle = bv.findViewById(R.id.heroTitle)
        bannerDotsContainer = bv.findViewById(R.id.heroDotsContainer)

        root.setOnFocusChangeListener { _, hasFocus ->
            isHeroFocused = hasFocus
        }

        // Manual D-Pad Navigation: Left/Right to change slides without losing focus
        root.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && featuredItems.size > 1) {
                    featuredIndex = (featuredIndex - 1 + featuredItems.size) % featuredItems.size
                    displayHeroSlide(featuredIndex)
                    return@setOnKeyListener true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && featuredItems.size > 1) {
                    featuredIndex = (featuredIndex + 1) % featuredItems.size
                    displayHeroSlide(featuredIndex)
                    return@setOnKeyListener true
                }
            }
            false
        }

        root.setOnClickListener {
            val currentItem = featuredItems.getOrNull(featuredIndex) ?: return@setOnClickListener
            when (currentItem) {
                is Movie -> (activity as? MainActivity)?.openMovieDetail(currentItem)
                is Series -> (activity as? MainActivity)?.openSeriesDetail(currentItem)
                is Channel -> (activity as? MainActivity)?.openChannel(currentItem)
            }
        }

        heroContainer.addView(bv)
        bannerView = bv
    }

    private fun displayHeroSlide(index: Int) {
        val item = featuredItems.getOrNull(index) ?: return

        when (item) {
            is Movie -> {
                bannerKickerIcon?.text = "🗂 "
                bannerKickerText?.text = "MOVIES"
                bannerTitle?.text = item.name
                val bg = item.backdrop.ifBlank { item.poster }
                bannerBackdrop?.let { ImageLoader.loadBackdrop(it, bg, cornerRadiusDp = 18) }
            }
            is Series -> {
                bannerKickerIcon?.text = "🎬 "
                bannerKickerText?.text = "SERIES"
                bannerTitle?.text = item.name
                val bg = item.backdrop.ifBlank { item.poster }
                bannerBackdrop?.let { ImageLoader.loadBackdrop(it, bg, cornerRadiusDp = 18) }
            }
            is Channel -> {
                bannerKickerIcon?.text = "📺 "
                bannerKickerText?.text = "LIVE"
                bannerTitle?.text = item.name
                bannerBackdrop?.let { ImageLoader.loadBackdrop(it, item.logo, cornerRadiusDp = 18) }
            }
        }

        // Update Pagination Dots in place
        bannerDotsContainer?.let { container ->
            container.removeAllViews()
            for (i in featuredItems.indices) {
                val dot = View(requireContext())
                val isActive = (i == index)
                val w = if (isActive) (22 * resources.displayMetrics.density).toInt() else (6 * resources.displayMetrics.density).toInt()
                val h = (6 * resources.displayMetrics.density).toInt()
                val lp = LinearLayout.LayoutParams(w, h).apply {
                    marginStart = (4 * resources.displayMetrics.density).toInt()
                    marginEnd = (4 * resources.displayMetrics.density).toInt()
                }
                dot.layoutParams = lp
                dot.setBackgroundResource(R.drawable.bg_pill)
                dot.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(if (isActive) R.color.accent else R.color.surface_3)
                )
                container.addView(dot)
            }
        }
    }

    private fun addChannelRow(titleStr: String, count: Int, channels: List<Channel>) {
        val rowView = createRowContainer(titleStr, count)
        val recycler = rowView.findViewById<RecyclerView>(R.id.rowRecycler)

        val adapter = ChannelTileAdapter(channels) { ch ->
            (activity as? MainActivity)?.openChannel(ch)
        }
        recycler.adapter = adapter
        rowsContainer.addView(rowView)
    }

    private fun addMovieRow(titleStr: String, count: Int, items: List<Any>) {
        val rowView = createRowContainer(titleStr, count)
        val recycler = rowView.findViewById<RecyclerView>(R.id.rowRecycler)

        val adapter = MovieCardAdapter(
            items = items,
            getProgress = { id -> vm.repo.getResume(id)?.progress ?: 0f },
            onClick = { item ->
                when (item) {
                    is Movie -> (activity as? MainActivity)?.openMovieDetail(item)
                    is Series -> (activity as? MainActivity)?.openSeriesDetail(item)
                    is Channel -> (activity as? MainActivity)?.openChannel(item)
                }
            }
        )
        recycler.adapter = adapter
        rowsContainer.addView(rowView)
    }

    private fun addCardsRow(titleStr: String, count: Int, items: List<Any>) {
        addMovieRow(titleStr, count, items)
    }

    private fun addWideRow(titleStr: String, count: Int, items: List<ResumeEntry>) {
        val rowView = createRowContainer(titleStr, count)
        val recycler = rowView.findViewById<RecyclerView>(R.id.rowRecycler)

        val adapter = WideCardAdapter(items) { entry ->
            (activity as? MainActivity)?.play(
                url = entry.url,
                title = entry.title,
                poster = entry.poster,
                backdrop = entry.backdrop,
                seriesId = entry.seriesId,
                season = entry.seasonNumber,
                episode = entry.episodeNumber,
                id = entry.id
            )
        }
        recycler.adapter = adapter
        rowsContainer.addView(rowView)
    }

    private fun createRowContainer(titleStr: String, count: Int): View {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.layout_home_row, rowsContainer, false)
        val title = view.findViewById<TextView>(R.id.rowTitle)
        val countText = view.findViewById<TextView>(R.id.rowCount)
        val recycler = view.findViewById<RecyclerView>(R.id.rowRecycler)

        title.text = titleStr
        countText.text = count.toString()

        recycler.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        recycler.clipChildren = false
        recycler.clipToPadding = false

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clockHandler.removeCallbacks(clockRunnable)
        bannerHandler.removeCallbacks(bannerRunnable)
    }
}
