package com.playboxpro.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.playboxpro.tv.MainActivity
import com.playboxpro.tv.R
import com.playboxpro.tv.model.Episode
import com.playboxpro.tv.model.Series
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Movie / episode player. One frosted-glass panel carries everything:
 * title and time, the seek bar, Now Playing, the transport controls
 * (previous, rewind, play / pause, forward, next, subtitles) and, on the
 * right, the profile, a settings button and the clock.
 */
class PlayerFragment : Fragment(R.layout.fragment_player) {
    private val vm: AppViewModel by activityViewModels()

    private lateinit var playerView: PlayerView
    private lateinit var playerOverlay: View
    private lateinit var bufferingSpinner: ProgressBar
    private lateinit var osdTitle: TextView
    private lateinit var osdTimeElapsed: TextView
    private lateinit var osdSeekBar: SeekBar
    private lateinit var osdSeekRow: View
    private lateinit var osdNowPlaying: TextView
    private lateinit var osdPlayLabel: TextView
    private lateinit var osdKindLabel: TextView
    private lateinit var osdClock: TextView

    private lateinit var btnPlayPause: ImageView
    private lateinit var btnRewind: View
    private lateinit var btnForward: View
    private lateinit var btnPrev: View
    private lateinit var btnNext: View
    private lateinit var btnSubtitles: View
    private lateinit var btnSettings: View
    private lateinit var osdResolution: TextView
    private lateinit var osdCcBadge: TextView
    private lateinit var playError: TextView

    private var exoPlayer: ExoPlayer? = null
    private var isLiveStream = false
    private var streamUrl = ""
    private var mediaTitle = ""
    private var mediaSubtitle = ""
    private var mediaPoster = ""
    private var mediaBackdrop = ""
    private var mediaKind = "live"
    private var seriesId = ""
    private var seasonNum = 1
    private var episodeNum = 1
    private var mediaId = ""

    /** The saved position is applied once, when the title first becomes ready. */
    private var resumeApplied = false

