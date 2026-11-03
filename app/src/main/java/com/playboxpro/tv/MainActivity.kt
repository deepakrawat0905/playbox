package com.playboxpro.tv

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import com.playboxpro.tv.data.LicenseGate
import com.playboxpro.tv.model.*
import com.playboxpro.tv.ui.*

class MainActivity : AppCompatActivity() {
    // Phones: the TV layout scaled to fit (see ScreenFit).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(ScreenFit.wrap(newBase))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ScreenFit.immersive(this)
    }

    val vm: AppViewModel by viewModels()

    private lateinit var sidebar: LinearLayout
    private lateinit var brandText: View
    private lateinit var navContainer: LinearLayout
    private lateinit var drawerScrim: View
    private lateinit var contentHost: android.widget.FrameLayout

    // STB+ opens on Live Tv; there is no Home destination in its drawer.
    private var currentDestination = "live"

    /** The drawer is closed until the user asks for it, as in STB+. */
    private var isDrawerOpen = false

    /**
     * Drawer contents, matching the STB+ reference exactly: same seven
     * destinations, same order, same labels, same icons.
     */
    private val navItems = listOf(
        NavItem("live", "Live Tv", R.drawable.ic_nav_livetv),
        NavItem("series", "TV Series", R.drawable.ic_nav_tvseries),
        NavItem("movies", "Movies", R.drawable.ic_nav_movies),
        NavItem("search", "Search", R.drawable.ic_nav_search),
        NavItem("profile", "Profile", R.drawable.ic_nav_profile),
        NavItem("settings", "Settings", R.drawable.ic_nav_settings),
        NavItem("exit", "Exit", R.drawable.ic_nav_exit)
    )

    data class NavItem(val id: String, val label: String, val iconRes: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Never rebuild old screens after Android closed the app in the
        // background: a restored player has no stream behind it. The app
        // always reopens on its start screen instead.
        super.onCreate(null)
        setContentView(R.layout.activity_main)

        sidebar = findViewById(R.id.sidebar)
        brandText = findViewById(R.id.brandText)
        navContainer = findViewById(R.id.navItemsContainer)
        drawerScrim = findViewById(R.id.drawerScrim)
        contentHost = findViewById(R.id.content)
        drawerScrim.setOnClickListener { closeDrawer() }

        setupSidebar()
        ScreenFit.immersive(this)

        // Touch screens reach the side menu by tapping each page's "Menu" label.
        supportFragmentManager.registerFragmentLifecycleCallbacks(object :
            androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(
                fm: androidx.fragment.app.FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?
            ) {
                ScreenFit.makeMenuLabelTappable(v) { openDrawer() }
            }
        }, false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // On Live TV the menu is the last stop: BACK there asks to
                // exit (channels -> categories -> menu -> exit). Anywhere
                // else BACK just puts the menu away.
                if (isDrawerOpen) {
                    val onLive = currentDestination == "live" &&
                        supportFragmentManager.backStackEntryCount == 0
                    if (onLive) open("exit") else closeDrawer()
                    return
                }
                val currentFrag = supportFragmentManager.findFragmentById(R.id.content)
                // First BACK puts the player controls away, the next one leaves.
                if (currentFrag is PlayerFragment && currentFrag.handleBack()) return
                if (currentFrag is PlayerFragment || currentFrag is LivePlayerFragment) {
                    supportFragmentManager.popBackStack()
                    return
                }
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                    return
                }
                if (currentFrag is LiveFragment && currentFrag.handleBack()) {
                    // STB+: channels -> categories -> menu.
                    return
                }
                if (currentDestination == "live") {
                    openDrawer()
                } else if (currentDestination == "activate") {
                    exitApp()
                } else {
                    open("live")
                }
            }
        })

        if (vm.repo.account() != null && vm.repo.isActive()) {
            open("live")
            // Licence / playlist expiry reminders, once the first screen has settled.
            window.decorView.postDelayed({
                if (!isFinishing && vm.repo.isActive()) {
                    SubscriptionNotices.showDueReminders(this, vm.repo, vm.mac, vm.deviceKey)
                }
            }, 4000)
        } else {
            open("activate")
        }

        // A box that stays on for days never passes through the splash
        // again, so the licence is re-confirmed while the app is in front.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // The first look comes shortly after start-up, to catch the
                // background check the splash starts for a recently
                // confirmed device; after that every 15 minutes.
                var wait = 6_000L
                while (true) {
                    kotlinx.coroutines.delay(wait)
                    wait = 15 * 60 * 1000L
                    if (vm.repo.isActive() && LicenseGate.isStale(vm.repo)) {
                        LicenseGate.verify(vm.repo, applicationContext)
                    }
                    if (!vm.repo.isActive() && vm.repo.account() != null &&
                        currentDestination != "activate" && currentDestination != "profile"
                    ) {
                        // Drop whatever is playing and go back to the
                        // activation screen, which explains why.
                        supportFragmentManager.popBackStack(
                            null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
                        )
                        open("activate")
                    }
                }
            }
        }
    }

    private fun setupSidebar() {
        navContainer.removeAllViews()

        navItems.forEach { item ->
            // STB+ drawer rows: 42dp tall, 18dp icons, 16sp Roboto Medium.
            // Selected rows fill white and flip icon + label to black.
            val navTint = ContextCompat.getColorStateList(this, R.color.menu_item_color_state)

            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.sidebar_item_height)
                ).apply { setMargins(0, 2, 0, 2) }
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                background = getDrawable(R.drawable.bg_nav_item)
                val padH = resources.getDimensionPixelSize(R.dimen.sidebar_item_inset)
                setPadding(padH, 0, padH, 0)
                tag = item.id
            }

            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    resources.getDimensionPixelSize(R.dimen.sidebar_icon_size),
                    resources.getDimensionPixelSize(R.dimen.sidebar_icon_size)
                )
                setImageResource(item.iconRes)
                imageTintList = navTint
                isDuplicateParentStateEnabled = true
            }

            val label = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = item.label
                setTextColor(navTint)
                textSize = 16f
                typeface = ResourcesCompat.getFont(this@MainActivity, R.font.robotomedium)
                setPadding(18, 0, 0, 0)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                isDuplicateParentStateEnabled = true
                tag = "label_${item.id}"
            }

            row.addView(icon)
            row.addView(label)

            row.setOnClickListener { open(item.id) }

            navContainer.addView(row)
        }
    }

    fun isDrawerVisible(): Boolean = isDrawerOpen

    /**
     * Slides the drawer in over the content and focuses the current
     * destination, so the remote lands on the item the user is already on.
     */
    /** What had focus when the drawer opened, so closing it lands back there. */
    private var focusBeforeDrawer: View? = null

    fun openDrawer() {
        if (isDrawerOpen) return
        isDrawerOpen = true
        focusBeforeDrawer = currentFocus

        val width = resources.getDimensionPixelSize(R.dimen.sidebar_width_open).toFloat()
        sidebar.translationX = -width
        sidebar.visibility = View.VISIBLE
        sidebar.animate().translationX(0f).setDuration(180).start()
        // STB+ pushes the page aside rather than dimming it under the menu:
        // the Live screen stays fully visible, shifted right by the menu width.
        contentHost.animate().translationX(width).setDuration(180).start()

        // Keep the remote inside the drawer. A scrim stops touches but not
        // focus traversal, so without this up/down would walk into the page
        // behind and the drawer would appear unresponsive.
        contentHost.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        val index = navItems.indexOfFirst { it.id == currentDestination }.coerceAtLeast(0)
        navContainer.getChildAt(index)?.let { row ->
            row.post { row.requestFocus() }
        }
    }

    /** Slides the drawer away and hands focus back to the page. */
    fun closeDrawer() {
        if (!isDrawerOpen) return
        isDrawerOpen = false

        val width = resources.getDimensionPixelSize(R.dimen.sidebar_width_open).toFloat()
        contentHost.animate().translationX(0f).setDuration(180).start()
        sidebar.animate()
            .translationX(-width)
            .setDuration(180)
            .withEndAction {
                sidebar.visibility = View.GONE
                contentHost.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                focusContent()
            }
            .start()
    }

    private fun focusContent() {
        // STB+ closes the menu onto the row it was opened from - the same
        // category, not the top of the rail.
        val previous = focusBeforeDrawer
        focusBeforeDrawer = null
        contentHost.post {
            if (previous != null && previous.isAttachedToWindow && previous.requestFocus()) return@post
            contentHost.requestFocus()
        }
    }

    /**
     * Opening the drawer with the remote: pressing LEFT when there is nothing
     * further left to move to. Without this the drawer would only be reachable
     * from the Live TV "Menu" pill.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Players own the remote outright - STB+ never opens its menu over video.
        val inPlayer = supportFragmentManager.findFragmentById(R.id.content)
            .let { it is PlayerFragment || it is LivePlayerFragment }

        if (event.action == KeyEvent.ACTION_DOWN && !inPlayer) {
            val key = event.keyCode

            // STB+ acts on the first press of LEFT / RIGHT / BACK only; holding
            // the key does not fire the action again and again.
            val firstPressOnly = key == KeyEvent.KEYCODE_DPAD_LEFT ||
                key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_BACK
            if (firstPressOnly && event.repeatCount > 0) return true

            if (isDrawerOpen) {
                when (key) {
                    // STB+ menu: RIGHT closes it, LEFT is inert; UP / DOWN / OK
                    // are ordinary focus moves inside it.
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { closeDrawer(); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> return true
                }
            } else if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                val focused = currentFocus
                if (focused != null && focused.focusSearch(View.FOCUS_LEFT) == null) {
                    openDrawer()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    fun open(id: String) {
        // Exit is a drawer action, not a destination - and STB+ asks first.
        if (id == "exit") {
            closeDrawer()
            StbDialogs.showConfirm(
                this,
                R.drawable.ic_nav_exit,
                "Exit",
                "Are you sure you want to Exit?",
                okLabel = "Exit"
            ) { exitApp() }
            return
        }

        currentDestination = id

        // Choosing a destination always dismisses the drawer, as in STB+.
        closeDrawer()

        // Highlight active item
        for (i in 0 until navContainer.childCount) {
            val row = navContainer.getChildAt(i)
            row.isSelected = (row.tag == id)
        }

        val fragment: Fragment = when (id) {
            "activate" -> ActivationFragment()
            // STB+ "Profile" opens the playlist/profile picker.
            "profile" -> ActivationFragment()
            "home" -> HomeFragment()
            "live" -> LiveFragment()
            "movies" -> CatalogFragment.newInstance("movies")
            "series" -> CatalogFragment.newInstance("series")
            "search" -> SearchFragment()
            "favorites" -> FavoritesFragment()
            "continue" -> ContinueWatchingFragment()
            "recent" -> RecentlyWatchedFragment()
            "settings" -> SettingsFragment()
            "device" -> SimpleFragment.newInstance(
                "Device Info",
                "MAC Address: ${vm.mac}\nDevice Key: ${vm.deviceKey}\nModel: ${android.os.Build.MODEL}\nAndroid OS: ${android.os.Build.VERSION.RELEASE}\nResolution: 1920x1080 (FHD TV)"
            )
            "about" -> SimpleFragment.newInstance(
                "About Pro Play Box",
                "Pro Play Box\nMedia Player for Android TV\nVersion 1.0.0"
            )
            else -> LiveFragment()
        }

        // The drawer is an overlay now and manages its own visibility.

        supportFragmentManager.beginTransaction()
            .replace(R.id.content, fragment)
            .commit()

        findViewById<View>(R.id.content).post {
            findViewById<View>(R.id.content).requestFocus()
        }
    }

    /** Set by [exitApp]: once this activity is gone, the process ends with it. */
    private var exiting = false

    /**
     * Exit closes the app for real. finish() alone left the process running:
     * the playlist download and licence checks started by the splash live
     * outside any screen, the network kept its connections open, and the
     * app stayed in the recent-apps list.
     */
    fun exitApp() {
        if (exiting) return
        exiting = true
        // Players release when their screen goes; removing it now stops the
        // sound at once rather than after the closing animation.
        supportFragmentManager.findFragmentById(R.id.content)?.let {
            supportFragmentManager.beginTransaction().remove(it).commitNowAllowingStateLoss()
        }
        PlayBoxApp.scope.coroutineContext.cancelChildren()
        com.playboxpro.tv.api.Http.client.dispatcher.cancelAll()
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (exiting) {
            com.playboxpro.tv.api.Http.client.connectionPool.evictAll()
            // Nothing is left running behind the closed screen.
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /**
     * Hands the stream to another installed player, per the Settings choice.
     * Returns false when nothing can handle it, so playback falls back to the
     * built-in player rather than doing nothing.
     */
    fun launchExternalPlayer(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(url), "video/*")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(packageManager) == null) {
                android.widget.Toast.makeText(
                    this,
                    "No external player installed - using the built-in one",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                false
            } else {
                startActivity(intent)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Leaves the player (and anything stacked under it) for a menu section. */
    fun leavePlayerTo(id: String) {
        supportFragmentManager.popBackStack(
            null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
        )
        open(id)
    }

    fun openMovieDetail(movie: Movie) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.content, DetailFragment.newMovie(movie))
            .addToBackStack(null)
            .commit()
    }

    fun openSeriesDetail(series: Series) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.content, DetailFragment.newSeries(series))
            .addToBackStack(null)
            .commit()
    }

    /** STB+ EpisodeActivity: the season / episode picker for a series. */
    fun openSeasons(series: Series) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.content, SeasonFragment.newInstance(series))
            .addToBackStack(null)
            .commit()
    }

    /**
     * The Stream Format setting picks between the channel's HLS and MPEG-TS
     * variants. Falls back to whichever one the playlist actually provided.
     */
    fun streamUrlFor(channel: Channel): String =
        if (vm.repo.streamFormat == "ts") {
            channel.urlTs.ifBlank { channel.url }
        } else {
            channel.url.ifBlank { channel.urlTs }
        }

    fun openChannel(channel: Channel) {
        play(
            url = streamUrlFor(channel),
            title = channel.name,
            subtitle = channel.categoryName,
            poster = channel.logo,
            backdrop = channel.logo,
            kind = "live",
            id = channel.id
        )
    }

    /**
     * Opens the STB+ full-screen live player on [index] of [channels], the
     * list UP / DOWN will walk through. External-player users get the single
     * stream instead, as before.
     */
    fun playLive(channels: List<Channel>, index: Int) {
        val channel = channels.getOrNull(index) ?: return
        vm.pushHistory(
            HistoryEntry(
                id = channel.id,
                kind = "live",
                name = channel.name,
                poster = channel.logo,
                categoryName = channel.categoryName,
                url = streamUrlFor(channel),
                watchedAt = System.currentTimeMillis()
            )
        )

        if (!vm.repo.liveInternalPlayer) {
            val u = streamUrlFor(channel)
            if (com.playboxpro.tv.api.StalkerApi.isStalker(u)) {
                externalWithFreshLink(u) { vm.startLiveSession(channels, index); showLivePlayer() }
                return
            }
            if (launchExternalPlayer(u)) return
        }

        vm.startLiveSession(channels, index)
        showLivePlayer()
    }

    private fun showLivePlayer() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.content, LivePlayerFragment())
            .addToBackStack("player")
            .commit()
    }

    /**
     * Portal streams need a fresh link before another app can open them;
     * [orElse] plays it here when no external player takes it.
     */
    private fun externalWithFreshLink(url: String, orElse: () -> Unit) {
        lifecycleScope.launch {
            val real = runCatching { vm.repo.resolveStream(url) }.getOrNull()
            if (real.isNullOrBlank() || !launchExternalPlayer(real)) orElse()
        }
    }

    fun play(
        url: String,
        title: String,
        subtitle: String = "",
        poster: String = "",
        backdrop: String = "",
        kind: String = "live",
        seriesId: String = "",
        season: Int = 1,
        episode: Int = 1,
        id: String = ""
    ) {
        val targetId = id.ifBlank { url }

        // Push to Recently Watched History
        vm.pushHistory(
            HistoryEntry(
                id = targetId,
                kind = kind,
                name = title,
                poster = poster.ifBlank { backdrop },
                categoryName = subtitle,
                seriesId = seriesId,
                season = season,
                episode = episode,
                url = url,
                watchedAt = System.currentTimeMillis()
            )
        )

        // Settings decide whether this opens in our player or another app.
        val useInternal =
            if (kind == "live") vm.repo.liveInternalPlayer else vm.repo.vodInternalPlayer

        // Player runs full screen; the drawer is already hidden.
        val openHere = {
            val fragment = PlayerFragment.newInstance(
                url = url,
                title = title,
                subtitle = subtitle,
                poster = poster,
                backdrop = backdrop,
                kind = kind,
                seriesId = seriesId,
                season = season,
                episode = episode,
                id = targetId
            )
            supportFragmentManager.beginTransaction()
                .replace(R.id.content, fragment)
                .addToBackStack("player")
                .commit()
        }
        if (!useInternal) {
            if (com.playboxpro.tv.api.StalkerApi.isStalker(url)) { externalWithFreshLink(url) { openHere() }; return }
            if (launchExternalPlayer(url)) return
        }
        openHere()
    }
}
