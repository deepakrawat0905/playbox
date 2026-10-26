package com.playboxpro.tv.api

import com.playboxpro.tv.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object XtreamApi {
    private fun host(a: Account): String {
        val h = a.host.trim().removeSuffix("/")
        return if (!h.startsWith("http://", ignoreCase = true) && !h.startsWith("https://", ignoreCase = true)) "http://$h" else h
    }

    private fun url(a: Account, action: String? = null, extra: Map<String, String> = emptyMap()): String {
        val q = linkedMapOf("username" to a.username, "password" to a.password)
        if (action != null) q["action"] = action
        q.putAll(extra)
        return host(a) + "/player_api.php?" + q.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
    }

    fun authenticate(a: Account): JSONObject {
        val raw = Http.get(url(a))
        val o = Http.parseObject(raw)
        val info = o.optJSONObject("user_info") ?: throw Exception("The server did not accept those credentials.")
        val auth = info.optString("auth", "1")
        val status = info.optString("status", "Active")
        if (auth == "0" || status.equals("Banned", true) || status.equals("Disabled", true)) {
            throw Exception("Account is $status or unauthorized")
        }
        return o
    }

    /**
     * Reads a list endpoint row by row. Nothing larger than a single record
     * is ever held in memory, which is what keeps a 27MB VOD listing from
     * dominating start-up.
     */
    private fun <T : Any> rows(
        a: Account,
        action: String,
        extra: Map<String, String> = emptyMap(),
        map: (JSONObject, Int) -> T?
    ): List<T> = Http.streamArray(url(a, action, extra), map)

    fun liveCategories(a: Account): List<Category> =
        rows(a, "get_live_categories") { o, _ -> toCategory(o, "live") }

    fun movieCategories(a: Account): List<Category> =
        rows(a, "get_vod_categories") { o, _ -> toCategory(o, "movie") }

    fun seriesCategories(a: Account): List<Category> =
        rows(a, "get_series_categories") { o, _ -> toCategory(o, "series") }

    private fun toCategory(o: JSONObject, kind: String): Category {
        val id = optStr(o, "category_id")
        val name = optStr(o, "category_name", "Uncategorised")
        return Category(id, name, kind, 0, isAdultCategory(name))
    }

    fun channels(a: Account, categoryId: String? = null, catMap: Map<String, String> = emptyMap()): List<Channel> {
        val extra = if (categoryId.isNullOrBlank()) emptyMap() else mapOf("category_id" to categoryId)
        return rows(a, "get_live_streams", extra) { o, i ->
            run {
                val streamId = optStr(o, "stream_id")
                if (streamId.isBlank()) return@run null
                val catId = optStr(o, "category_id")
                val catName = catMap[catId] ?: optStr(o, "category_name")
                val name = optStr(o, "name", "Unnamed channel")
                val icon = optStr(o, "stream_icon")
                val epgId = optStr(o, "epg_channel_id")
                val num = o.optInt("num", i + 1)
                val archive = o.optInt("tv_archive") == 1
                val archiveDays = o.optInt("tv_archive_duration")
                val added = optStr(o, "added").toLongOrNull()?.times(1000) ?: 0L
                val isAdult = isAdultCategory(name) || isAdultCategory(catName)

                Channel(
                    id = "live:$streamId",
                    name = name,
                    logo = icon,
                    categoryId = catId,
                    categoryName = catName,
                    epgId = epgId,
                    number = num,
                    url = liveUrl(a, streamId, "m3u8"),
                    urlTs = liveUrl(a, streamId, "ts"),
                    archive = archive,
                    archiveDays = archiveDays,
                    adult = isAdult,
                    added = added
                )
            }
        }
    }

    fun movies(a: Account, categoryId: String? = null, catMap: Map<String, String> = emptyMap()): List<Movie> {
        val extra = if (categoryId.isNullOrBlank()) emptyMap() else mapOf("category_id" to categoryId)
        return rows(a, "get_vod_streams", extra) { o, _ ->
            run {
                val streamId = optStr(o, "stream_id")
                if (streamId.isBlank()) return@run null
                val catId = optStr(o, "category_id")
                val catName = catMap[catId] ?: optStr(o, "category_name")
                val name = optStr(o, "name", "Untitled")
                val poster = optStr(o, "stream_icon").ifBlank { optStr(o, "cover") }
                val backdrop = parseBackdrop(o)
                val ext = optStr(o, "container_extension", "mp4").ifBlank { "mp4" }
                val rating = parseRating(o.opt("rating")) ?: parseRating(o.opt("rating_5based"))
                val year = parseYear(optStr(o, "year").ifBlank { optStr(o, "releasedate") })
                val duration = o.optInt("duration_secs").takeIf { it > 0 } ?: parseDurationSecs(optStr(o, "duration"))
                // Synopsis, cast and director are read per title by the detail
                // page. Keeping them out of the listing cuts the saved catalogue
                // by more than half, which is most of its load time on a box.
                val plot = ""
                val cast = ""
                val director = ""
                val genre = optStr(o, "genre").ifBlank { catName }
                val isAdult = isAdultCategory(name) || isAdultCategory(catName)
                val added = optStr(o, "added").toLongOrNull()?.times(1000) ?: 0L

                Movie(
                    id = "movie:$streamId",
                    name = name,
                    poster = poster,
                    backdrop = backdrop,
                    categoryId = catId,
                    categoryName = catName,
                    rating = rating,
                    year = year,
                    plot = plot,
                    cast = cast,
                    director = director,
                    genre = genre,
                    duration = duration,
                    url = movieUrl(a, streamId, ext),
                    extension = ext,
                    adult = isAdult,
                    added = added
                )
            }
        }
    }

    fun series(a: Account, categoryId: String? = null, catMap: Map<String, String> = emptyMap()): List<Series> {
        val extra = if (categoryId.isNullOrBlank()) emptyMap() else mapOf("category_id" to categoryId)
        return rows(a, "get_series", extra) { o, _ ->
            run {
                val seriesId = optStr(o, "series_id")
                if (seriesId.isBlank()) return@run null
                val catId = optStr(o, "category_id")
                val catName = catMap[catId] ?: optStr(o, "category_name")
                val name = optStr(o, "name", "Untitled")
                val poster = optStr(o, "cover").ifBlank { optStr(o, "stream_icon") }
                val backdrop = parseBackdrop(o)
                val rating = parseRating(o.opt("rating")) ?: parseRating(o.opt("rating_5based"))
                val year = parseYear(optStr(o, "year").ifBlank { optStr(o, "releaseDate") })
                // Synopsis, cast and director are read per title by the detail
                // page. Keeping them out of the listing cuts the saved catalogue
                // by more than half, which is most of its load time on a box.
                val plot = ""
                val cast = ""
                val director = ""
                val genre = optStr(o, "genre").ifBlank { catName }
                val isAdult = isAdultCategory(name) || isAdultCategory(catName)
                val added = (optStr(o, "last_modified").ifBlank { optStr(o, "added") })
                    .toLongOrNull()?.times(1000) ?: 0L

                Series(
                    id = "series:$seriesId",
                    name = name,
                    poster = poster,
                    backdrop = backdrop,
                    categoryId = catId,
                    categoryName = catName,
                    rating = rating,
                    year = year,
                    plot = plot,
                    cast = cast,
                    director = director,
                    genre = genre,
                    adult = isAdult,
                    added = added
                )
            }
        }
    }

    fun movieInfo(a: Account, id: String): Movie {
        val stream = id.removePrefix("movie:")
        val raw = Http.get(url(a, "get_vod_info", mapOf("vod_id" to stream)))
        val o = Http.parseObject(raw)
        val info = o.optJSONObject("info") ?: JSONObject()
        val md = o.optJSONObject("movie_data") ?: JSONObject()

        val name = optStr(md, "name").ifBlank { optStr(info, "name", "Untitled") }
        val poster = optStr(info, "movie_image").ifBlank { optStr(info, "cover_big").ifBlank { optStr(info, "cover") } }
        val backdrop = parseBackdrop(info)
        val ext = optStr(md, "container_extension", "mp4").ifBlank { "mp4" }
        val rating = parseRating(info.opt("rating")) ?: parseRating(info.opt("rating_5based"))
        val year = parseYear(optStr(info, "releasedate").ifBlank { optStr(info, "release_date") })
        val plot = optStr(info, "plot").ifBlank { optStr(info, "description") }
        val cast = optStr(info, "cast").ifBlank { optStr(info, "actors") }
        val director = optStr(info, "director")
        val genre = optStr(info, "genre")
        val country = optStr(info, "country")
        val duration = info.optInt("duration_secs").takeIf { it > 0 } ?: md.optInt("duration_secs").takeIf { it > 0 } ?: parseDurationSecs(optStr(info, "duration"))
        val trailer = optStr(info, "youtube_trailer")

        return Movie(
            id = "movie:$stream",
            name = name,
            poster = poster,
            backdrop = backdrop,
            rating = rating,
            year = year,
            plot = plot,
            cast = cast,
            director = director,
            genre = genre,
            country = country,
            duration = duration,
            url = movieUrl(a, stream, ext),
            extension = ext,
            trailer = trailer
        )
    }

    fun seriesInfo(a: Account, id: String): Series {
        val sid = id.removePrefix("series:")
        val raw = Http.get(url(a, "get_series_info", mapOf("series_id" to sid)))
        val o = Http.parseObject(raw)
        val info = o.optJSONObject("info") ?: JSONObject()

        val name = optStr(info, "name", "Untitled")
        val poster = optStr(info, "cover").ifBlank { optStr(info, "cover_big").ifBlank { optStr(info, "movie_image") } }
        val backdrop = parseBackdrop(info)
        val rating = parseRating(info.opt("rating")) ?: parseRating(info.opt("rating_5based"))
        val year = parseYear(optStr(info, "releaseDate").ifBlank { optStr(info, "releasedate") })
        val plot = optStr(info, "plot").ifBlank { optStr(info, "description") }
        val cast = optStr(info, "cast").ifBlank { optStr(info, "actors") }
        val director = optStr(info, "director")
        val genre = optStr(info, "genre")

        val seasons = buildList {
            val epsObj = o.optJSONObject("episodes")
            if (epsObj != null) {
                val keys = epsObj.keys()
                while (keys.hasNext()) {
                    val sKey = keys.next()
                    val sNum = sKey.toIntOrNull() ?: continue
                    val epArray = epsObj.optJSONArray(sKey) ?: JSONArray()
                    val episodeList = parseEpisodesArray(a, sid, sNum, epArray)
                    add(Season(sNum, "Season $sNum", episodes = episodeList))
                }
            } else {
                // If episodes is a JSONArray or seasons is separate
                val seasonsArr = o.optJSONArray("seasons")
                if (seasonsArr != null) {
                    for (i in 0 until seasonsArr.length()) {
                        val sObj = seasonsArr.optJSONObject(i) ?: continue
                        val sNum = sObj.optInt("season_number", i + 1)
                        val sName = optStr(sObj, "name", "Season $sNum")
                        add(Season(sNum, sName, overview = optStr(sObj, "overview"), cover = optStr(sObj, "cover")))
                    }
                }
            }
        }.sortedBy { it.number }

        return Series(
            id = "series:$sid",
            name = name,
            poster = poster,
            backdrop = backdrop,
            rating = rating,
            year = year,
            plot = plot,
            cast = cast,
            director = director,
            genre = genre,
            seasons = seasons,
            trailer = optStr(info, "youtube_trailer")
        )
    }

    private fun parseEpisodesArray(a: Account, seriesId: String, seasonNum: Int, arr: JSONArray): List<Episode> = buildList {
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val epNum = e.optInt("episode_num", i + 1)
            val streamId = optStr(e, "id").ifBlank { e.opt("id")?.toString().orEmpty() }.ifBlank { epNum.toString() }
            val title = optStr(e, "title", "Episode $epNum")
            val epInfo = e.optJSONObject("info")
            val plot = epInfo?.let { optStr(it, "plot").ifBlank { optStr(it, "description") } } ?: optStr(e, "plot")
            val still = epInfo?.let { optStr(it, "movie_image").ifBlank { optStr(it, "still_path") } } ?: optStr(e, "movie_image")
            val duration = epInfo?.optInt("duration_secs")?.takeIf { it > 0 } ?: e.optInt("duration_secs").takeIf { it > 0 } ?: parseDurationSecs(epInfo?.optString("duration") ?: "")
            val ext = optStr(e, "container_extension", "mp4").ifBlank { "mp4" }

            add(
                Episode(
                    id = "episode:$seriesId:$seasonNum:$epNum",
                    name = title,
                    season = seasonNum,
                    episode = epNum,
                    plot = plot,
                    still = still,
                    url = seriesUrl(a, streamId, ext),
                    duration = duration
                )
            )
        }
    }.sortedBy { it.episode }

    /**
     * The guide for one live stream, the way STB+ reads it: the full
     * `get_simple_data_table` listing, or `get_short_epg` when [limit] is
     * given (what is on now and next). Titles and descriptions come base64
     * encoded; times are the server's epoch timestamps.
     */
    fun epg(a: Account, streamId: String, limit: Int? = null): List<EpgProgram> {
        val extra = linkedMapOf("stream_id" to streamId)
        val action = if (limit != null) { extra["limit"] = limit.toString(); "get_short_epg" } else "get_simple_data_table"
        val raw = Http.get(url(a, action, extra))
        val root = Http.parseObject(raw)
        val rows = root.optJSONArray("epg_listings") ?: return emptyList()
        val out = ArrayList<EpgProgram>(rows.length())
        for (i in 0 until rows.length()) {
            val o = rows.optJSONObject(i) ?: continue
            val start = epgMillis(o, "start_timestamp", "start")
            val end = epgMillis(o, "stop_timestamp", "end")
            if (start <= 0L || end <= start) continue
            out.add(
                EpgProgram(
                    title = b64(optStr(o, "title")).ifBlank { "No Information" },
                    start = start,
                    end = end,
                    description = b64(optStr(o, "description")),
                    nowPlaying = o.optInt("now_playing") == 1,
                    hasArchive = o.optInt("has_archive") == 1
                )
            )
        }
        return out.sortedBy { it.start }
    }

    /**
     * Xtream panels write the guide's wall-clock text and derive the
     * timestamps from it as if it were UTC, so the text is what STB+ shows
     * verbatim. Reading it in the device's own zone keeps the same clock
     * face on screen; the timestamps are only a fallback.
     */
    private fun epgMillis(o: JSONObject, tsKey: String, textKey: String): Long {
        val text = optStr(o, textKey)
        if (text.isNotBlank()) {
            runCatching {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).parse(text)?.time
            }.getOrNull()?.let { return it }
        }
        return optStr(o, tsKey).toLongOrNull()?.let { it * 1000 } ?: 0L
    }

    private fun b64(v: String): String {
        if (v.isBlank()) return ""
        return runCatching {
            String(android.util.Base64.decode(v, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
        }.getOrDefault(v)
    }

    fun xmltvUrl(a: Account) = "${host(a)}/xmltv.php?username=${enc(a.username)}&password=${enc(a.password)}"
    fun liveUrl(a: Account, id: String, ext: String = "m3u8") = "${host(a)}/live/${enc(a.username)}/${enc(a.password)}/$id.$ext"
    fun movieUrl(a: Account, id: String, ext: String = "mp4") = "${host(a)}/movie/${enc(a.username)}/${enc(a.password)}/$id.$ext"
    fun seriesUrl(a: Account, id: String, ext: String = "mp4") = "${host(a)}/series/${enc(a.username)}/${enc(a.password)}/$id.$ext"

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun optStr(o: JSONObject, key: String, default: String = ""): String {
        val v = o.opt(key) ?: return default
        val s = v.toString().trim()
        return if (s == "null" || s.isEmpty()) default else s
    }

    private fun parseBackdrop(o: JSONObject): String {
        return when (val bp = o.opt("backdrop_path")) {
            is JSONArray -> bp.optString(0)
            is String -> if (bp.isNotBlank() && bp != "null") bp else ""
            else -> optStr(o, "backdrop")
        }
    }

    private fun parseRating(v: Any?): Double? {
        if (v == null) return null
        return when (v) {
            is Number -> v.toDouble().takeIf { it > 0 }
            is String -> v.trim().toDoubleOrNull()?.takeIf { it > 0 }
            else -> null
        }
    }

    private fun parseYear(v: String): Int? {
        return Regex("(19|20)\\d{2}").find(v)?.value?.toIntOrNull()
    }

    private fun parseDurationSecs(v: String): Int? {
        if (v.isBlank()) return null
        val parts = v.trim().split(':')
        if (parts.size == 3) {
            val h = parts[0].toIntOrNull() ?: 0
            val m = parts[1].toIntOrNull() ?: 0
            val s = parts[2].toIntOrNull() ?: 0
            return (h * 3600 + m * 60 + s).takeIf { it > 0 }
        }
        if (parts.size == 2) {
            val m = parts[0].toIntOrNull() ?: 0
            val s = parts[1].toIntOrNull() ?: 0
            return (m * 60 + s).takeIf { it > 0 }
        }
        return null
    }

    private fun isAdultCategory(name: String): Boolean {
        val s = name.lowercase()
        return s.contains("xxx") || s.contains("adult") || s.contains("18+") || s.contains("porn") || s.contains("erotic")
    }
}