    private val audioGuard = LivePlayerOptions.AudioGuard()

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideOsd() }

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            osdClock.text = clockFormat.format(Date())
            progressHandler.postDelayed(this, 1000)
        }
    }
    private val clockFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    /** Any focus move inside the controls keeps them up for another 5s. */
    private val focusWatcher = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ ->
        if (playerOverlay.visibility == View.VISIBLE) resetHideTimer()
    }

    companion object {
        private const val OSD_TIMEOUT_MS = 5000L

        fun newInstance(
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
        ) = PlayerFragment().apply {
            arguments = Bundle().apply {
                putString("url", url)
                putString("title", title)
                putString("subtitle", subtitle)
                putString("poster", poster)
                putString("backdrop", backdrop)
                putString("kind", kind)
                putString("seriesId", seriesId)
                putInt("season", season)
                putInt("episode", episode)
                putString("id", id)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        streamUrl = requireArguments().getString("url", "")
        mediaTitle = requireArguments().getString("title", "")
        mediaSubtitle = requireArguments().getString("subtitle", "")
        mediaPoster = requireArguments().getString("poster", "")
        mediaBackdrop = requireArguments().getString("backdrop", "")
        mediaKind = requireArguments().getString("kind", "live")
        seriesId = requireArguments().getString("seriesId", "")
        seasonNum = requireArguments().getInt("season", 1)
        episodeNum = requireArguments().getInt("episode", 1)
        mediaId = requireArguments().getString("id", streamUrl)

        isLiveStream = (mediaKind == "live")

        playerView = view.findViewById(R.id.exoPlayerView)
        playerOverlay = view.findViewById(R.id.playerOverlay)
        bufferingSpinner = view.findViewById(R.id.playerBuffering)
        osdTitle = view.findViewById(R.id.osdTitle)
        osdTimeElapsed = view.findViewById(R.id.osdTimeElapsed)
        osdSeekBar = view.findViewById(R.id.osdSeekBar)
        osdSeekRow = view.findViewById(R.id.osdSeekRow)
        osdNowPlaying = view.findViewById(R.id.osdNowPlaying)
        osdPlayLabel = view.findViewById(R.id.osdPlayLabel)
        osdKindLabel = view.findViewById(R.id.osdKindLabel)
        osdClock = view.findViewById(R.id.osdClock)

        btnPlayPause = view.findViewById(R.id.btnPlayPause)
        btnRewind = view.findViewById(R.id.btnRewind30)
        btnForward = view.findViewById(R.id.btnForward30)
        btnPrev = view.findViewById(R.id.btnPrevEpisode)
        btnNext = view.findViewById(R.id.btnNextEpisode)
        btnSubtitles = view.findViewById(R.id.btnSubtitles)
        btnSettings = view.findViewById(R.id.btnPlayerSettings)
        osdResolution = view.findViewById(R.id.osdResolution)
        osdCcBadge = view.findViewById(R.id.osdCcBadge)
        playError = view.findViewById(R.id.playError)

        view.findViewById<TextView>(R.id.osdProfileName).text =
            vm.repo.account()?.name?.takeIf { it.isNotBlank() } ?: "Profile"
        osdClock.text = clockFormat.format(Date())

        bindTitles()

        val seekable = !isLiveStream
        osdSeekRow.visibility = if (seekable) View.VISIBLE else View.GONE
        btnRewind.visibility = if (seekable) View.VISIBLE else View.GONE
        btnForward.visibility = if (seekable) View.VISIBLE else View.GONE
        if (isLiveStream) osdTimeElapsed.text = "Live"

        // Previous / next step through the series; a film has no neighbours,
        // so previous restarts it and next is shown disabled.
        val hasNext = neighbourEpisode(+1) != null
        btnNext.isEnabled = hasNext
        btnNext.isFocusable = hasNext

        setupButtons()
        setupSeekBar()
        initPlayer()

        // Back online: carry on from the same point.
        viewLifecycleOwner.lifecycleScope.launch {
            com.playboxpro.tv.api.NetworkWatch.online.collect { up ->
                if (up && isAdded && playError.visibility == View.VISIBLE) {
                    playError.visibility = View.GONE
                    exoPlayer?.let { it.prepare(); it.play() }
                }
            }
        }

        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setOnKeyListener { _, keyCode, event ->
            // OK brings the controls back on release, so the same press
            // cannot also click play / pause once it has the focus.
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_UP) showOsd()
                return@setOnKeyListener true
            }
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    togglePlayPause(); showOsd(); true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekRelative(-10000); showOsd(); true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekRelative(10000); showOsd(); true }
                KeyEvent.KEYCODE_CAPTIONS -> { showSubtitlesDialog(); true }
                KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> { showAudioTracksDialog(); true }
                KeyEvent.KEYCODE_MENU -> { showOsd(); showSettingsDialog(); true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // With the controls hidden the first press only brings
                    // them back, focused on play / pause.
                    showOsd(); true
                }
                else -> false
            }
        }
        // Touch: a tap on the picture shows or hides the controls; taps on
        // the panel itself stay on the panel.
        view.setOnClickListener {
            if (playerOverlay.visibility == View.VISIBLE) { hideHandler.removeCallbacks(hideRunnable); hideOsd() } else showOsd()
        }
        view.findViewById<View>(R.id.osdPanel).setOnClickListener { resetHideTimer() }
        view.viewTreeObserver.addOnGlobalFocusChangeListener(focusWatcher)

        showOsd()
    }

    /** Title on top; "Now Playing" names the episode, or the film itself. */
    private fun bindTitles() {
        osdTitle.text = mediaTitle
        osdNowPlaying.text = mediaSubtitle.ifBlank { mediaTitle }
        osdKindLabel.text = when (mediaKind) {
            "episode" -> "Series"
            "movie" -> "Movie"
            "live" -> "Live"
            else -> ""
        }
    }

    private fun initPlayer() {
        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("IPTVSmartersPlayer/1.0 (Android TV; PlayBox Pro)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)

        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(requireContext(), TolerantExtractorsFactory())
            .setDataSourceFactory(httpDataSourceFactory)

        // Same audio path as live TV: every track decoded in the player,
        // so switching language is instant and follows the TV volume.
        val player = ExoPlayer.Builder(requireContext(), LivePlayerOptions.renderers(requireContext()))
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        exoPlayer = player
        playerView.player = player
        playerView.useController = false
        LivePlayerOptions.styleSubtitles(playerView)

        // "Fit" (the default) fits each video to the screen automatically;
        // the other aspect settings are fixed choices.
        VideoFit.attach(playerView, player) { vodAspect() == "auto" }
        applyAspectRatio(vodAspect())

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (!isAdded) return
                when (state) {
                    Player.STATE_BUFFERING -> bufferingSpinner.visibility = View.VISIBLE
                    Player.STATE_READY -> {
                        bufferingSpinner.visibility = View.GONE
                        playError.visibility = View.GONE
                        bindPlayState(player.isPlaying)
                        // Playing for real now: progress may be saved from here on.
                        resumeApplied = true
                        updateProgress()
                    }
                    Player.STATE_ENDED -> {
                        bufferingSpinner.visibility = View.GONE
                        bindPlayState(false)
                        onPlaybackEnded()
                    }
                    Player.STATE_IDLE -> bufferingSpinner.visibility = View.GONE
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (!isAdded) return
                // A failed audio track: carry on from the same point with another one.
                if (audioGuard.onError(error)) { audioGuard.apply(player); player.prepare(); return }
                playError.text = if (com.playboxpro.tv.api.NetworkWatch.isOnline) "Broadcast Source Offline" else com.playboxpro.tv.api.NetworkWatch.NO_INTERNET
                playError.visibility = View.VISIBLE; bufferingSpinner.visibility = View.GONE
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (!isAdded) return
                var height = 0; var hasText = false
                for (g in tracks.groups) for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    if (g.type == C.TRACK_TYPE_VIDEO && g.isTrackSelected(i)) height = f.height
                    if (g.type == C.TRACK_TYPE_TEXT) hasText = true
                }
                val res = qualityLabel(height)
                osdResolution.text = res
                osdResolution.visibility = if (res.isNotBlank()) View.VISIBLE else View.GONE
                osdCcBadge.visibility = if (hasText) View.VISIBLE else View.GONE
                // Audio language and subtitles last chosen for this title.
                LivePlayerOptions.restore(player, store())
                audioGuard.apply(player)
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                // PlayerView re-reads the video's own shape on each size
                // change; put a forced 16:9 / 4:3 back afterwards.
                if (isAdded) playerView.post { applyAspectRatio(vodAspect()) }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isAdded) bindPlayState(isPlaying)
            }
        })

        audioGuard.itemId = mediaId
        // Where this title was left off, read before anything can overwrite it.
        val resumeAt = if (isLiveStream) 0L else vm.repo.getResume(mediaId)
            ?.takeIf { it.positionMs > 5000 && it.progress < 0.95f }?.positionMs ?: 0L

        if (streamUrl.isNotBlank()) {
            LivePlayerOptions.resetTracks(player)
            bufferingSpinner.visibility = View.VISIBLE
            // Portal items get a fresh stream link first; other URLs pass straight through.
            viewLifecycleOwner.lifecycleScope.launch {
                val real = runCatching { vm.repo.resolveStream(streamUrl) }.getOrNull()
                if (!isAdded || exoPlayer !== player) return@launch
                if (real.isNullOrBlank()) { playError.visibility = View.VISIBLE; bufferingSpinner.visibility = View.GONE; return@launch }
                player.setMediaItem(MediaItem.fromUri(real))
                // Start straight at the saved point instead of jumping there later.
                if (resumeAt > 0) player.seekTo(resumeAt)
                player.prepare()
                player.playWhenReady = true
            }
        }

        progressHandler.post(progressRunnable)
    }

    private fun qualityLabel(height: Int): String = when {
        height >= 2000 -> "4K"
        height >= 1000 -> "FHD"
        height >= 700 -> "HD"
        height > 0 -> "SD"
        else -> ""
    }

    private fun bindPlayState(playing: Boolean) {
        btnPlayPause.setImageResource(if (playing) R.drawable.ic_glass_pause else R.drawable.ic_glass_play)
        osdPlayLabel.text = if (playing) "PAUSE" else "PLAY"
    }

    private fun setupButtons() {
        btnPlayPause.setOnClickListener { togglePlayPause(); resetHideTimer() }
        btnRewind.setOnClickListener { seekRelative(-10000); resetHideTimer() }
        btnForward.setOnClickListener { seekRelative(10000); resetHideTimer() }
        btnPrev.setOnClickListener {
            val prev = neighbourEpisode(-1)
            if (prev != null) playEpisode(prev.first, prev.second)
            else { exoPlayer?.seekTo(0); resetHideTimer() }
        }
        btnNext.setOnClickListener {
            neighbourEpisode(+1)?.let { playEpisode(it.first, it.second) }
        }
        btnSubtitles.setOnClickListener { showSubtitlesDialog() }
        btnSettings.setOnClickListener { showSettingsDialog() }

        // LEFT / RIGHT on the seek bar step 10s at a time.
        osdSeekBar.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { seekRelative(-10000); resetHideTimer(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { seekRelative(10000); resetHideTimer(); true }
                else -> false
            }
        }
    }

    private fun setupSeekBar() {
        osdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = exoPlayer?.duration?.takeIf { it > 0 } ?: return
                    val targetMs = (progress.toFloat() / osdSeekBar.max * duration).toLong()
                    osdTimeElapsed.text = "${formatTime(targetMs)} / ${formatTime(duration)}"
                    resetHideTimer()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                hideHandler.removeCallbacks(hideRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val duration = exoPlayer?.duration?.takeIf { it > 0 } ?: return
                val targetMs = ((seekBar?.progress ?: 0).toFloat() / osdSeekBar.max * duration).toLong()
                exoPlayer?.seekTo(targetMs)
                resetHideTimer()
            }
        })
    }

    private fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun seekRelative(offsetMs: Long) {
        val player = exoPlayer ?: return
        val duration = player.duration
        val target = player.currentPosition + offsetMs
        val newPos = if (duration > 0) target.coerceIn(0, duration) else target.coerceAtLeast(0)
        player.seekTo(newPos)
        updateProgress()
    }

    private fun updateProgress() {
        val player = exoPlayer ?: return
        if (isLiveStream) return
        val position = player.currentPosition.coerceAtLeast(0)
        val duration = player.duration

        if (duration <= 0) {
            // Length not known yet: show the running time on its own.
            osdTimeElapsed.text = formatTime(position)
            return
        }

        osdTimeElapsed.text = "${formatTime(position)} / ${formatTime(duration)}"
        val max = osdSeekBar.max
        osdSeekBar.progress = ((position.toDouble() / duration) * max).toInt().coerceIn(0, max)
        osdSeekBar.secondaryProgress =
            ((player.bufferedPosition.toDouble() / duration) * max).toInt().coerceIn(0, max)

        // Saved only once the title is really playing; before that the
        // position reads 0 and would wipe the saved resume point.
        if (!resumeApplied) return
        vm.savePlaybackProgress(
            id = mediaId,
            title = mediaTitle,
            poster = mediaPoster,
            backdrop = mediaBackdrop,
            positionMs = position,
            durationMs = duration,
            kind = mediaKind,
            seriesId = seriesId,
            season = seasonNum,
            episode = episodeNum,
            url = streamUrl
        )
    }

    // ------------------------------------------------------------------
    // Episodes
    // ------------------------------------------------------------------

    /** The episode [delta] away from this one in the series, if any. */
    private fun neighbourEpisode(delta: Int): Pair<Series, Episode>? {
        if (mediaKind != "episode") return null
        // The playlist itself carries no episodes; the saved series details do.
        val listed = vm.library.value.series.firstOrNull { it.id == seriesId }
        val series = listed?.takeIf { it.seasons.isNotEmpty() }
            ?: vm.repo.cachedSeries(seriesId)?.let { s -> listed?.copy(seasons = s.seasons) ?: s }
            ?: return null
        val flat = series.seasons.flatMap { it.episodes }
        val idx = flat.indexOfFirst { it.id == mediaId }
        if (idx < 0) return null
        return flat.getOrNull(idx + delta)?.let { series to it }
    }

    private fun playEpisode(series: Series, ep: Episode) {
        // Replace this player rather than stacking a new one on top.
        parentFragmentManager.popBackStack()
        (activity as? MainActivity)?.play(
            url = ep.url,
            title = series.name,
            subtitle = "S${ep.season} E${ep.episode} - ${ep.name}",
            poster = series.poster,
            backdrop = series.backdrop,
            kind = "episode",
            seriesId = series.id,
            season = ep.season,
            episode = ep.episode,
            id = ep.id
        )
    }

    private fun onPlaybackEnded() {
        if (mediaKind == "episode" && vm.repo.autoplayNext) {
            neighbourEpisode(+1)?.let { playEpisode(it.first, it.second) }
        }
    }

    // ------------------------------------------------------------------
    // Audio track, subtitles, aspect ratio (shared with live TV)
    // ------------------------------------------------------------------

    /** Choices for this movie / episode are remembered per title. */
    private fun store() = LivePlayerOptions.Store(
        vm.repo, mediaId.takeIf { it.isNotBlank() },
        { vodAspect() },
        { vm.repo.aspectRatio = it }
    )

    /** The saved movie aspect setting, in the same terms as live TV. */
    private fun vodAspect(): String = when (val a = vm.repo.aspectRatio) {
        "fit", "" -> "auto"
        "fill" -> "stretch"
        else -> a
    }

    private fun showAudioTracksDialog() = withOsdHeld(R.id.btnPlayerSettings) { done ->
        LivePlayerOptions.showAudio(requireContext(), exoPlayer, store(), done)
    }

    private fun showSubtitlesDialog() = withOsdHeld(R.id.btnSubtitles) { done ->
        LivePlayerOptions.showSubtitles(requireContext(), exoPlayer, store(), done)
    }

    /** The settings button: audio, subtitles, aspect ratio and the external player. */
    private fun showSettingsDialog() = withOsdHeld(R.id.btnPlayerSettings) { done ->
        LivePlayerOptions.showOptions(
            requireContext(), exoPlayer, playerView, store(),
            extras = listOf("external" to "Open in External Player"),
            onExtra = {
                viewLifecycleOwner.lifecycleScope.launch {
                    val real = runCatching { vm.repo.resolveStream(streamUrl) }.getOrDefault(streamUrl)
                    (activity as? MainActivity)?.launchExternalPlayer(real)
                }
            },
            onDone = done
        )
    }

    /** Controls stay up while a chooser is open; afterwards focus is back on its button. */
    private fun withOsdHeld(buttonId: Int, open: (() -> Unit) -> Unit) {
        playerOverlay.visibility = View.VISIBLE
        hideHandler.removeCallbacks(hideRunnable)
        open {
            if (isAdded && view != null) {
                playerOverlay.visibility = View.VISIBLE
                requireView().findViewById<View>(buttonId)?.requestFocus()
                resetHideTimer()
            }
        }
    }

    private fun applyAspectRatio(mode: String) {
        val p = exoPlayer ?: return
        LivePlayerOptions.applyAspect(playerView, p, mode)
    }

    // ------------------------------------------------------------------
    // OSD
    // ------------------------------------------------------------------

    private fun showOsd() {
        val wasHidden = playerOverlay.visibility != View.VISIBLE
        playerOverlay.visibility = View.VISIBLE
        updateProgress()
        if (wasHidden || playerOverlay.findFocus() == null) btnPlayPause.requestFocus()
        resetHideTimer()
    }

    private fun hideOsd() {
        playerOverlay.visibility = View.GONE
        // Focus back on the root so the next key press reaches it.
        view?.requestFocus()
    }

    private fun resetHideTimer() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, OSD_TIMEOUT_MS)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /** BACK with the controls up puts them away first. */
    fun handleBack(): Boolean {
        if (playerOverlay.visibility == View.VISIBLE) {
            hideHandler.removeCallbacks(hideRunnable)
            hideOsd()
            return true
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        exoPlayer?.play()
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onDestroyView() {
        view?.viewTreeObserver?.removeOnGlobalFocusChangeListener(focusWatcher)
        progressHandler.removeCallbacks(progressRunnable)
        hideHandler.removeCallbacks(hideRunnable)
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroyView()
    }
}
