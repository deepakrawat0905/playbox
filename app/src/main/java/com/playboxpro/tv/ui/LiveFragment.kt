package com.playboxpro.tv.ui

import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.playboxpro.tv.MainActivity
import com.playboxpro.tv.R
import com.playboxpro.tv.model.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class LiveFragment : Fragment(R.layout.fragment_live) {
    private companion object {
        /** STB+ posts the category load 0x1f4ms after the last rail key. */
        const val CATEGORY_LOAD_DEBOUNCE_MS = 500L

        const val RECENT_ADDED = "recent_added"

        /** How many of the newest channels the Recently Added row holds. */
        const val RECENT_ADDED_LIMIT = 100
    }

    private val vm: AppViewModel by activityViewModels()

    private lateinit var liveTitle: TextView
    private lateinit var liveSubtitle: TextView
    private lateinit var playlistBadge: TextView
    private lateinit var licenseBadge: TextView
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView

    private lateinit var categoriesRecycler: RecyclerView
    private lateinit var channelsRecycler: RecyclerView

    private lateinit var previewPlayerView: PlayerView
    private lateinit var previewLogo: ImageView
    private lateinit var previewName: TextView
    private lateinit var previewCategory: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnFavorite: Button
    private lateinit var btnGuide: Button
    private lateinit var epgGuideInfo: TextView

    // STB+ info-panel pieces
    private lateinit var menuPill: View
    private lateinit var previewOverlayName: TextView
    private lateinit var previewError: TextView
    private lateinit var previewShutter: View
    private lateinit var tvStartTime: TextView
    private lateinit var tvEndTime: TextView
    private lateinit var epgProgress: ProgressBar
    private lateinit var emptyFavorites: View
    private lateinit var emptyIcon: ImageView
    private lateinit var emptyTitle: TextView
    private lateinit var emptyHint: TextView

    private var previewPlayer: ExoPlayer? = null
    private var allChannels: List<Channel> = emptyList()
    private var filteredChannels: List<Channel> = emptyList()
    private var selectedCategory: String = ""
    private var initialCategoryDone = false

    /** True once this particular view has been given its first focus. */
    private var viewLanded = false

    /** True while the highlight lives in the channel list rather than the rail. */
    private var channelColumnActive = false

    /** Set when a fresh view should open on the first channel (STB+ default). */
    private var landOnChannels = false

    /** False until the user first moves into the rail; no category is lit before. */
    private var railTouched = false

    /**
     * True from the moment a fresh view asks for the first channel until that
     * focus actually lands. Android hands the very first focus to the first
     * focusable row - Favorites on the rail - before our request runs, and
     * that must not count as the user choosing Favorites.
     */
    private var landingPending = false

    private fun landOnFirstChannel(attempt: Int = 0) {
        if (!isAdded) { landingPending = false; return }
        val row = channelsRecycler.findViewHolderForAdapterPosition(0)?.itemView
        if (row != null && row.requestFocus()) {
            landingPending = false
            return
        }
        if (attempt < 25) {
            channelsRecycler.postDelayed({ landOnFirstChannel(attempt + 1) }, 40L)
        } else {
            landingPending = false
            focusCategoryRow(categoryAdapter.indexOf(selectedCategory).coerceAtLeast(0))
        }
    }
    private var currentChannel: Channel? = null
    private val previewDebounceHandler = Handler(Looper.getMainLooper())
    private var previewRunnable: Runnable? = null
    private val categoryLoadHandler = Handler(Looper.getMainLooper())
    private var categoryLoadRunnable: Runnable? = null

    private lateinit var categoryAdapter: CategoryRailAdapter
    private lateinit var channelAdapter: ChannelRowAdapter

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 15000)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLanded = false
        leavingForPlayer = false
        channelColumnActive = false

        liveTitle = view.findViewById(R.id.liveTitle)
        liveSubtitle = view.findViewById(R.id.liveSubtitle)
        playlistBadge = view.findViewById(R.id.playlistBadge)
        licenseBadge = view.findViewById(R.id.licenseBadge)
        clockTime = view.findViewById(R.id.clockTime)
        clockDate = view.findViewById(R.id.clockDate)

        categoriesRecycler = view.findViewById(R.id.liveCategoriesRecycler)
        channelsRecycler = view.findViewById(R.id.liveChannelsRecycler)

        previewPlayerView = view.findViewById(R.id.livePreviewPlayer)
        previewLogo = view.findViewById(R.id.previewLogo)
        previewName = view.findViewById(R.id.previewName)
        previewCategory = view.findViewById(R.id.previewCategory)
        btnPlay = view.findViewById(R.id.btnLivePlay)
        btnFavorite = view.findViewById(R.id.btnLiveFavorite)
        btnGuide = view.findViewById(R.id.btnLiveGuide)
        epgGuideInfo = view.findViewById(R.id.epgGuideInfo)

        menuPill = view.findViewById(R.id.menuPill)
        previewOverlayName = view.findViewById(R.id.previewOverlayName)
        previewError = view.findViewById(R.id.previewError)
        previewShutter = view.findViewById(R.id.previewShutter)
        tvStartTime = view.findViewById(R.id.tvStartTime)
        tvEndTime = view.findViewById(R.id.tvEndTime)
        epgProgress = view.findViewById(R.id.epgProgress)
        emptyFavorites = view.findViewById(R.id.layoutEmptyFavorites)
        emptyIcon = view.findViewById(R.id.emptyIcon)
        emptyTitle = view.findViewById(R.id.emptyTitle)
        emptyHint = view.findViewById(R.id.emptyHint)

        updateClock()
        clockHandler.postDelayed(clockRunnable, 15000)

        val account = vm.repo.account()
        playlistBadge.text = account?.name?.takeIf { it.isNotBlank() } ?: "Playlist"
        playlistBadge.visibility = View.VISIBLE

        initPreviewPlayer()
        setupAdapters()

        btnPlay.setOnClickListener {
            currentChannel?.let { openFullScreen(it) }
        }

        btnFavorite.setOnClickListener {
            currentChannel?.let { ch ->
                val isFav = vm.toggleFavorite(ch.id)
                btnFavorite.text = if (isFav) "★" else "☆"
                channelAdapter.refresh()
            }
        }

        btnGuide.setOnClickListener {
            showEpgGuideDialog()
        }

        view.findViewById<View>(R.id.previewClickOverlay).setOnClickListener {
            btnPlay.performClick()
        }

        // Live TV needs only the channels; movies and series load when wanted.
        vm.load(catalogue = false)

        // Back online: the preview picks up again by itself.
        viewLifecycleOwner.lifecycleScope.launch {
            com.playboxpro.tv.api.NetworkWatch.online.collect { up ->
                if (up && isAdded && previewError.visibility == View.VISIBLE) { previewRetries = 0; reconnectPreview() }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.library.collectLatest { lib ->
                    allChannels = lib.channels
                    setupCategories(lib)
                    filterChannels(selectedCategory)
                    keepChannelFocus()
                }
            }
        }

        // A failed load is reported after the (empty) library has already
        // been drawn; redraw so the failure message replaces the blank list.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.error.collectLatest { err ->
                    if (err.isNotBlank() && allChannels.isEmpty()) filterChannels(selectedCategory)
                }
            }
        }
    }

    /** STB+ shows one line: "Wednesday, 9 September 2026 |  5:52 pm". */
    private fun updateClock() {
        val fmt = SimpleDateFormat("EEEE, d MMMM yyyy  |  h:mm a", Locale.getDefault())
        clockTime.text = fmt.format(Date())
    }

    private fun initPreviewPlayer() {
        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; PlayBoxPro TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(25000)

        liveSource = LiveStreamSource.Factory(httpDataSourceFactory)
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(requireContext(), TolerantExtractorsFactory())
            .setDataSourceFactory(liveSource)

        val renderers = LivePlayerOptions.renderers(requireContext())

        previewPlayer = ExoPlayer.Builder(requireContext(), renderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().also { player ->
                previewPlayerView.player = player
                // Watching from the preview counts as watching: no screensaver.
                previewPlayerView.keepScreenOn = true
                LivePlayerOptions.styleSubtitles(previewPlayerView)
                VideoFit.attach(previewPlayerView, player) { vm.repo.liveAspect == "auto" }
                player.playWhenReady = true
                player.repeatMode = Player.REPEAT_MODE_OFF

                // STB+ overlays "Stream Offline" on the preview when a
                // channel will not open, instead of showing a blank box.
                player.addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        if (!isAdded) return
                        // A failed audio track: carry on with another one, no "offline".
                        if (previewAudioGuard.onError(error)) { previewAudioGuard.apply(player); player.prepare(); return }
                        previewError.text = if (com.playboxpro.tv.api.NetworkWatch.isOnline) "Stream Offline" else com.playboxpro.tv.api.NetworkWatch.NO_INTERNET
                        previewError.visibility = View.VISIBLE
                        // Offline: wait for the connection (see the watcher), no retries.
                        if (!com.playboxpro.tv.api.NetworkWatch.isOnline) return
                        // Try the channel again a few times, waiting a little longer each time.
                        if (previewRetries < 5) { previewRetries++; scheduleReconnect(2000L * previewRetries) }
                    }

                    override fun onRenderedFirstFrame() {
                        if (isAdded) previewShutter.visibility = View.GONE
                    }

                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        if (isAdded) LivePlayerOptions.restore(player, LivePlayerOptions.liveStore(vm.repo, previewingId.ifBlank { null }))
                        if (isAdded) previewAudioGuard.apply(player)
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        if (isAdded) previewPlayerView.post {
                            LivePlayerOptions.applyAspect(previewPlayerView, player, vm.repo.liveAspect)
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (!isAdded) return
                        if (state == Player.STATE_READY) {
                            previewError.visibility = View.GONE
                            previewRetries = 0
                        }
                        // The provider closed the live stream: pick it up again.
                        if (state == Player.STATE_ENDED) scheduleReconnect(1000L)
                    }
                })
            }
    }

    private fun setupAdapters() {
        categoriesRecycler.layoutManager = LinearLayoutManager(requireContext())
        categoryAdapter = CategoryRailAdapter(
            items = emptyList(),
            // STB+: OK on a category opens it and drops straight onto its
            // first channel.
            onClick = { cat ->
                categoryLoadRunnable?.let { categoryLoadHandler.removeCallbacks(it) }
                railTouched = true
                selectedCategory = cat.id
                if (isLocked(cat.id)) {
                    promptUnlock(cat)
                } else {
                    filterChannels(cat.id)
                    if (filteredChannels.isNotEmpty()) channelsRecycler.post { focusChannelRow(0) }
                }
            },
            // STB+: long-press OK locks / unlocks the category behind the PIN.
            onLongPress = { cat -> if (cat.id != "favorites" && cat.id != RECENT_ADDED) promptLockToggle(cat) },
            // Moving through the rail loads each category straight away.
            onFocus = { cat ->
                // While this view is being torn down for the player, Android parks
                // focus on the first rail row. That is not a selection.
                if (landingPending) {
                    // Android's default first focus, not a choice - the channel
                    // landing is about to take it away again.
                    categoryAdapter.clearSelection()
                } else if (channelColumnActive) {
                    // Focus was not moved here by the user: a channel row was
                    // recycled mid-scroll and Android parked focus on the
                    // nearest thing. Hand it straight back.
                    val index = filteredChannels.indexOfFirst { it.id == currentChannel?.id }
                    if (index >= 0) channelsRecycler.post { focusChannelRow(index) }
                } else if (!leavingForPlayer) {
                    railTouched = true
                    selectedCategory = cat.id
                    // STB+ loads a category 500ms after the last UP / DOWN, so
                    // skimming the rail does not rebuild the channel list on
                    // every step - and the rebuild never runs inside the focus
                    // pass that triggered it.
                    categoryLoadRunnable?.let { categoryLoadHandler.removeCallbacks(it) }
                    val r = Runnable { if (isAdded && selectedCategory == cat.id) filterChannels(cat.id) }
                    categoryLoadRunnable = r
                    categoryLoadHandler.postDelayed(r, CATEGORY_LOAD_DEBOUNCE_MS)
                }
            },
            // RIGHT always enters the channel list at the top.
            onRightKey = {
                // Pressing RIGHT inside the debounce window must not enter the
                // previous category's list: load the pending one now.
                categoryLoadRunnable?.let {
                    categoryLoadHandler.removeCallbacks(it)
                    categoryLoadRunnable = null
                    it.run()
                }
                val cat = categoryAdapter.categoryAt(categoryAdapter.indexOf(selectedCategory))
                if (cat != null && isLocked(cat.id)) {
                    promptUnlock(cat)
                    true
                } else if (filteredChannels.isEmpty()) {
                    false
                } else {
                    focusChannelRow(0); true
                }
            },
            onVerticalKey = { position, delta ->
                val target = position + delta
                if (target in 0 until categoryAdapter.itemCount) focusCategoryRow(target)
                true
            }
        )
        categoriesRecycler.adapter = categoryAdapter

        channelsRecycler.layoutManager = LinearLayoutManager(requireContext())
        channelAdapter = ChannelRowAdapter(
            items = emptyList(),
            isFav = { vm.repo.isFavorite(it) },
            onFocusChange = { channel ->
                channelColumnActive = true
                selectChannel(channel)
            },
            // STB+: OK on the channel already playing in the preview goes full
            // screen; OK on anything else starts it in the preview first.
            onClick = { channel -> onChannelOk(channel) },
            onOptionsKey = {
                LivePlayerOptions.showOptions(
                    requireContext(), previewPlayer, previewPlayerView,
                    LivePlayerOptions.liveStore(vm.repo, previewingId.ifBlank { null })
                )
            },
            // STB+ favourites a channel on long press rather than with a button.
            onLongPress = { channel ->
                val isFav = vm.toggleFavorite(channel.id)
                android.widget.Toast.makeText(
                    requireContext(),
                    if (isFav) "Added to favorites" else "Removed from favorites",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                setupCategories(vm.library.value)
                channelAdapter.refresh()
            },
            // LEFT returns to the category that is actually selected.
            onLeftKey = {
                channelColumnActive = false
                val index = categoryAdapter.indexOf(selectedCategory)
                if (index < 0) false else { focusCategoryRow(index); true }
            },
            // UP / DOWN walk the list by position and never leave it - at
            // either end the highlight simply stays, as in STB+.
            onVerticalKey = { position, delta ->
                val target = position + delta
                if (target in filteredChannels.indices) focusChannelRow(target)
                true
            }
        )
        channelsRecycler.adapter = channelAdapter
    }

    private fun recentlyAddedChannels(channels: List<Channel>): List<Channel> =
        channels.asSequence()
            .filter { it.added > 0 }
            .sortedByDescending { it.added }
            .take(RECENT_ADDED_LIMIT)
            .toList()

    private fun setupCategories(lib: Library) {
        val categories = buildList {
            // STB+ lists Favorites first and always, even when it is empty -
            // selecting it is how you reach the "long press to add" prompt.
            val favCount = lib.channels.count { vm.repo.isFavorite(it.id) }
            add(Category("favorites", "Favorites", "live", favCount))
            // The newest channels the provider added, straight under
            // Favorites so they are always at the top of the list. Only
            // playlists that date their channels (Xtream) get this row.
            val recent = recentlyAddedChannels(lib.channels)
            if (recent.isNotEmpty()) {
                add(Category(RECENT_ADDED, "Recently Added", "live", recent.size))
            }
            // Provider categories are shown uppercase with a pipe separator,
            // the way STB+ renders them ("SPORTS | CRICKET"). Only the display
            // name changes - ids stay untouched so filtering still matches.
            // Categories hidden from Settings are dropped here.
            val hidden = vm.repo.hiddenCategories("live")
            addAll(
                lib.liveCategories
                    .filter { it.id !in hidden }
                    .map { it.copy(name = formatCategoryName(it.name)) }
            )
        }

        // Open on the first real category, never on Favorites - it is empty
        // for most users. Waits for a real category to exist because the
        // library emits once while still loading.
        val firstRealCategory = categories.firstOrNull { it.id != "favorites" && it.id != RECENT_ADDED }?.id

        if (categories.none { it.id == selectedCategory }) {
            selectedCategory = firstRealCategory ?: categories.firstOrNull()?.id.orEmpty()
        }

        categoryAdapter.submitList(categories, if (railTouched) selectedCategory else "")

        // Every fresh view needs landing, not just the first: coming back from
        // the full-screen player rebuilds this view, and without an explicit
        // target Android hands focus to the first row - Favorites - which
        // then loads an empty list over whatever was playing.
        if (!viewLanded && firstRealCategory != null) {
            viewLanded = true
            if (!initialCategoryDone) {
                initialCategoryDone = true
                selectedCategory = firstRealCategory
            }
            // STB+ opens with the first channel highlighted and no category
            // lit up; the rail only shows a selection once you move into it.
            // The channel focus is requested by filterChannels() once the
            // list exists, so nothing is asked of the rail here.
            landOnChannels = true
        }
    }

    /**
     * Focuses a category row once the rail has laid out. The activity focuses
     * the content host as soon as the fragment appears, which lands on row 0,
     * so this has to retry until the intended row exists and takes focus.
     */
    // ------------------------------------------------------------------
    // Category lock (STB+ long-press on a category)
    // ------------------------------------------------------------------

    /** Categories unlocked with the PIN since the app opened. */
    private val sessionUnlocked = mutableSetOf<String>()

    private fun isLocked(catId: String): Boolean =
        catId in vm.repo.lockedCategories("live") && catId !in sessionUnlocked

    private fun promptLockToggle(cat: Category) {
        val locked = cat.id in vm.repo.lockedCategories("live")
        StbDialogs.showPin(
            requireContext(),
            if (locked) "Remove Category Lock" else "Lock Category",
            if (locked) "Enter PIN to remove the lock." else "Enter PIN to lock this category."
        ) { pin ->
            if (pin != vm.repo.effectivePin()) {
                toast("Incorrect PIN")
                return@showPin
            }
            val set = vm.repo.lockedCategories("live").toMutableSet()
            if (locked) set.remove(cat.id) else { set.add(cat.id); sessionUnlocked.remove(cat.id) }
            vm.repo.setLockedCategories("live", set)
            toast(if (locked) "Category Unlocked Successfully" else "Category Locked Successfully")
            if (selectedCategory == cat.id) filterChannels(cat.id)
        }
    }

    private fun promptUnlock(cat: Category) {
        StbDialogs.showPin(requireContext(), "Locked Category", "Enter PIN to open this category.") { pin ->
            if (pin != vm.repo.effectivePin()) {
                toast("Incorrect PIN")
                return@showPin
            }
            sessionUnlocked.add(cat.id)
            filterChannels(cat.id)
            if (filteredChannels.isNotEmpty()) channelsRecycler.post { focusChannelRow(0) }
        }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------
    // BACK, the STB+ way: channels -> categories -> menu. Leaving the app is
    // the menu's Exit item, never a stray BACK.
    // ------------------------------------------------------------------

    /**
     * Called by MainActivity for BACK while this screen is showing (the
     * activity's own callback is registered later in the lifecycle than a
     * fragment's would be, so it has to delegate rather than be overridden).
     * Returns true when handled.
     */
    fun handleBack(): Boolean {
        if (!isAdded) return false
        if (channelColumnActive) {
            channelColumnActive = false
            focusCategoryRow(categoryAdapter.indexOf(selectedCategory).coerceAtLeast(0))
            return true
        }
        (activity as? MainActivity)?.openDrawer()
        return true
    }

    /**
     * Brings a row into the laid-out range. A neighbour scrolls in smoothly
     * like a native key scroll would; a far-off row (coming back from the
     * player) jumps, because smooth-scrolling a thousand rows takes seconds.
     */
    private fun reveal(list: RecyclerView, index: Int) {
        val lm = list.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first < 0) {
            list.scrollToPosition(index)
            return
        }

        // One instant, deterministic layout with the row at the edge it is
        // arriving from - the same picture a native key scroll paints. A
        // smooth scroll here fights the layout pass and the row that was
        // just focused gets recycled from under the highlight.
        when {
            index > last -> {
                val rowHeight = list.getChildAt(0)?.height ?: 0
                val offset = (list.height - list.paddingBottom - rowHeight).coerceAtLeast(0)
                lm.scrollToPositionWithOffset(index, offset)
            }
            index < first -> lm.scrollToPositionWithOffset(index, 0)
            else -> list.scrollToPosition(index)
        }
    }

    private fun focusCategoryRow(index: Int, attempt: Int = 0) {
        if (!isAdded) return

        val row = categoriesRecycler.findViewHolderForAdapterPosition(index)?.itemView
        if (row != null && row.requestFocus()) return

        if (attempt == 0) reveal(categoriesRecycler, index)

        if (attempt < 25) {
            categoriesRecycler.postDelayed({ focusCategoryRow(index, attempt + 1) }, 40L)
        }
    }

    /** Same retry, for entering the channel column. */
    /** The channel the preview box is actually tuned to, once it is up. */
    private var previewingId: String = ""

    /** The channel on air in the preview, for reconnecting it. */
    private var previewChannel: Channel? = null
    /** Keeps live TS connections going when the server closes them (see LiveStreamSource). */
    private lateinit var liveSource: LiveStreamSource.Factory
    private var previewRetries = 0
    private val previewAudioGuard = LivePlayerOptions.AudioGuard()
    private val previewReconnect = Runnable { reconnectPreview() }

    /**
     * A live stream should never end. When the provider drops the
     * connection, or a portal link expires, the preview tunes the same
     * channel again with a fresh link instead of stopping.
     */
    private fun reconnectPreview() {
        val ch = previewChannel ?: return
        if (!isResumed || leavingForPlayer || previewingId != ch.id) return
        val url = (activity as? MainActivity)?.streamUrlFor(ch) ?: ch.url
        if (url.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val real = runCatching { vm.repo.resolveStream(url) }.getOrNull()
            if (!isAdded || previewingId != ch.id || real.isNullOrBlank()) return@launch
            liveSource.prepareFor(real)
            previewPlayer?.setMediaItem(MediaItem.fromUri(real))
            previewPlayer?.prepare()
            previewPlayer?.play()
        }
    }

    private fun scheduleReconnect(delayMs: Long) {
        previewDebounceHandler.removeCallbacks(previewReconnect)
        previewDebounceHandler.postDelayed(previewReconnect, delayMs)
    }

    private fun onChannelOk(channel: Channel) {
        // The channel already in the preview goes full screen. A live stream
        // spends a fair share of its time re-buffering, so "tuned and not
        // failed" is the test - not a momentary READY.
        val tuned = previewingId == channel.id &&
            previewPlayer?.playbackState.let { it == Player.STATE_READY || it == Player.STATE_BUFFERING } &&
            previewError.visibility != View.VISIBLE
        if (tuned) {
            openFullScreen(channel)
            return
        }

        // Not on air yet: tune straight away rather than waiting out the
        // debounce, the way STB+ starts the preview on the first OK.
        previewRunnable?.let { previewDebounceHandler.removeCallbacks(it) }
        previewingId = ""
        selectChannel(channel)
        previewRunnable?.let {
            previewDebounceHandler.removeCallbacks(it)
            it.run()
        }
    }

    private fun openFullScreen(channel: Channel) {
        val index = filteredChannels.indexOfFirst { it.id == channel.id }
        if (index < 0) return
        returnFromPlayer = true
        leavingForPlayer = true
        previewPlayer?.pause()
        (activity as? MainActivity)?.playLive(filteredChannels, index)
    }

    /**
     * When the full-screen player has moved to another channel, land back
     * on it here so the list, preview and info panel all agree with what
     * was just on screen.
     */
    /** Set on the way into the player; consumed by the first sync back. */
    private var returnFromPlayer = false

    /** True from the hand-off to the player until this view is rebuilt. */
    private var leavingForPlayer = false

    private fun syncFromLiveSession(): Boolean {
        if (!returnFromPlayer) return false
        val channel = vm.liveSession.getOrNull(vm.liveIndex.value) ?: return false
        val index = filteredChannels.indexOfFirst { it.id == channel.id }
        if (index < 0) return false

        returnFromPlayer = false
        channelAdapter.submitList(filteredChannels, channel.id)
        selectChannel(filteredChannels[index])
        channelsRecycler.post { focusChannelRow(index) }
        return true
    }

    /**
     * The playlist is published more than once while the app opens, and
     * each refill of the list recycles the row under the remote - which
     * leaves the highlight painted but the remote's focus gone, so OK did
     * nothing until the user moved. Put focus back on the same row.
     */
    private fun keepChannelFocus() {
        channelsRecycler.post {
            if (!isAdded || !channelColumnActive || leavingForPlayer) return@post
            // Only when focus is really gone - never take it from the menu
            // drawer or a dialog the user has opened meanwhile.
            val focused = activity?.currentFocus
            if (focused != null && focused.isShown) return@post
            val index = filteredChannels.indexOfFirst { it.id == currentChannel?.id }
            if (index >= 0) focusChannelRow(index)
        }
    }

    private fun focusChannelRow(index: Int, attempt: Int = 0) {
        if (!isAdded) return

        val row = channelsRecycler.findViewHolderForAdapterPosition(index)?.itemView
        if (row != null && row.requestFocus()) return

        // Not laid out yet - usually because it is scrolled off screen.
        if (attempt == 0) reveal(channelsRecycler, index)

        if (attempt < 25) {
            channelsRecycler.postDelayed({ focusChannelRow(index, attempt + 1) }, 40L)
        }
    }

    private fun filterChannels(catId: String) {
        val visible = if (vm.repo.lockAdult) allChannels.filter { !it.adult } else allChannels

        filteredChannels = when {
            isLocked(catId) -> emptyList()
            catId == "favorites" -> visible.filter { vm.repo.isFavorite(it.id) }
            catId == RECENT_ADDED -> recentlyAddedChannels(visible)
            else -> visible.filter { it.categoryId == catId }
        }

        channelAdapter.submitList(filteredChannels, currentChannel?.id ?: "")
        // STB+ counts the category on screen, not the whole playlist.
        liveSubtitle.text = "Total Channels: ${filteredChannels.size}"
        // A new category always opens at the top. The layout manager would
        // otherwise keep the previous list's scroll offset, leaving row 0 off
        // screen where focus cannot reach it.
        channelsRecycler.scrollToPosition(0)

        // STB+ swaps the channel column for a prompt when Favorites is empty.
        // The list must stay INVISIBLE rather than GONE: the info panel is
        // constrained to its trailing edge, and collapsing it would drag the
        // panel left over the message.
        val locked = isLocked(catId)
        if (locked) {
            // Nothing from a locked category may keep playing behind the prompt.
            previewRunnable?.let { previewDebounceHandler.removeCallbacks(it) }
            previewPlayer?.stop()
            previewShutter.visibility = View.VISIBLE
            previewingId = ""
        }
        // Nothing at all could be loaded (no internet, provider down): say
        // so instead of an empty screen reading "Total Channels: 0".
        val loadFailed = allChannels.isEmpty() && vm.error.value.isNotBlank()
        // A first download of a large playlist can outlast the splash.
        val stillLoading = allChannels.isEmpty() && !loadFailed
        val showPrompt = locked || loadFailed || stillLoading || (catId == "favorites" && filteredChannels.isEmpty())
        if (showPrompt) {
            emptyIcon.setImageResource(
                if (locked) R.drawable.ic_lock else if (loadFailed || stillLoading) R.drawable.ic_info else R.drawable.ic_star_filled
            )
            emptyTitle.text = when {
                locked -> "This category is locked"
                loadFailed -> "Unable to load the playlist"
                stillLoading -> "Loading the playlist..."
                else -> "No favorite channels found"
            }
            emptyHint.text = when {
                locked -> "Press OK and enter your PIN to open it"
                // A portal that does not know this MAC says so; show its reason.
                loadFailed && vm.error.value.contains("not registered", true) -> vm.error.value
                loadFailed -> "Check the internet connection, then open the app again"
                stillLoading -> "This can take a moment for a large playlist"
                else -> "Long press a TV channel to add to favorites"
            }
        }
        emptyFavorites.visibility = if (showPrompt) View.VISIBLE else View.GONE
        channelsRecycler.visibility = if (showPrompt) View.INVISIBLE else View.VISIBLE

        val synced = filteredChannels.isNotEmpty() && syncFromLiveSession()
        if (!synced && filteredChannels.isNotEmpty()) {
            selectChannel(filteredChannels.first())
        }

        if (landOnChannels) {
            landOnChannels = false
            if (synced) {
                // Coming back from the player: already on the right channel.
            } else if (filteredChannels.isNotEmpty()) {
                landingPending = true
                channelsRecycler.post { landOnFirstChannel() }
            } else {
                // Nothing to land on (empty category): fall back to the rail.
                val index = categoryAdapter.indexOf(selectedCategory).coerceAtLeast(0)
                categoriesRecycler.post { focusCategoryRow(index) }
            }
        }
    }

    private fun selectChannel(channel: Channel) {
        currentChannel = channel

        // Info panel, laid out as STB+ does: programme title, the time range
        // on the left with its label on the right, then progress and synopsis.
        previewName.text = channel.name
        previewOverlayName.text = channel.name
        // STB+ tv_title / tv_dec: programme title and synopsis, "No
        // Information" until the guide answers (or when it has nothing).
        previewCategory.text = "No Information"
        tvStartTime.text = "00:00"
        tvEndTime.text = "00:00"
        epgProgress.progress = 0
        epgGuideInfo.text = "No Information"
        loadEpgInfo(channel)

        previewError.visibility = View.GONE

        btnFavorite.text = if (vm.repo.isFavorite(channel.id)) "★" else "☆"

        ImageLoader.loadLogo(previewLogo, channel.logo)

        // Debounce stream loading in Live Preview surface
        previewRunnable?.let { previewDebounceHandler.removeCallbacks(it) }

        // Landing back on the channel that is already on air must not
        // restart it - otherwise the player is buffering when OK arrives and
        // STB+'s "only a playing channel goes full screen" rule bounces it.
        val alreadyOnAir = previewingId == channel.id &&
            previewPlayer?.playbackState.let { it == Player.STATE_READY || it == Player.STATE_BUFFERING } &&
            previewError.visibility != View.VISIBLE
        if (alreadyOnAir) return

        val r = Runnable {
            try {
                val previewUrl = (activity as? MainActivity)?.streamUrlFor(channel) ?: channel.url
                if (previewUrl.isNotBlank() && isAdded) {
                    previewingId = channel.id
                    previewChannel = channel
                    previewAudioGuard.itemId = channel.id
                    previewRetries = 0
                    previewDebounceHandler.removeCallbacks(previewReconnect)
                    // Clear the previous channel's picture first (STB+ U()).
                    previewPlayer?.stop()
                    previewPlayer?.let { LivePlayerOptions.resetTracks(it) }
                    previewShutter.visibility = View.VISIBLE
                    previewPlayer?.playWhenReady = true
                    // Portal channels get a fresh stream link first.
                    val tunedId = channel.id
                    viewLifecycleOwner.lifecycleScope.launch {
                        val real = runCatching { vm.repo.resolveStream(previewUrl) }.getOrNull()
                        if (!isAdded || previewingId != tunedId || real.isNullOrBlank()) return@launch
                        liveSource.prepareFor(real)
                        previewPlayer?.setMediaItem(MediaItem.fromUri(real))
                        previewPlayer?.prepare()
                        previewPlayer?.play()
                    }
                }
            } catch (_: Exception) {}
        }
        previewRunnable = r
        // STB+ waits 500ms after the last UP / DOWN before tuning the preview.
        previewDebounceHandler.postDelayed(r, 500)
    }

    private var epgJob: kotlinx.coroutines.Job? = null

    /**
     * Fills the info panel from the guide once the highlight has settled on
     * a channel (same 500ms STB+ waits before tuning the preview), so a fast
     * scroll through the list does not fire a request per row.
     */
    private fun loadEpgInfo(channel: Channel) {
        epgJob?.cancel()
        epgJob = viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(500)
            val programmes = vm.repo.epgFor(channel, full = false)
            if (currentChannel?.id != channel.id || !isAdded) return@launch
            val now = System.currentTimeMillis()
            val current = programmes.firstOrNull { now in it.start until it.end }
                ?: return@launch
            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            tvStartTime.text = fmt.format(java.util.Date(current.start))
            tvEndTime.text = fmt.format(java.util.Date(current.end))
            previewCategory.text = current.title
            epgGuideInfo.text = current.description.ifBlank { "No Information" }
            val span = (current.end - current.start).coerceAtLeast(1L)
            epgProgress.progress = (((now - current.start) * 100) / span).toInt().coerceIn(0, 100)
        }
    }

    private fun showEpgGuideDialog() {
        val channel = currentChannel ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val programmes = vm.repo.epgFor(channel, full = true)
            if (!isAdded) return@launch
            StbDialogs.showEpgGuide(requireContext(), channel, programmes)
        }
    }

    override fun onResume() {
        super.onResume()
        if (filteredChannels.isNotEmpty()) syncFromLiveSession()
        previewPlayer?.play()
    }

    override fun onPause() {
        super.onPause()
        previewPlayer?.pause()
        previewDebounceHandler.removeCallbacks(previewReconnect)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clockHandler.removeCallbacks(clockRunnable)
        previewDebounceHandler.removeCallbacksAndMessages(null)
        categoryLoadHandler.removeCallbacksAndMessages(null)
        previewPlayer?.release()
        previewPlayer = null
    }
}
