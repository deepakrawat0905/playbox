package com.playboxpro.tv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playboxpro.tv.api.LicenseApi
import com.playboxpro.tv.data.AppRepository
import com.playboxpro.tv.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private companion object {
        /**
         * A playlist saved more recently than this is used as it is. A
         * large playlist is tens of megabytes to download and parse, and
         * doing that behind every launch is what made the box sluggish;
         * choosing the profile again fetches a fresh copy on demand.
         */
        const val FRESH_CACHE_MS = 6L * 60 * 60 * 1000
    }

    val repo = AppRepository(app)

    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error

    private val _continueWatching = MutableStateFlow<List<ResumeEntry>>(emptyList())
    val continueWatching: StateFlow<List<ResumeEntry>> = _continueWatching

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites

    private val _deviceState = MutableStateFlow<DeviceState?>(null)
    val deviceState: StateFlow<DeviceState?> = _deviceState

    // ------------------------------------------------------------------
    // Live session: the channel list the full-screen player walks through
    // with UP / DOWN, and where it currently is. Held here so the Live
    // screen can land back on whatever channel the player left off on.
    // ------------------------------------------------------------------

    var liveSession: List<Channel> = emptyList()
        private set

    private val _liveIndex = MutableStateFlow(-1)
    val liveIndex: StateFlow<Int> = _liveIndex

    fun startLiveSession(channels: List<Channel>, index: Int) {
        liveSession = channels
        _liveIndex.value = index.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
    }

    fun setLiveIndex(index: Int) {
        if (index in liveSession.indices) _liveIndex.value = index
    }

    val mac = LicenseApi.deviceMac(app)
    val deviceKey = LicenseApi.deviceKey(mac)

    init {
        refreshUserData()

        // Whatever screen is open, the catalogue is in place a minute after start.
        viewModelScope.launch { kotlinx.coroutines.delay(60_000); wantCatalogue() }

        // Whatever lands in the shared library - the live half from the
        // splash, the catalogue a little later - reaches every screen from
        // here. Screens collect [library], so they fill themselves in.
        viewModelScope.launch {
            repo.libraryUpdates.collect { lib ->
                if (lib != null && lib !== _library.value) {
                    _library.value = lib
                    refreshUserData()
                    // A refresh publishes its live half first, which would
                    // otherwise empty Movies and Series for the minutes the
                    // catalogue download takes. The copy on disk stands in
                    // until the fresh one arrives.
                    fillCatalogueInBackground(lib)
                }
            }
        }
    }

    fun refreshUserData() {
        _continueWatching.value = repo.getContinueWatching()
        _history.value = repo.getHistory()
        _favorites.value = repo.favorites()
    }

    fun pushHistory(entry: HistoryEntry) {
        repo.pushHistory(entry)
        _history.value = repo.getHistory()
    }

    fun clearHistory() {
        repo.clearHistory()
        _history.value = emptyList()
    }

    fun clearError() {
        _error.value = ""
    }

    fun connect(a: Account, onDone: () -> Unit = {}) {
        _loading.value = true
        _error.value = ""
        repo.saveAccount(a)
        viewModelScope.launch {
            try {
                // Always reload: connect() means the user picked this account,
                // so any library held from a previous one must not be reused.
                // Live TV opens as soon as the channels are in; the catalogue
                // finishes behind it.
                var opened = false
                val lib = repo.loadLibrary(force = true, onLive = { live ->
                    withContext(Dispatchers.Main) {
                        _library.value = live
                        refreshUserData()
                        if (!opened) { opened = true; onDone() }
                    }
                })
                _library.value = lib
                refreshUserData()
                if (!opened) onDone()
            } catch (e: Exception) {
                _error.value = e.message ?: "Unable to load playlist"
                repo.setActive(false)
            } finally {
                _loading.value = false
            }
        }
    }

    /** True while a background refresh is downloading. */
    private var refreshing = false

    /**
     * Movies and series are read from disk only once a screen needs them
     * (or a minute after start). The catalogue is tens of thousands of
     * titles; reading it while Live TV opens made the first half-minute slow.
     */
    private var catalogueWanted = false

    /** One catalogue download per session when none is saved. */
    private var vodDownloadTried = false

    /** Screens that show movies / series call this; the catalogue is filled in once. */
    fun wantCatalogue() {
        if (catalogueWanted) return
        catalogueWanted = true
        fillCatalogueInBackground(_library.value)
    }

    fun load(catalogue: Boolean = true) {
        if (catalogue) catalogueWanted = true
        if (repo.account() == null) return
        val current = _library.value
        if (current.channels.isNotEmpty() || current.movies.isNotEmpty() || current.series.isNotEmpty()) {
            // Already painted (usually straight from the splash). The
            // catalogue on disk still has to be grafted on, and a cache
            // older than the splash's own fetch is refreshed behind the page.
            refreshUserData()
            fillCatalogueInBackground(current)
            if (repo.libraryCacheAgeMs() >= FRESH_CACHE_MS) refreshInBackground(if (catalogue) 0L else 45_000L)
            return
        }

        viewModelScope.launch {
            // Paint from the last parsed copy first so a cold start is not
            // stuck behind a download and a full re-parse. The splash will
            // usually have left one in memory already.
            val cached = repo.getCachedLibrary()
                ?: withContext(Dispatchers.IO) { repo.loadLibraryFromDisk(includeVod = catalogueWanted) }
            if (cached != null) {
                _library.value = cached
                repo.setCachedLibrary(cached)
                refreshUserData()

                // The catalogue on disk is usable right away whatever happens
                // next; a background refresh must never leave Movies / Series
                // empty for the minutes a full download can take on a box.
                fillCatalogueInBackground(cached)

                // The splash has just fetched this; refreshing again here
                // would be a second download for nothing.
                _loading.value = false
                // An older copy is refreshed behind the screen, once it has settled.
                if (repo.libraryCacheAgeMs() >= FRESH_CACHE_MS) refreshInBackground(if (catalogueWanted) 0L else 45_000L)
                return@launch
            }

            _loading.value = cached == null
            try {
                // Then refresh in the background; the cache is only a head start.
                val fresh = repo.loadLibrary(force = true)
                _library.value = fresh
                refreshUserData()
            } catch (e: Exception) {
                // A stale cache still beats an error screen.
                if (cached == null) _error.value = e.message ?: "Unable to load playlist"
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Re-downloads the playlist without disturbing what is on screen; the
     * shared-library collector swaps the result in when it is complete.
     */
    private fun refreshInBackground(delayMs: Long = 0L) {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            // Started from Live TV, the download waits until the first screen
            // has settled, so opening the app stays quick.
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            try {
                repo.loadLibrary(force = true)
            } catch (_: Exception) {
                // Stale is fine; the cache stays.
            } finally {
                refreshing = false
            }
        }
    }

    /**
     * Parses the catalogue after the first screen is already up.
     *
     * Movies and series are the bulk of a large playlist and none of it is on
     * screen when the app opens, so it is read once Live TV is drawn. Every
     * catalogue screen collects [library], so they pick this up on their own.
     */
    private fun fillCatalogueInBackground(base: Library) {
        if (!catalogueWanted) return
        if (base.movies.isNotEmpty() || base.series.isNotEmpty()) return

        viewModelScope.launch {
            val full = withContext(Dispatchers.IO) { repo.loadVodFromDisk(base) }
            if (full == null || (full.movies.isEmpty() && full.series.isEmpty())) {
                // No catalogue saved yet (the app was closed during the first
                // download): fetch it now rather than wait for the next refresh.
                if (!vodDownloadTried) { vodDownloadTried = true; refreshInBackground(0L) }
                return@launch
            }

            // If a refresh replaced the live half meanwhile, graft the disk
            // catalogue onto that rather than throwing it away; a completed
            // refresh already carries its own catalogue and is left alone.
            val current = _library.value
            val merged = when {
                current === base -> full
                current.movies.isEmpty() && current.series.isEmpty() ->
                    current.copy(movies = full.movies, series = full.series)
                else -> return@launch
            }
            _library.value = merged
            repo.setCachedLibrary(merged)
        }
    }

    fun forceRefresh(onDone: () -> Unit = {}) {
        if (repo.account() == null) return
        _loading.value = true
        viewModelScope.launch {
            try {
                _library.value = repo.loadLibrary(force = true)
                refreshUserData()
                onDone()
            } catch (e: Exception) {
                _error.value = e.message ?: "Unable to refresh playlist"
            } finally {
                _loading.value = false
            }
        }
    }

    fun toggleFavorite(id: String): Boolean {
        val result = repo.toggleFavorite(id)
        _favorites.value = repo.favorites()
        return result
    }

    fun savePlaybackProgress(
        id: String,
        title: String,
        poster: String,
        backdrop: String,
        positionMs: Long,
        durationMs: Long,
        kind: String,
        seriesId: String = "",
        season: Int = 1,
        episode: Int = 1,
        url: String = ""
    ) {
        if (!repo.rememberPosition) return
        val entry = ResumeEntry(
            id = id,
            title = title,
            poster = poster,
            backdrop = backdrop,
            positionMs = positionMs,
            durationMs = durationMs,
            kind = kind,
            seriesId = seriesId,
            seasonNumber = season,
            episodeNumber = episode,
            url = url
        )
        repo.saveResume(entry)
        _continueWatching.value = repo.getContinueWatching()
    }

    fun clearResume(id: String) {
        repo.clearResume(id)
        _continueWatching.value = repo.getContinueWatching()
    }

    fun logout() {
        repo.clearAccount()
        _library.value = Library()
        _continueWatching.value = emptyList()
    }
}
