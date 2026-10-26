package com.playboxpro.tv.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.playboxpro.tv.api.StalkerApi
import com.playboxpro.tv.api.XtreamApi
import com.playboxpro.tv.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("playbox_tv_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private companion object {
        /**
         * Shared by every repository instance in the process. The splash and
         * the view model each build their own AppRepository, and with a
         * per-instance field they both paid to parse the same multi-megabyte
         * library. There is only ever one signed-in playlist, so the parsed
         * copy belongs to the process, not to an instance.
         */
        val libraryState = kotlinx.coroutines.flow.MutableStateFlow<Library?>(null)

        /** Big enough that a multi-megabyte cache is not written in dribs. */
        const val CACHE_BUFFER = 1 shl 16
    }

    /** The process-wide library, and every change to it. */
    private var sharedLibrary: Library?
        get() = libraryState.value
        set(v) { libraryState.value = v }

    val libraryUpdates: kotlinx.coroutines.flow.StateFlow<Library?> get() = libraryState

    fun getCachedLibrary(): Library? = sharedLibrary

    fun setCachedLibrary(lib: Library) {
        sharedLibrary = lib
    }

    fun account(): Account? {
        val host = prefs.getString("account_host", null) ?: return null
        return Account(
            host = host,
            username = prefs.getString("account_user", "") ?: "",
            password = prefs.getString("account_pass", "") ?: "",
            type = prefs.getString("account_type", "m3u") ?: "m3u",
            epgUrl = prefs.getString("account_epg", "") ?: "",
            name = prefs.getString("account_name", "Primary Playlist") ?: "Primary Playlist"
        )
    }

    fun saveAccount(a: Account) {
        // A different playlist invalidates whatever the previous one loaded,
        // otherwise switching profiles would keep serving the old library.
        sharedLibrary = null
        prefs.edit()
            .putString("account_host", a.host)
            .putString("account_user", a.username)
            .putString("account_pass", a.password)
            .putString("account_type", a.type)
            .putString("account_epg", a.epgUrl)
            .putString("account_name", a.name)
            .putBoolean("account_active", true)
            .apply()
    }

    fun clearAccount() {
        sharedLibrary = null
        prefs.edit().remove("account_host").remove("account_active").apply()
    }

    fun isActive(): Boolean = prefs.getBoolean("account_active", false)
    fun setActive(v: Boolean) = prefs.edit().putBoolean("account_active", v).apply()

    // ------------------------------------------------------------------
    // Licence bookkeeping (see LicenseGate)
    // ------------------------------------------------------------------

    /** When the licensing server last confirmed this device, 0 if never. */
    fun licenseVerifiedAt(): Long = prefs.getLong("license_ok_at", 0L)

    fun markLicenseVerified(state: DeviceState) {
        prefs.edit()
            .putLong("license_ok_at", System.currentTimeMillis())
            .putString("license_expires_at", state.expiresAt.orEmpty())
            .remove("license_denied_reason")
            .apply()
    }

    /**
     * Takes playback away until the server grants it again. The playlist
     * credentials are kept so that re-activating the same playlist does not
     * cost a full re-download.
     */
    fun revokeAccess(reason: String) {
        sharedLibrary = null
        prefs.edit()
            .putBoolean("account_active", false)
            .putString("license_denied_reason", reason)
            .remove("license_ok_at")
            .apply()
    }

    fun licenseDeniedReason(): String = prefs.getString("license_denied_reason", "") ?: ""

    // Resume / Continue Watching
    fun saveResume(entry: ResumeEntry) {
        val list = getContinueWatching().toMutableList()
        list.removeAll { it.id == entry.id }
        if (entry.progress < 0.95f && entry.positionMs > 5000) {
            list.add(0, entry)
        }
        val trimmed = list.take(30)
        val json = gson.toJson(trimmed)
        prefs.edit().putString("continue_watching_json", json).apply()
    }

    fun getResume(id: String): ResumeEntry? {
        return getContinueWatching().firstOrNull { it.id == id }
    }

    fun getContinueWatching(): List<ResumeEntry> {
        val json = prefs.getString("continue_watching_json", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ResumeEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearResume(id: String) {
        val list = getContinueWatching().filterNot { it.id == id }
        val json = gson.toJson(list)
        prefs.edit().putString("continue_watching_json", json).apply()
    }

    // Favorites
    fun favorites(): Set<String> {
        return prefs.getStringSet("favorite_ids", emptySet()) ?: emptySet()
    }

    fun isFavorite(id: String): Boolean = favorites().contains(id)

    fun toggleFavorite(id: String): Boolean {
        val set = favorites().toMutableSet()
        val isFav = if (set.contains(id)) {
            set.remove(id)
            false
        } else {
            set.add(id)
            true
        }
        prefs.edit().putStringSet("favorite_ids", set).apply()
        return isFav
    }

    // Watch History (Recently Watched)
    fun getHistory(): List<HistoryEntry> {
        val json = prefs.getString("watch_history_json", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<HistoryEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun pushHistory(entry: HistoryEntry) {
        val list = getHistory().toMutableList()
        list.removeAll { it.id == entry.id }
        list.add(0, entry)
        val trimmed = list.take(100)
        val json = gson.toJson(trimmed)
        prefs.edit().putString("watch_history_json", json).apply()
    }

    fun clearHistory() {
        prefs.edit().remove("watch_history_json").apply()
    }

    // Recent items & channels
    fun recent(): List<String> {
        return prefs.getString("recent_items_str", "")!!.split('|').filter { it.isNotBlank() }
    }

    fun pushRecent(id: String) {
        val l = (listOf(id) + recent().filterNot { it == id }).take(40)
        prefs.edit().putString("recent_items_str", l.joinToString("|")).apply()
    }

    fun recentChannels(): List<String> {
        return prefs.getString("recent_channels_str", "")!!.split('|').filter { it.isNotBlank() }
    }

    fun pushRecentChannel(id: String) {
        val l = (listOf(id) + recentChannels().filterNot { it == id }).take(40)
        prefs.edit().putString("recent_channels_str", l.joinToString("|")).apply()
    }

    // Settings
    var autoplayNext: Boolean
        get() = prefs.getBoolean("pref_autoplay_next", true)
        set(v) = prefs.edit().putBoolean("pref_autoplay_next", v).apply()

    var rememberPosition: Boolean
        get() = prefs.getBoolean("pref_remember_pos", true)
        set(v) = prefs.edit().putBoolean("pref_remember_pos", v).apply()

    /** The legal terms have been accepted on this device. */
    var termsAccepted: Boolean
        get() = prefs.getBoolean("pref_terms_accepted", false)
        set(v) = prefs.edit().putBoolean("pref_terms_accepted", v).apply()

    /** Live TV picture size: auto, original, 16_9, 4_3, stretch, zoom. */
    var liveAspect: String
        get() = prefs.getString("pref_live_aspect", "auto") ?: "auto"
        set(v) = prefs.edit().putString("pref_live_aspect", v).apply()

    /** Audio language last picked on a channel; "" when never chosen. */
    fun liveAudioFor(channelId: String): String =
        prefs.getString("pref_live_audio_$channelId", "") ?: ""

    fun setLiveAudioFor(channelId: String, language: String) =
        prefs.edit().putString("pref_live_audio_$channelId", language).apply()

    /** Subtitle choice for a channel or title: "" = never set, "off", or a track key. */
    fun subtitleFor(itemId: String): String =
        prefs.getString("pref_subs_$itemId", "") ?: ""

    fun setSubtitleFor(itemId: String, key: String) =
        prefs.edit().putString("pref_subs_$itemId", key).apply()

    var aspectRatio: String
        get() = prefs.getString("pref_aspect_ratio", "fit") ?: "fit"
        set(v) = prefs.edit().putString("pref_aspect_ratio", v).apply()

    var subtitleSize: String
        get() = prefs.getString("pref_subtitle_size", "medium") ?: "medium"
        set(v) = prefs.edit().putString("pref_subtitle_size", v).apply()

    var theme: String
        get() = prefs.getString("pref_theme", "default") ?: "default"
        set(v) = prefs.edit().putString("pref_theme", v).apply()

    // ------------------------------------------------------------------
    // Settings backing the STB+ tile grid
    // ------------------------------------------------------------------

    /** Category ids the user chose to hide, per section. */
    fun hiddenCategories(kind: String): Set<String> =
        prefs.getStringSet("pref_hidden_cats_$kind", emptySet()) ?: emptySet()

    fun setHiddenCategories(kind: String, ids: Set<String>) {
        // A new Set instance is required; SharedPreferences does not detect
        // mutations of the set it handed out.
        prefs.edit().putStringSet("pref_hidden_cats_$kind", HashSet(ids)).apply()
    }

    /** Categories the user locked behind the PIN (STB+ long-press on a category). */
    fun lockedCategories(kind: String): Set<String> =
        prefs.getStringSet("pref_locked_cats_$kind", emptySet()) ?: emptySet()

    fun setLockedCategories(kind: String, ids: Set<String>) {
        prefs.edit().putStringSet("pref_locked_cats_$kind", HashSet(ids)).apply()
    }

    /** STB+ ships with 0000 until the user sets their own. */
    fun effectivePin(): String = parentPin.ifBlank { "0000" }

    /** "hls" streams the m3u8 variant, "ts" the MPEG-TS one. */
    var streamFormat: String
        get() = prefs.getString("pref_stream_format", "hls") ?: "hls"
        set(v) = prefs.edit().putString("pref_stream_format", v).apply()

    var liveInternalPlayer: Boolean
        get() = prefs.getBoolean("pref_live_internal", true)
        set(v) = prefs.edit().putBoolean("pref_live_internal", v).apply()

    var vodInternalPlayer: Boolean
        get() = prefs.getBoolean("pref_vod_internal", true)
        set(v) = prefs.edit().putBoolean("pref_vod_internal", v).apply()

    // ------------------------------------------------------------------
    // EPG
    // ------------------------------------------------------------------

    private class EpgEntry(val at: Long, val full: Boolean, val programmes: List<EpgProgram>)

    private val epgCache = android.util.LruCache<String, EpgEntry>(300)

    /** Guide data is re-read after this long so the "now" entry stays right. */
    private val epgTtlMs = 10 * 60 * 1000L

    /**
     * The guide for [channel], from memory when it was read recently. Only
     * Xtream playlists carry a guide; anything else yields nothing. [full]
     * asks for every day the server has instead of just now and next.
     * Times are shifted by the EPG offset chosen in Settings.
     */
    suspend fun epgFor(channel: Channel, full: Boolean): List<EpgProgram> {
        val a = account() ?: return emptyList()
        val stalker = a.type == "stalker" && channel.id.startsWith("stk:")
        if (!stalker && (a.type != "xtream" || !channel.id.startsWith("live:"))) return emptyList()
        val streamId = channel.id.removePrefix("live:").removePrefix("stk:")
        val key = channel.id
        val now = System.currentTimeMillis()

        epgCache.get(key)?.let { hit ->
            if ((hit.full || !full) && now - hit.at < epgTtlMs) return shifted(hit.programmes)
        }

        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                if (stalker) StalkerApi.shortEpg(StalkerApi.session(a.host, a.username), streamId, if (full) 30 else 4)
                else XtreamApi.epg(a, streamId, if (full) null else 4)
            }.getOrNull()
        } ?: return epgCache.get(key)?.let { shifted(it.programmes) } ?: emptyList()

        epgCache.put(key, EpgEntry(now, full, fetched))
        return shifted(fetched)
    }

    private fun shifted(list: List<EpgProgram>): List<EpgProgram> {
        val delta = epgTimeOffset * 60L * 60L * 1000L
        if (delta == 0L) return list
        return list.map { it.copy(start = it.start + delta, end = it.end + delta) }
    }

    /**
     * Sort order for a catalogue ("movies" / "series"), as chosen from the
     * Select Sort Type dialog. Kept per section, as STB+ does, so sorting
     * films by rating does not reorder the series page too.
     */
    fun catalogSort(kind: String): String =
        prefs.getString("pref_sort_$kind", "added") ?: "added"

    fun setCatalogSort(kind: String, value: String) =
        prefs.edit().putString("pref_sort_$kind", value).apply()

    /** Hours to shift EPG programme times by. */
    var epgTimeOffset: Int
        get() = prefs.getInt("pref_epg_offset", 0)
        set(v) = prefs.edit().putInt("pref_epg_offset", v).apply()

    /** Empty means parental control is off. */
    var parentPin: String
        get() = prefs.getString("pref_parent_pin", "") ?: ""
        set(v) = prefs.edit().putString("pref_parent_pin", v).apply()

    var lockAdult: Boolean
        get() = prefs.getBoolean("pref_lock_adult", false)
        set(v) = prefs.edit().putBoolean("pref_lock_adult", v).apply()

    /** Drops the in-memory and on-disk library so the next load refetches. */
    fun clearCache() {
        sharedLibrary = null
        runCatching { liveCacheFile().delete() }
        runCatching { vodCacheFile().delete() }
    }

    // ------------------------------------------------------------------
    // On-disk library cache
    //
    // Parsing a large playlist takes seconds on a TV box. Keeping the parsed
    // result on disk lets a cold start paint immediately while a fresh copy
    // is fetched in the background.
    // ------------------------------------------------------------------

    /**
     * Bumped whenever what is saved changes shape or meaning (new fields,
     * a different live / movie split), so an older saved playlist is read
     * fresh once instead of being trusted for hours.
     */
    private val CACHE_FORMAT = 2

    private fun cacheKey(): String =
        (account()?.host ?: "none").hashCode().toString().replace("-", "m") + "_v$CACHE_FORMAT"

    /** Saved playlists from other accounts or older formats. */
    private fun deleteStaleCaches() = runCatching {
        val keep = setOf(liveCacheFile().name, vodCacheFile().name)
        context.cacheDir.listFiles { f -> f.name.startsWith("lib_") && f.name.endsWith(".json.gz") }
            ?.filter { it.name !in keep }
            ?.forEach { it.delete() }
    }

    /** Channels and every category list - all the default screen needs. */
    private fun liveCacheFile() = java.io.File(context.cacheDir, "lib_live_${cacheKey()}.json.gz")

    /** Movies and series, parsed only once something actually asks for them. */
    private fun vodCacheFile() = java.io.File(context.cacheDir, "lib_vod_${cacheKey()}.json.gz")

    private data class VodCache(
        val movies: List<Movie> = emptyList(),
        val series: List<Series> = emptyList()
    )

    private fun writeGz(file: java.io.File, value: Any, type: java.lang.reflect.Type) {
        // Streamed rather than serialised into one giant String first - a
        // library this size would otherwise be held in memory twice - and
        // gzipped, because this playlist alone is ~48MB of JSON and a TV box
        // reads its flash a great deal slower than it inflates.
        java.util.zip.GZIPOutputStream(file.outputStream().buffered(CACHE_BUFFER)).use { gz ->
            gz.writer().buffered(CACHE_BUFFER).use { out ->
                gson.toJson(value, type, com.google.gson.stream.JsonWriter(out))
            }
        }
    }

    private fun <T> readGz(file: java.io.File, type: Class<T>): T? {
        if (!file.exists()) return null
        return java.util.zip.GZIPInputStream(file.inputStream().buffered(CACHE_BUFFER)).use { gz ->
            gz.reader().buffered(CACHE_BUFFER).use { reader ->
                gson.fromJson(com.google.gson.stream.JsonReader(reader), type)
            }
        }
    }

    fun saveLibraryToDisk(lib: Library, includeVod: Boolean = true) {
        deleteStaleCaches()
        runCatching {
            writeGz(liveCacheFile(), lib.copy(movies = emptyList(), series = emptyList()), Library::class.java)
            if (includeVod) writeGz(vodCacheFile(), VodCache(lib.movies, lib.series), VodCache::class.java)
        }
    }

    /**
     * Whether a cache is on disk, without paying to parse it. Parsing a
     * library this size takes seconds, so nothing should do it merely to
     * answer "is there one?".
     */
    fun hasLibraryCache(): Boolean = runCatching {
        val file = liveCacheFile()
        file.exists() && file.length() > 32
    }.getOrDefault(false)

    /**
     * How long ago the cache was written, or [Long.MAX_VALUE] if there is
     * none. The splash primes this cache, so the screen behind it can skip a
     * refresh it would only be repeating.
     */
    fun libraryCacheAgeMs(): Long = runCatching {
        val file = liveCacheFile()
        if (!file.exists()) Long.MAX_VALUE
        else System.currentTimeMillis() - file.lastModified()
    }.getOrDefault(Long.MAX_VALUE)

    /**
     * Reads the cache back.
     *
     * With [includeVod] false only the live half is parsed. That half is a
     * fraction of the size, and it is everything the default screen draws -
     * the catalogue is filled in afterwards by [loadVodFromDisk] rather than
     * holding up start-up for tens of thousands of titles nobody has asked
     * to see yet.
     */
    fun loadLibraryFromDisk(includeVod: Boolean = true): Library? = runCatching {
        val live = readGz(liveCacheFile(), Library::class.java) ?: return@runCatching null

        if (!includeVod) {
            // A playlist can legitimately be VOD-only, so an empty channel
            // list is not on its own proof of a worthless cache.
            return@runCatching if (live.channels.isEmpty() && !vodCacheFile().exists()) null else live
        }

        val merged = mergeVod(live)
        if (merged.channels.isEmpty() && merged.movies.isEmpty() && merged.series.isEmpty()) {
            null
        } else {
            merged
        }
    }.getOrNull()

    /** Fills the catalogue into an already-loaded library. */
    fun loadVodFromDisk(base: Library): Library? = runCatching { mergeVod(base) }
        .onFailure { android.util.Log.e("AppRepository", "catalogue cache unreadable", it) }
        .getOrNull()

    private fun mergeVod(base: Library): Library {
        val vod = readGz(vodCacheFile(), VodCache::class.java) ?: return base
        return base.copy(movies = vod.movies, series = vod.series)
    }

    /**
     * Loads the playlist. With [onLive], the channels and category lists are
     * handed over the moment they are in, before the far larger movie and
     * series listings are fetched - so the first screen can open in seconds
     * instead of waiting on the whole catalogue.
     */
    suspend fun loadLibrary(
        force: Boolean = false,
        onLive: (suspend (Library) -> Unit)? = null
    ): Library = withContext(Dispatchers.IO) {
        if (!force && sharedLibrary != null && (sharedLibrary!!.channels.isNotEmpty() || sharedLibrary!!.movies.isNotEmpty() || sharedLibrary!!.series.isNotEmpty())) {
            return@withContext sharedLibrary!!
        }
        val a = account() ?: error("No playlist configured")

        val lib = if (a.type == "stalker") {
            loadFromStalker(a, onLive)
        } else if (a.type != "m3u") {
            loadFromXtream(a, onLive)
        } else {
            // The playlist may be recorded as M3U while actually being an
            // Xtream get.php export. Providers routinely disable get.php and
            // leave player_api.php running, which made a perfectly good
            // account fail with a bare 404 - so try the API first and only
            // fall back to fetching the raw playlist.
            val asXtream = xtreamAccountFrom(a)
            val apiOk = asXtream != null &&
                runCatching { XtreamApi.authenticate(asXtream) }.isSuccess

            if (apiOk) {
                // Persist the upgrade first so the cache lands under the
                // account it belongs to, and detail lookups and stream URLs
                // use the API too instead of the dead get.php link.
                upgradeAccountToXtream(asXtream!!)
                loadFromXtream(asXtream, onLive)
            } else {
                M3uParser.load(a.host).also { onLive?.invoke(it) }
            }
        }

        sharedLibrary = lib
        saveLibraryToDisk(lib)
        lib
    }

    /**
     * A Stalker / MAG portal playlist. Live TV is published as soon as the
     * channels are in, exactly as for Xtream; films and series are read
     * category by category behind it, a few at a time, because portals hand
     * them out a page (often 14 titles) at a time.
     */
    private suspend fun loadFromStalker(
        a: Account,
        onLive: (suspend (Library) -> Unit)? = null
    ): Library = coroutineScope {
        val s = withContext(Dispatchers.IO) { StalkerApi.session(a.host, a.username) }

        val lcJob = async(Dispatchers.IO) { StalkerApi.liveCategories(s) }
        val mcJob = async(Dispatchers.IO) { runCatching { StalkerApi.vodCategories(s) }.getOrDefault(emptyList()) }
        val scJob = async(Dispatchers.IO) { runCatching { StalkerApi.seriesCategories(s) }.getOrDefault(emptyList()) }
        val rawLc = lcJob.await(); val rawMc = mcJob.await(); val rawSc = scJob.await()

        val channels = withContext(Dispatchers.IO) { StalkerApi.channels(s, rawLc.associate { it.id to it.name }) }
        val liveCounts = channels.groupingBy { it.categoryId }.eachCount()
        val lc = rawLc.map { it.copy(count = liveCounts[it.id] ?: 0) }

        if (onLive != null) {
            val livePart = Library(channels = channels, liveCategories = lc, movieCategories = rawMc, seriesCategories = rawSc)
            sharedLibrary = livePart
            saveLibraryToDisk(livePart, includeVod = false)
            onLive(livePart)
        }

        val gate = kotlinx.coroutines.sync.Semaphore(6)
        suspend fun <T> limited(block: () -> T): T = gate.withPermit { withContext(Dispatchers.IO) { block() } }

        // Films; old portals also keep their series in here, flagged per item.
        val vodRows = rawMc.map { cat ->
            async {
                val rows = runCatching { limited { StalkerApi.categoryItems(s, "vod", cat.id) } }.getOrDefault(emptyList())
                cat to rows
            }
        }.awaitAll()
        val movies = ArrayList<Movie>()
        val vodSeries = ArrayList<Series>()
        for ((cat, rows) in vodRows) for (o in rows) {
            val map = mapOf(cat.id to cat.name)
            if (o.optString("is_series") == "1") {
                StalkerApi.seriesFrom(s, o, "vod", map)?.let { vodSeries += it.copy(categoryId = cat.id, categoryName = cat.name) }
            } else {
                StalkerApi.movieFrom(s, o, map)?.let { movies += it.copy(categoryId = cat.id, categoryName = cat.name) }
            }
        }

        // The portal's own series section, when it has one.
        val seriesRows = rawSc.map { cat ->
            async {
                val rows = runCatching { limited { StalkerApi.categoryItems(s, "series", cat.id) } }.getOrDefault(emptyList())
                cat to rows
            }
        }.awaitAll()
        val series = ArrayList<Series>()
        for ((cat, rows) in seriesRows) for (o in rows) {
            StalkerApi.seriesFrom(s, o, "series", mapOf(cat.id to cat.name))
                ?.let { series += it.copy(categoryId = cat.id, categoryName = cat.name) }
        }
        series += vodSeries

        val uniqueMovies = movies.distinctBy { it.id }
        val uniqueSeries = series.distinctBy { it.id }
        val movieCounts = uniqueMovies.groupingBy { it.categoryId }.eachCount()
        val seriesCounts = uniqueSeries.groupingBy { it.categoryId }.eachCount()

        // Series found among the films keep their film category as a row.
        val seriesCats = (rawSc + rawMc.filter { c -> vodSeries.any { it.categoryId == c.id } })
            .distinctBy { it.id }
            .map { it.copy(kind = "series", count = seriesCounts[it.id] ?: 0) }
            .filter { it.count > 0 }

        Library(
            channels = channels,
            movies = uniqueMovies,
            series = uniqueSeries,
            liveCategories = lc,
            movieCategories = rawMc.map { it.copy(count = movieCounts[it.id] ?: 0) }.filter { it.count > 0 },
            seriesCategories = seriesCats
        )
    }

    /**
     * The address a player should open. Portal items are stored as their
     * play command and turned into a fresh, short-lived link here; anything
     * else is already a stream address.
     */
    suspend fun resolveStream(url: String): String = withContext(Dispatchers.IO) {
        if (!StalkerApi.isStalker(url)) return@withContext url
        val a = account() ?: return@withContext url
        StalkerApi.resolve(StalkerApi.session(a.host, a.username), url)
    }

    private suspend fun loadFromXtream(
        a: Account,
        onLive: (suspend (Library) -> Unit)? = null
    ): Library = coroutineScope {
        withContext(Dispatchers.IO) { applyAllowedOutputFormats(XtreamApi.authenticate(a)) }

        // The three category lists are small and are needed to name the rows,
        // so they are fetched together first; the three big listings then run
        // concurrently. Downloading them one after another wasted most of the
        // wait on a connection that was sitting idle.
        val rawLc: List<Category>
        val rawMc: List<Category>
        val rawSc: List<Category>
        run {
            val live = async(Dispatchers.IO) { XtreamApi.liveCategories(a) }
            val movie = async(Dispatchers.IO) { XtreamApi.movieCategories(a) }
            val ser = async(Dispatchers.IO) { XtreamApi.seriesCategories(a) }
            rawLc = live.await(); rawMc = movie.await(); rawSc = ser.await()
        }

        val liveCatMap = rawLc.associate { it.id to it.name }
        val movieCatMap = rawMc.associate { it.id to it.name }
        val seriesCatMap = rawSc.associate { it.id to it.name }

        // Channels first, alone: they are what the first screen needs, and a
        // 27MB catalogue download running beside them would only take the
        // bandwidth and CPU they need to arrive quickly.
        val channels = withContext(Dispatchers.IO) { XtreamApi.channels(a, catMap = liveCatMap) }
        val liveCounts0 = channels.groupingBy { it.categoryId }.eachCount()

        // Live TV is the first screen and it only needs this much. Publish
        // it now; the catalogue keeps downloading behind it.
        val livePart = Library(
            channels = channels,
            liveCategories = rawLc.map { cat -> cat.copy(count = liveCounts0[cat.id] ?: 0) },
            movieCategories = rawMc,
            seriesCategories = rawSc,
            epgUrl = XtreamApi.xmltvUrl(a)
        )
        if (onLive != null) {
            sharedLibrary = livePart
            saveLibraryToDisk(livePart, includeVod = false)
            onLive(livePart)
        }

        val moviesJob = async(Dispatchers.IO) { XtreamApi.movies(a, catMap = movieCatMap) }
        val seriesJob = async(Dispatchers.IO) { XtreamApi.series(a, catMap = seriesCatMap) }
        val movies = moviesJob.await()
        val series = seriesJob.await()

        // Counting per category was O(categories x items) - about 9 million
        // comparisons for this playlist. One pass builds the same totals.
        val liveCounts = channels.groupingBy { it.categoryId }.eachCount()
        val movieCounts = movies.groupingBy { it.categoryId }.eachCount()
        val seriesCounts = series.groupingBy { it.categoryId }.eachCount()

        val lc = rawLc.map { cat -> cat.copy(count = liveCounts[cat.id] ?: 0) }
        val mc = rawMc.map { cat -> cat.copy(count = movieCounts[cat.id] ?: 0) }
        val sc = rawSc.map { cat -> cat.copy(count = seriesCounts[cat.id] ?: 0) }

        Library(
            channels = channels,
            movies = movies,
            series = series,
            liveCategories = lc,
            movieCategories = mc,
            seriesCategories = sc,
            epgUrl = XtreamApi.xmltvUrl(a)
        )
    }

    /**
     * Follows what the account is actually permitted to stream.
     *
     * Providers advertise their containers in `allowed_output_formats`. Plenty
     * of them serve MPEG-TS only and answer an .m3u8 request with a 405, which
     * would leave every channel unplayable while the playlist itself looked
     * perfectly healthy. An existing preference is only overridden when it
     * cannot work.
     */
    private fun applyAllowedOutputFormats(auth: org.json.JSONObject) {
        val info = auth.optJSONObject("user_info") ?: return
        val allowed = info.optJSONArray("allowed_output_formats") ?: return

        val formats = (0 until allowed.length())
            .map { allowed.optString(it).trim().lowercase() }
            .filter { it.isNotBlank() }
        if (formats.isEmpty()) return

        val hlsAllowed = formats.any { it == "m3u8" || it == "hls" }
        val tsAllowed = formats.any { it == "ts" || it == "mpegts" }

        if (!hlsAllowed && tsAllowed) streamFormat = "ts"
    }

    /**
     * Reads an Xtream account out of a `get.php` playlist link, or null if the
     * URL is an ordinary M3U that carries no credentials.
     */
    private fun xtreamAccountFrom(a: Account): Account? {
        val uri = runCatching { java.net.URI(a.host.trim()) }.getOrNull() ?: return null
        if (!uri.path.orEmpty().trimEnd('/').endsWith("get.php", ignoreCase = true)) return null

        val params = uri.rawQuery.orEmpty()
            .split("&")
            .mapNotNull { pair ->
                val i = pair.indexOf('=')
                if (i <= 0) null else runCatching {
                    java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8") to
                        java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8")
                }.getOrNull()
            }
            .toMap()

        val user = params["username"].orEmpty()
        val pass = params["password"].orEmpty()
        val host = uri.host ?: return null
        if (user.isBlank() || pass.isBlank()) return null

        val scheme = uri.scheme?.ifBlank { null } ?: "http"
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return a.copy(
            host = "$scheme://$host$port",
            username = user,
            password = pass,
            type = "xtream"
        )
    }

    /**
     * Rewrites the stored account in place. Unlike [saveAccount] this keeps
     * the library that was just loaded - it is the same playlist, only
     * reached by a different route.
     */
    private fun upgradeAccountToXtream(a: Account) {
        prefs.edit()
            .putString("account_host", a.host)
            .putString("account_user", a.username)
            .putString("account_pass", a.password)
            .putString("account_type", a.type)
            .apply()
    }

    // ------------------------------------------------------------------
    // Movie / series details, cached
    //
    // A title's full details (plot, cast, seasons and episodes) are kept in
    // memory and on disk. Opening it again shows the saved copy at once,
    // online or not; while online the copy is refreshed quietly behind the
    // page, so the next visit is current.
    // ------------------------------------------------------------------

    private val infoMemory = object : android.util.LruCache<String, Any>(200) {}
    private val infoRefreshed = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun infoFile(kind: String, id: String) =
        java.io.File(java.io.File(context.cacheDir, "info_${cacheKey()}").apply { mkdirs() },
            "${kind}_${id.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.json.gz")

    private fun <T> readInfo(kind: String, id: String, type: Class<T>): T? {
        @Suppress("UNCHECKED_CAST")
        (infoMemory.get("$kind:$id") as? T)?.let { return it }
        val f = infoFile(kind, id)
        if (!f.exists()) return null
        return runCatching {
            java.util.zip.GZIPInputStream(f.inputStream().buffered()).use { gz ->
                gson.fromJson(gz.reader(), type)
            }
        }.getOrNull()?.also { infoMemory.put("$kind:$id", it) }
    }

    private fun writeInfo(kind: String, id: String, value: Any) {
        infoMemory.put("$kind:$id", value)
        runCatching {
            java.util.zip.GZIPOutputStream(infoFile(kind, id).outputStream().buffered()).use { gz ->
                gz.writer().use { w -> gson.toJson(value, w) }
            }
        }
    }

    /** Series details already saved, without touching the network. */
    fun cachedSeries(id: String): Series? = readInfo("series", id, Series::class.java)

    suspend fun movie(id: String): Movie = withContext(Dispatchers.IO) {
        val a = account() ?: error("No account")
        val base = sharedLibrary?.movies?.firstOrNull { it.id == id }
        if (a.type == "m3u" || a.type == "stalker") return@withContext base ?: Movie(id, "Movie")

        val cached = readInfo("movie", id, Movie::class.java)
        if (cached != null) {
            refreshInfoOnce("movie:$id") { fetchMovie(a, id, base) }
            return@withContext cached
        }
        fetchMovie(a, id, base) ?: base ?: Movie(id, "Movie")
    }

    suspend fun series(id: String): Series = withContext(Dispatchers.IO) {
        val a = account() ?: error("No account")
        val base = sharedLibrary?.series?.firstOrNull { it.id == id }
        if (a.type == "m3u") return@withContext base ?: Series(id, "Series")
        if (a.type == "stalker") return@withContext stalkerSeries(a, id, base)

        val cached = readInfo("series", id, Series::class.java)
        if (cached != null) {
            refreshInfoOnce("series:$id") { fetchSeries(a, id, base) }
            return@withContext cached
        }
        fetchSeries(a, id, base) ?: base ?: Series(id, "Series")
    }

    /** Portal series: seasons and episodes come from the portal's series section. */
    private fun stalkerSeries(a: Account, id: String, base: Series?): Series {
        val fallback = base ?: Series(id, "Series")
        if (fallback.seasons.isNotEmpty()) return fallback
        readInfo("series", id, Series::class.java)?.let { cached ->
            refreshInfoOnce("series:$id") { fetchStalkerSeries(a, id, fallback) }
            return cached
        }
        return fetchStalkerSeries(a, id, fallback) ?: fallback
    }

    private fun fetchStalkerSeries(a: Account, id: String, base: Series): Series? = runCatching {
        val portalId = id.substringAfterLast(":")
        val seasons = StalkerApi.seasons(StalkerApi.session(a.host, a.username), portalId)
        base.copy(seasons = seasons).also { if (seasons.isNotEmpty()) writeInfo("series", id, it) }
    }.getOrNull()

    /** One background refresh per title per app session. */
    private fun refreshInfoOnce(key: String, fetch: suspend () -> Any?) {
        if (!infoRefreshed.add(key)) return
        com.playboxpro.tv.PlayBoxApp.scope.launch(Dispatchers.IO) { runCatching { fetch() } }
    }

    private fun fetchMovie(a: Account, id: String, base: Movie?): Movie? = try {
        val full = XtreamApi.movieInfo(a, id)
        full.copy(
            categoryId = base?.categoryId ?: full.categoryId,
            categoryName = base?.categoryName ?: full.categoryName,
            backdrop = full.backdrop.ifBlank { base?.backdrop.orEmpty() },
            poster = full.poster.ifBlank { base?.poster.orEmpty() },
            genre = full.genre.ifBlank { base?.genre.orEmpty() }
        ).also { writeInfo("movie", id, it) }
    } catch (e: Exception) {
        android.util.Log.w("AppRepository", "movie info failed for $id", e)
        null
    }

    private fun fetchSeries(a: Account, id: String, base: Series?): Series? = try {
        val full = XtreamApi.seriesInfo(a, id)
        full.copy(
            categoryId = base?.categoryId ?: full.categoryId,
            categoryName = base?.categoryName ?: full.categoryName,
            backdrop = full.backdrop.ifBlank { base?.backdrop.orEmpty() },
            poster = full.poster.ifBlank { base?.poster.orEmpty() },
            genre = full.genre.ifBlank { base?.genre.orEmpty() }
        ).also { if (it.seasons.isNotEmpty()) writeInfo("series", id, it) }
    } catch (e: Exception) {
        null
    }
}
