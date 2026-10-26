package com.playboxpro.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.playboxpro.tv.MainActivity
import com.playboxpro.tv.R
import com.playboxpro.tv.model.Channel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen live player, matched to STB+ FullScreenVideoActivity.
 *
 * The remote drives it exactly as STB+ does:
 *
 *  - UP / DOWN   step to the previous / next channel in the list the Live
 *                screen handed over. The OSD updates at once; the stream
 *                itself switches 400ms after the last press, so holding a
 *                direction skims the list without opening every channel.
 *  - OK / ENTER  opens the EPG guide for the current channel.
 *  - BACK        hides the OSD if it is showing, otherwise leaves the player.
 *
 * The OSD appears on every channel change and hides itself after 3s.
 */
class LivePlayerFragment : Fragment(R.layout.fragment_live_player) {

    private companion object {
        /** STB+ posts the switch 0x190ms after the last UP / DOWN. */
        const val SWITCH_DEBOUNCE_MS = 400L

        /** The info panel shown on a channel change hides after 3s. */
        const val OSD_TIMEOUT_MS = 3000L

        /** Once the remote is in the panel's buttons it stays up longer. */
        const val OSD_INTERACTIVE_TIMEOUT_MS = 8000L
    }

    private val vm: AppViewModel by activityViewModels()

    private lateinit var playerView: PlayerView
    private lateinit var playError: TextView
    private lateinit var shutter: View
    private lateinit var osd: View
    private lateinit var osdChannelName: TextView
    private lateinit var osdResolution: TextView
    private lateinit var osdCcBadge: TextView
    private lateinit var osdAudioInfo: TextView
    private lateinit var osdLogo: ImageView
    private lateinit var osdProgress: ProgressBar
    private lateinit var osdEpgTimes: TextView
    private lateinit var osdEpgElapsed: TextView
    private lateinit var osdEpgTitle: TextView
    private lateinit var osdClock: TextView
    private lateinit var osdArchived: View
    private lateinit var osdProfileName: TextView
    private lateinit var navLive: View

    private var player: ExoPlayer? = null

    /** The programme on air, kept so its elapsed time can tick. */
    private var currentProgramme: com.playboxpro.tv.model.EpgProgram? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideOsd = Runnable { hideOsdNow() }

