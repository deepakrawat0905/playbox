package com.playboxpro.tv.model

data class Account(
    val host: String,
    val username: String = "",
    val password: String = "",
    val type: String = "m3u",
    val epgUrl: String = "",
    val name: String = "Default Playlist"
)

data class Category(
    val id: String,
    val name: String,
    val kind: String,
    val count: Int = 0,
    val adult: Boolean = false
)

data class Channel(
    val id: String,
    val name: String,
    val logo: String = "",
    val categoryId: String = "",
    val categoryName: String = "",
    val epgId: String = "",
    val number: Int = 0,
    val url: String = "",
    val urlTs: String = "",
    val archive: Boolean = false,
    val archiveDays: Int = 0,
    val adult: Boolean = false,
    /** When the provider added this channel, epoch millis; 0 when unknown. */
    val added: Long = 0L
)

data class Movie(
    val id: String,
    val name: String,
    val poster: String = "",
    val backdrop: String = "",
    val categoryId: String = "",
    val categoryName: String = "",
    val rating: Double? = null,
    val year: Int? = null,
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val genre: String = "",
    val releaseDate: String = "",
    val country: String = "",
    val duration: Int? = null,
    val url: String = "",
    val extension: String = "mp4",
    val trailer: String = "",
    val adult: Boolean = false,
    /** When the provider added this title, epoch millis; 0 when unknown. */
    val added: Long = 0L
)

data class Episode(
    val id: String,
    val name: String,
    val season: Int,
    val episode: Int,
    val plot: String = "",
    val still: String = "",
    val url: String = "",
    val duration: Int? = null
)

data class Season(
    val number: Int,
    val name: String,
    val overview: String = "",
    val cover: String = "",
    val episodes: List<Episode> = emptyList()
)

data class Series(
    val id: String,
    val name: String,
    val poster: String = "",
    val backdrop: String = "",
    val categoryId: String = "",
    val categoryName: String = "",
    val rating: Double? = null,
    val year: Int? = null,
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val genre: String = "",
    val seasons: List<Season> = emptyList(),
    val adult: Boolean = false,
    val trailer: String = "",
    /** When the provider added this title, epoch millis; 0 when unknown. */
    val added: Long = 0L
)

data class Library(
    val channels: List<Channel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    val liveCategories: List<Category> = emptyList(),
    val movieCategories: List<Category> = emptyList(),
    val seriesCategories: List<Category> = emptyList(),
    val epgUrl: String = "",
    val loadedAt: Long = System.currentTimeMillis()
) {
    // A library is tens of thousands of objects. The generated data-class
    // equals would compare every one of them - and StateFlow calls equals on
    // every emission, on the main thread, which froze a TV box for over ten
    // seconds. Two libraries are the same only if they are the same object.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

data class Playlist(
    val id: String,
    val name: String,
    val type: String,
    val host: String,
    val username: String = "",
    val password: String = "",
    val epgUrl: String = "",
    val enabled: Boolean = true
)

/**
 * What the licensing server currently says about this device.
 *
 * [active] is the only field that gates playback: it is true when the
 * server grants playlist access (an active trial or a live activation)
 * and at least one playlist is assigned. Everything else is presented to
 * the user on the activation and profile screens.
 */
data class DeviceState(
    val active: Boolean = false,
    val found: Boolean = false,
    val status: String = "unknown",
    val message: String = "",
    val mac: String = "",
    val deviceKey: String = "",
    val playlists: List<Playlist> = emptyList(),
    val expiresAt: String? = null,
    val trialActive: Boolean = false,
    val trialExpired: Boolean = false,
    val trialExpiresAt: String = "",
    val activatedAt: String = "",
    val activationExpiresAt: String = "",
    val playlistAccess: Boolean = false
)

/** One guide entry; [start] / [end] are epoch millis. */
data class EpgProgram(
    val title: String,
    val start: Long,
    val end: Long,
    val description: String = "",
    val nowPlaying: Boolean = false,
    val hasArchive: Boolean = false
)

data class ResumeEntry(
    val id: String,
    val title: String,
    val poster: String,
    val backdrop: String = "",
    val positionMs: Long,
    val durationMs: Long,
    val kind: String, // "movie", "episode", "channel"
    val seriesId: String = "",
    val seasonNumber: Int = 1,
    val episodeNumber: Int = 1,
    val url: String = "",
    val lastWatched: Long = System.currentTimeMillis()
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val remainingMinutes: Int
        get() = if (durationMs > positionMs) (((durationMs - positionMs) / 1000) / 60).toInt() else 0
}

data class HistoryEntry(
    val id: String,
    val kind: String = "movie",
    val name: String,
    val poster: String = "",
    val categoryName: String = "",
    val seriesId: String = "",
    val seriesName: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val url: String = "",
    val watchedAt: Long = System.currentTimeMillis()
)