    /** Any move between the panel's buttons keeps it up. */
    private val focusWatcher = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, now ->
        if (osd.visibility == View.VISIBLE && now != null && isInOsd(now)) {
            handler.removeCallbacks(hideOsd)
            handler.postDelayed(hideOsd, OSD_INTERACTIVE_TIMEOUT_MS)
        }
    }
    private var pendingSwitch: Runnable? = null
    private val tickClock = object : Runnable {
        override fun run() {
            osdClock.text = "TIME: " + clockFormat.format(Date())
            if (osd.visibility == View.VISIBLE) bindProgrammeTime()
            handler.postDelayed(this, 1000)
        }
    }

    private val clockFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    /** Index into [AppViewModel.liveSession] that is actually on air. */
    private var playingIndex = -1

    private val channels: List<Channel> get() = vm.liveSession

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        playerView = view.findViewById(R.id.livePlayerView)
        playError = view.findViewById(R.id.livePlayError)
        shutter = view.findViewById(R.id.liveShutter)
        osd = view.findViewById(R.id.liveOsd)
        osdChannelName = view.findViewById(R.id.osdChannelName)
        osdResolution = view.findViewById(R.id.osdResolution)
        osdCcBadge = view.findViewById(R.id.osdCcBadge)
        osdAudioInfo = view.findViewById(R.id.osdAudioInfo)
        osdLogo = view.findViewById(R.id.osdLogo)
        osdProgress = view.findViewById(R.id.osdProgress)
        osdEpgTimes = view.findViewById(R.id.osdEpgTimes)
        osdEpgElapsed = view.findViewById(R.id.osdEpgElapsed)
        osdEpgTitle = view.findViewById(R.id.osdEpgTitle)
        osdClock = view.findViewById(R.id.osdClock)
        osdArchived = view.findViewById(R.id.osdArchived)
        osdProfileName = view.findViewById(R.id.osdProfileName)
        navLive = view.findViewById(R.id.navLive)

        osdProfileName.text = vm.repo.account()?.name?.takeIf { it.isNotBlank() } ?: "Profile"
        // Live is the section on screen.
        navLive.isSelected = true

        if (channels.isEmpty()) {
            parentFragmentManager.popBackStack()
            return
        }

        setupKeys(view)
        setupOsdButtons()
        // Back online: the channel picks up again by itself.
        viewLifecycleOwner.lifecycleScope.launch {
            com.playboxpro.tv.api.NetworkWatch.online.collect { up ->
                if (up && isAdded && playError.visibility == View.VISIBLE && playingIndex >= 0) { retries = 0; tune(playingIndex) }
            }
        }
        setupTouch(view)
        view.viewTreeObserver.addOnGlobalFocusChangeListener(focusWatcher)
        setupBack()
        initPlayer()

        val start = vm.liveIndex.value.coerceIn(channels.indices)
        bindOsd(channels[start])
        tune(start)
        showOsd()
    }

    // ------------------------------------------------------------------
    // Remote
    // ------------------------------------------------------------------

    private fun setupKeys(root: View) {
        root.setOnKeyListener { _, keyCode, event ->
            // OK opens the panel on release, so the same press cannot also
            // click the button it puts the focus on.
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_UP) showOsdInteractive()
                return@setOnKeyListener true
            }
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { step(-1); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { step(+1); true }
                KeyEvent.KEYCODE_CHANNEL_UP -> { step(-1); true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { step(+1); true }
                // MENU opens the player options straight away; the remote's
                // own audio-track and subtitle keys go to those lists.
                KeyEvent.KEYCODE_MENU -> { showPlayerOptions(); true }
                KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> { showAudioTracks(); true }
                KeyEvent.KEYCODE_CAPTIONS -> { showSubtitles(); true }
                // LEFT or RIGHT (and OK, above) bring up the panel with the
                // remote in its buttons, starting on Live.
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { showOsdInteractive(); true }
                else -> false
            }
        }
        root.requestFocus()
    }

    /**
     * Touch screens: a tap on the picture shows or hides the panel, and a
     * swipe up or down changes channel, like UP / DOWN on the remote.
     */
    private fun setupTouch(root: View) {
        val detector = android.view.GestureDetector(requireContext(), object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true

            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                if (osd.visibility == View.VISIBLE) { handler.removeCallbacks(hideOsd); hideOsdNow() } else showOsdInteractive()
                return true
            }

            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                val dy = e2.y - (e1?.y ?: e2.y)
                val dx = e2.x - (e1?.x ?: e2.x)
                if (kotlin.math.abs(dy) < 80 * resources.displayMetrics.density || kotlin.math.abs(dy) < kotlin.math.abs(dx)) return false
                // Swipe up for the next channel, down for the previous one.
                step(if (dy < 0) +1 else -1)
                return true
            }
        })
        root.setOnTouchListener { v, ev ->
            val handled = detector.onTouchEvent(ev)
            if (ev.action == android.view.MotionEvent.ACTION_UP && handled) v.performClick()
            true
        }
        root.setOnClickListener { }
        requireView().findViewById<View>(R.id.osdPanel).setOnClickListener {
            handler.removeCallbacks(hideOsd)
            handler.postDelayed(hideOsd, OSD_INTERACTIVE_TIMEOUT_MS)
        }
    }

    /**
     * The panel's buttons. UP / DOWN on any of them still change channel,
     * so zapping works whether or not the panel is open.
     */
    private fun setupOsdButtons() {
        val main = activity as? MainActivity
        fun click(id: Int, action: () -> Unit) {
            val v = requireView().findViewById<View>(id)
            v.setOnClickListener { action() }
            v.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { step(-1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { step(+1); true }
                    else -> false
                }
            }
        }
        // Home goes back to the channel list the player was opened from.
        click(R.id.navHome) { parentFragmentManager.popBackStack() }
        click(R.id.navMovies) { main?.leavePlayerTo("movies") }
        click(R.id.navShows) { main?.leavePlayerTo("series") }
        // Already watching live: put the panel away.
        click(R.id.navLive) { hideOsdNow() }
        click(R.id.navGuide) { showGuide() }
        click(R.id.btnLiveSettings) { withOsdHeld(R.id.btnLiveSettings) { done -> showAspect(done) } }
        click(R.id.btnLiveAudio) { withOsdHeld(R.id.btnLiveAudio) { done -> showAudioTracks(done) } }
        click(R.id.btnLiveCc) { withOsdHeld(R.id.btnLiveCc) { done -> showSubtitles(done) } }
        click(R.id.btnLiveMore) { withOsdHeld(R.id.btnLiveMore) { done -> showMore(done) } }
        click(R.id.btnLiveSearch) { main?.leavePlayerTo("search") }
        click(R.id.osdProfilePill) { main?.leavePlayerTo("profile") }
    }

    private fun showGuide() {
        currentChannel()?.let { ch ->
            viewLifecycleOwner.lifecycleScope.launch {
                val programmes = vm.repo.epgFor(ch, full = true)
                if (isAdded) StbDialogs.showEpgGuide(requireContext(), ch, programmes)
            }
        }
    }

    /** More: favourite, the programme guide, and every player option. */
    private fun showMore(done: () -> Unit) {
        val ch = currentChannel() ?: return done()
        val fav = vm.repo.isFavorite(ch.id)
        var chained = false
        StbDialogs.showSelection(
            requireContext(), ch.name,
            listOf(
                "fav" to if (fav) "Remove from Favorites" else "Add to Favorites",
                "guide" to "Program Guide",
                "options" to "Player Options"
            ),
            "",
            onClose = { if (!chained) done() }
        ) { picked ->
            when (picked) {
                "fav" -> vm.toggleFavorite(ch.id)
                "guide" -> showGuide()
                "options" -> { chained = true; showPlayerOptions(done) }
            }
        }
    }

    /** The panel stays up while a chooser is open, and focus returns to its button. */
    private fun withOsdHeld(buttonId: Int, open: (() -> Unit) -> Unit) {
        handler.removeCallbacks(hideOsd)
        osd.visibility = View.VISIBLE
        open {
            if (isAdded && view != null) {
                osd.visibility = View.VISIBLE
                requireView().findViewById<View>(buttonId)?.requestFocus()
                handler.removeCallbacks(hideOsd)
                handler.postDelayed(hideOsd, OSD_INTERACTIVE_TIMEOUT_MS)
            }
        }
    }

    private fun isInOsd(v: View): Boolean {
        var p: android.view.ViewParent? = v.parent
        while (p != null) {
            if (p === osd) return true
            p = p.parent
        }
        return false
    }

    /**
     * Moves the selection and re-arms the debounced switch, the way STB+
     * does on every UP / DOWN: OSD first, stream only once the presses stop.
     */
    private fun step(delta: Int) {
        val target = vm.liveIndex.value + delta
        if (target !in channels.indices) return

        vm.setLiveIndex(target)
        playError.visibility = View.GONE
        handler.removeCallbacks(retune)
        bindOsd(channels[target])
        showOsd()

        // STB+ stops the player on the very first press (its U()), so the
        // channel being left goes black at once instead of carrying on under
        // the new name until the debounced switch lands. The surface keeps
        // its last frame through stop(), hence the explicit shutter.
        player?.stop()
        shutter.visibility = View.VISIBLE
        clearFormatBadges()

        pendingSwitch?.let { handler.removeCallbacks(it) }
        val r = Runnable { tune(target) }
        pendingSwitch = r
        handler.postDelayed(r, SWITCH_DEBOUNCE_MS)
    }

    private fun setupBack() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // STB+: first BACK dismisses the OSD, the next one leaves.
                    if (osd.visibility == View.VISIBLE) {
                        handler.removeCallbacks(hideOsd)
                        hideOsdNow()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    private fun initPlayer() {
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("IPTVSmartersPlayer/1.0 (Android TV; PlayBox Pro)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
        liveSource = LiveStreamSource.Factory(http)
        val sources = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(requireContext(), TolerantExtractorsFactory())
            .setDataSourceFactory(liveSource)

        // Decoder fallback: if a box's hardware codec rejects a stream, try
        // the next decoder instead of failing the channel outright.
        val renderers = LivePlayerOptions.renderers(requireContext())

        player = ExoPlayer.Builder(requireContext(), renderers)
            .setMediaSourceFactory(sources)
            .build().also { p ->
                playerView.player = p
                LivePlayerOptions.styleSubtitles(playerView)
                p.playWhenReady = true
                // "Auto" fits each stream to the screen; any other aspect
                // choice is applied as a fixed setting.
                VideoFit.attach(playerView, p) { vm.repo.liveAspect == "auto" }
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (!isAdded) return
                        // A failed audio track: carry on with another one, no "offline".
                        if (audioGuard.onError(error)) { player?.let { audioGuard.apply(it); it.prepare() }; return }
                        playError.text = if (com.playboxpro.tv.api.NetworkWatch.isOnline) "Broadcast Source Offline" else com.playboxpro.tv.api.NetworkWatch.NO_INTERNET
                        playError.visibility = View.VISIBLE
                        if (!com.playboxpro.tv.api.NetworkWatch.isOnline) return
                        // Try the channel again a few times, waiting a little longer each time.
                        if (retries < 5) { retries++; scheduleRetune(2000L * retries) }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (!isAdded) return
                        if (state == Player.STATE_READY) { playError.visibility = View.GONE; retries = 0 }
                        // A live stream should never end: the provider dropped it, so
                        // tune the same channel again with a fresh link.
                        if (state == Player.STATE_ENDED) scheduleRetune(1000L)
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        if (!isAdded) return
                        bindFormatBadges(tracks)
                        restoreAudioChoice(tracks)
                        player?.let { audioGuard.apply(it) }
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        // PlayerView re-reads the stream's own shape on every
                        // size change; put a forced 16:9 / 4:3 back afterwards.
                        if (isAdded) playerView.post { applyAspect() }
                    }

                    override fun onRenderedFirstFrame() {
                        if (isAdded) shutter.visibility = View.GONE
                    }
                })

                // Debug builds report the decoder in use and any dropped
                // frames, so playback quality on a box can be read off logcat.
                if (com.playboxpro.tv.BuildConfig.DEBUG) {
                    p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                        override fun onVideoDecoderInitialized(
                            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                            decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
                        ) { android.util.Log.i("LiveStats", "video decoder: $decoderName (${initializationDurationMs}ms)") }

                        override fun onDroppedVideoFrames(
                            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                            droppedFrames: Int, elapsedMs: Long
                        ) { android.util.Log.w("LiveStats", "dropped $droppedFrames frames in ${elapsedMs}ms") }

                        override fun onVideoSizeChanged(
                            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                            videoSize: androidx.media3.common.VideoSize
                        ) { android.util.Log.i("LiveStats", "video size: ${videoSize.width}x${videoSize.height}") }
                    })
                }
            }
    }

    private fun tune(index: Int) {
        val channel = channels.getOrNull(index) ?: return
        val p = player ?: return
        if (index != playingIndex) retries = 0
        handler.removeCallbacks(retune)
        playingIndex = index
        audioGuard.itemId = channel.id

        val url = (activity as? MainActivity)?.streamUrlFor(channel) ?: channel.url
        if (url.isBlank()) {
            playError.visibility = View.VISIBLE
            return
        }

        vm.repo.pushRecentChannel(channel.id)
        clearFormatBadges()
        // A track picked on the last channel must not follow to this one.
        LivePlayerOptions.resetTracks(p)
        // Portal channels get a fresh stream link first; other URLs pass straight through.
        tuneJob?.cancel()
        tuneJob = viewLifecycleOwner.lifecycleScope.launch {
            val real = runCatching { vm.repo.resolveStream(url) }.getOrNull()
            if (!isAdded || playingIndex != index || player !== p) return@launch
            if (real.isNullOrBlank()) { playError.visibility = View.VISIBLE; return@launch }
            liveSource.prepareFor(real)
            p.setMediaItem(MediaItem.fromUri(real))
            p.prepare()
            p.play()
        }
    }

    private var tuneJob: kotlinx.coroutines.Job? = null

    private fun currentChannel(): Channel? = channels.getOrNull(vm.liveIndex.value)

    private var retries = 0
    private val audioGuard = LivePlayerOptions.AudioGuard()
    /** Keeps live TS connections going when the server closes them (see LiveStreamSource). */
    private lateinit var liveSource: LiveStreamSource.Factory
    private val retune = Runnable { if (isResumed && playingIndex >= 0) tune(playingIndex) }

    private fun scheduleRetune(delayMs: Long) {
        handler.removeCallbacks(retune)
        handler.postDelayed(retune, delayMs)
    }

    // Audio track, subtitles + aspect ratio: see LivePlayerOptions.

    private fun liveStore() = LivePlayerOptions.liveStore(vm.repo, currentChannel()?.id)

    private fun showPlayerOptions(done: () -> Unit = {}) =
        LivePlayerOptions.showOptions(requireContext(), player, playerView, liveStore(), onDone = done)

    private fun showAudioTracks(done: () -> Unit = {}) =
        LivePlayerOptions.showAudio(requireContext(), player, liveStore(), done)

    private fun showSubtitles(done: () -> Unit = {}) =
        LivePlayerOptions.showSubtitles(requireContext(), player, liveStore(), done)

    private fun showAspect(done: () -> Unit = {}) =
        LivePlayerOptions.showAspect(requireContext(), player, playerView, liveStore(), done)

    private fun applyAspect() {
        val p = player ?: return
        LivePlayerOptions.applyAspect(playerView, p, vm.repo.liveAspect)
    }

    private fun restoreAudioChoice(@Suppress("UNUSED_PARAMETER") tracks: Tracks) {
        val p = player ?: return
        LivePlayerOptions.restore(p, LivePlayerOptions.liveStore(vm.repo, channels.getOrNull(playingIndex)?.id))
    }

    // ------------------------------------------------------------------
    // OSD
    // ------------------------------------------------------------------

    private var epgJob: kotlinx.coroutines.Job? = null

    private fun loadEpg(channel: Channel) {
        epgJob?.cancel()
        epgJob = viewLifecycleOwner.lifecycleScope.launch {
            val programmes = vm.repo.epgFor(channel, full = false)
            if (currentChannel()?.id != channel.id || !isAdded) return@launch
            val now = System.currentTimeMillis()
            val current = programmes.firstOrNull { now in it.start until it.end }
                ?: return@launch
            currentProgramme = current
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            osdEpgTimes.text = "${fmt.format(Date(current.start))} - ${fmt.format(Date(current.end))}"
            osdEpgTitle.text = current.title
            bindProgrammeTime()
        }
    }

    /** Elapsed / length of the programme on air, and its progress bar. */
    private fun bindProgrammeTime() {
        val p = currentProgramme ?: return
        val now = System.currentTimeMillis()
        val span = (p.end - p.start).coerceAtLeast(1L)
        val elapsed = (now - p.start).coerceIn(0L, span)
        osdEpgElapsed.text = "${hms(elapsed)} / ${hms(span)}"
        osdProgress.progress = ((elapsed * osdProgress.max) / span).toInt()
    }

    private fun hms(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun bindOsd(channel: Channel) {
        val number = channels.indexOfFirst { it.id == channel.id }.takeIf { it >= 0 }?.plus(1)
        osdChannelName.text = if (number != null) "$number  ${channel.name}" else channel.name
        ImageLoader.loadLogo(osdLogo, channel.logo)

        // Neutral until the guide answers; the same state the Live screen shows.
        currentProgramme = null
        osdEpgTimes.text = ""
        osdEpgElapsed.text = ""
        osdEpgTitle.text = "No Information"
        osdProgress.progress = 0
        loadEpg(channel)

        osdArchived.visibility = if (channel.archive) View.VISIBLE else View.GONE
    }

    /** Information only, after a channel change: no buttons focused, short timeout. */
    private fun showOsd() {
        osd.visibility = View.VISIBLE
        bindProgrammeTime()
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, OSD_TIMEOUT_MS)
    }

    /** The panel with the remote in it, starting on Live. */
    private fun showOsdInteractive() {
        osd.visibility = View.VISIBLE
        bindProgrammeTime()
        navLive.requestFocus()
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, OSD_INTERACTIVE_TIMEOUT_MS)
    }

    private fun hideOsdNow() {
        osd.visibility = View.GONE
        // Back to the root, so UP / DOWN / OK reach the player again.
        view?.requestFocus()
    }

    private fun clearFormatBadges() {
        osdResolution.visibility = View.GONE
        osdCcBadge.visibility = View.GONE
        osdAudioInfo.visibility = View.GONE
    }

    /** HD / CC / audio badges, read off the stream once a channel is up. */
    private fun bindFormatBadges(tracks: Tracks) {
        var height = 0
        var audioCodec = ""
        var audioChannels = 0
        var hasText = false

        for (group in tracks.groups) {
            if (!group.isSelected && group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> if (group.isTrackSelected(i) || height == 0) {
                        height = f.height.takeIf { it > 0 } ?: height
                    }
                    C.TRACK_TYPE_AUDIO -> if (group.isTrackSelected(i) || audioCodec.isBlank()) {
                        audioCodec = codecName(f.sampleMimeType).ifBlank { audioCodec }
                        audioChannels = f.channelCount.takeIf { it > 0 } ?: audioChannels
                    }
                    C.TRACK_TYPE_TEXT -> hasText = true
                }
            }
        }

        if (height > 0) {
            osdResolution.text = when {
                height >= 2000 -> "4K"
                height >= 1000 -> "FHD"
                height >= 700 -> "HD"
                else -> "SD"
            }
            osdResolution.visibility = View.VISIBLE
        }
        if (audioCodec.isNotBlank()) {
            osdAudioInfo.text = when {
                audioChannels >= 6 -> "5.1"
                audioChannels == 2 -> "STEREO"
                audioChannels == 1 -> "MONO"
                else -> audioCodec
            }
            osdAudioInfo.visibility = View.VISIBLE
        }
        osdCcBadge.visibility = if (hasText) View.VISIBLE else View.GONE
    }

    private fun codecName(mime: String?): String = when (mime) {
        "video/avc" -> "H264"
        "video/hevc" -> "HEVC"
        "video/mp4v-es" -> "MPEG4"
        "video/mpeg2" -> "MPEG2"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        "audio/mp4a-latm" -> "AAC"
        "audio/ac3" -> "AC3"
        "audio/eac3" -> "EAC3"
        "audio/mpeg", "audio/mpeg-L2" -> "MP3"
        "audio/opus" -> "OPUS"
        "audio/vorbis" -> "VORBIS"
        "audio/raw" -> "PCM"
        "audio/true-hd" -> "TRUEHD"
        "audio/vnd.dts" -> "DTS"
        null -> ""
        else -> mime.substringAfter('/').uppercase(Locale.getDefault()).take(6)
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        handler.post(tickClock)
        view?.requestFocus()
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tickClock)
        player?.pause()
    }

    override fun onDestroyView() {
        view?.viewTreeObserver?.removeOnGlobalFocusChangeListener(focusWatcher)
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroyView()
    }
}
