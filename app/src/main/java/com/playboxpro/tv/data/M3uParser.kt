package com.playboxpro.tv.data

import com.playboxpro.tv.api.Http
import com.playboxpro.tv.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.StringReader

object M3uParser {
    data class Parsed(val items: List<Item>, val epgUrl: String = "")
    data class Item(
        val name: String,
        val url: String,
        val group: String,
        val logo: String,
        val tvgId: String,
        val chno: String,
        val duration: Int,
        val kind: String
    )

    private val VOD_EXT = Regex("\\.(mp4|mkv|avi|mov|m4v|flv|wmv|mpg|mpeg|webm)(\\?|$)", RegexOption.IGNORE_CASE)
    private val SERIES_HINT = Regex("\\bS\\d{1,2}\\s?E\\d{1,3}\\b|\\bseason\\s?\\d+|\\b\\d{1,2}x\\d{1,3}\\b", RegexOption.IGNORE_CASE)
    private val SERIES_GROUP = Regex("\\b(series|serien|séries|dizi|shows|tv shows)\\b", RegexOption.IGNORE_CASE)
    private val VOD_GROUP = Regex("\\b(vod|movie|movies|film|filme|películas|peliculas|cinema)\\b", RegexOption.IGNORE_CASE)
    private val ATTR_REGEX = Regex("([a-zA-Z0-9_-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s,]+))")

    fun load(url: String): Library {
        val targetUrl = url.trim().let {
            if (!it.startsWith("http://", ignoreCase = true) && !it.startsWith("https://", ignoreCase = true)) "http://$it" else it
        }
        val text = Http.get(targetUrl)
        val t = text.trim()
        if (t.startsWith("{")) return parseJson(t)
        if (t.startsWith("[")) return parseJsonArray(t)
        if (!t.contains("#EXTINF", true) && !t.contains("#EXTM3U", true)) {
            throw IllegalArgumentException("The URL did not return a valid M3U playlist")
        }

        val p = parse(t)
        val live = ArrayList<Channel>(p.items.size)
        val movies = ArrayList<Movie>()
        val seriesRaw = ArrayList<Item>()
        var n = 0

        p.items.forEach { e ->
            val isAdult = isAdultText(e.name) || isAdultText(e.group)
            when (e.kind) {
                "movie" -> {
                    val year = parseYear(e.name)
                    val cleanName = cleanTitle(e.name)
                    movies += Movie(
                        id = "movie:${hash(e.url)}",
                        name = cleanName,
                        poster = e.logo,
                        backdrop = e.logo,
                        categoryId = hash(e.group),
                        categoryName = e.group,
                        year = year,
                        duration = e.duration.takeIf { it > 0 },
                        url = e.url,
                        adult = isAdult
                    )
                }
                "series" -> seriesRaw += e
                else -> {
                    n++
                    val num = e.chno.toIntOrNull() ?: n
                    live += Channel(
                        id = "live:${hash(e.url)}",
                        name = e.name,
                        logo = e.logo,
                        categoryId = hash(e.group),
                        categoryName = e.group,
                        epgId = e.tvgId,
                        number = num,
                        url = e.url,
                        urlTs = e.url,
                        adult = isAdult
                    )
                }
            }
        }

        val series = buildSeries(seriesRaw)

        return Library(
            channels = live,
            movies = movies,
            series = series,
            liveCategories = categories(live, "live"),
            movieCategories = categories(movies, "movie"),
            seriesCategories = categories(series, "series"),
            epgUrl = p.epgUrl
        )
    }

    private fun parse(text: String): Parsed {
        var epg = ""
        val items = mutableListOf<Item>()
        var pending: Meta? = null
        var pendingGroup = ""

        val reader = BufferedReader(StringReader(text))
        var line: String? = reader.readLine()

        while (line != null) {
            val l = line.trim()
            if (l.isEmpty()) {
                line = reader.readLine()
                continue
            }

            if (l.startsWith("#EXTM3U", true)) {
                val attrs = parseAttributes(l)
                epg = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: ""
                line = reader.readLine()
                continue
            }

            if (l.startsWith("#EXTINF:", true)) {
                val body = l.substring(l.indexOf(':') + 1)
                val (attrPart, title) = splitTitle(body)
                val attrs = parseAttributes(attrPart)
                val dur = Regex("^\\s*(-?\\d+)").find(attrPart)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1

                val grp = attrs["group-title"] ?: attrs["group"] ?: attrs["group_title"] ?: ""
                val logo = attrs["tvg-logo"] ?: attrs["tvg_logo"] ?: attrs["logo"] ?: attrs["tvg-icon"] ?: attrs["tvg_icon"] ?: attrs["icon"] ?: attrs["stream_icon"] ?: ""
                val tvgId = attrs["tvg-id"] ?: attrs["tvg_id"] ?: ""
                val chno = attrs["tvg-chno"] ?: attrs["tvg_chno"] ?: attrs["chno"] ?: attrs["channel-number"] ?: ""
                val name = title.ifBlank { attrs["tvg-name"] ?: "Untitled" }

                pending = Meta(name, dur, grp, logo, tvgId, chno)
                line = reader.readLine()
                continue
            }

            if (l.startsWith("#EXTGRP:", true)) {
                pendingGroup = l.substring(l.indexOf(':') + 1).trim()
                line = reader.readLine()
                continue
            }

            if (l.startsWith("#")) {
                line = reader.readLine()
                continue
            }

            // Line is stream URL
            val u = l
            val p = pending ?: Meta("Channel ${items.size + 1}", -1, "", "", "", "")
            val group = p.group.ifBlank { pendingGroup.ifBlank { "Uncategorised" } }
            val kind = detectKind(u, p.name, group)

            items += Item(
                name = p.name,
                url = u,
                group = group,
                logo = p.logo,
                tvgId = p.tvgId,
                chno = p.chno,
                duration = p.duration,
                kind = kind
            )

            pending = null
            pendingGroup = ""
            line = reader.readLine()
        }
        return Parsed(items, epg)
    }

    private data class Meta(val name: String, val duration: Int, val group: String, val logo: String, val tvgId: String, val chno: String)

    private fun parseAttributes(line: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (match in ATTR_REGEX.findAll(line)) {
            val key = match.groupValues[1].lowercase()
            val v1 = match.groupValues.getOrNull(2)
            val v2 = match.groupValues.getOrNull(3)
            val v3 = match.groupValues.getOrNull(4)
            val value = (if (!v1.isNullOrEmpty()) v1 else if (!v2.isNullOrEmpty()) v2 else v3.orEmpty()).trim('"', '\'', ' ')
            map[key] = value
        }
        return map
    }

    private fun splitTitle(line: String): Pair<String, String> {
        var inQuote: Char? = null
        var lastComma = -1
        for (i in line.indices) {
            val ch = line[i]
            if (inQuote != null) {
                if (ch == inQuote) inQuote = null
            } else if (ch == '"' || ch == '\'') {
                inQuote = ch
            } else if (ch == ',') {
                lastComma = i
            }
        }
        return if (lastComma == -1) {
            line to ""
        } else {
            line.substring(0, lastComma) to line.substring(lastComma + 1).trim()
        }
    }

    /**
     * Live, movie or series. The link decides first: a stream that never
     * ends (.m3u8 / .ts / a /live/ path / rtmp / udp) is a TV channel even
     * in a group called "Movies" - public lists file movie *channels* there.
     * Only a real video file, or a panel's /movie/ or /series/ path, is on
     * demand; the group name and an SxxEyy title then tell movie and
     * series apart.
     */
    private fun detectKind(url: String, name: String, group: String): String {
        val path = url.substringBefore('?').lowercase()
        if (path.contains("/series/")) return "series"
        if (path.contains("/movie/")) return "movie"

        val liveLink = LIVE_LINK.containsMatchIn(path)
        val videoFile = VOD_EXT.containsMatchIn(url)
        if (liveLink && !videoFile) return "live"

        val haystack = "$group "
        val seriesGroup = SERIES_GROUP.containsMatchIn(haystack) && !group.contains("news", ignoreCase = true)
        if (videoFile && (seriesGroup || SERIES_HINT.containsMatchIn(name))) return "series"
        if (videoFile) return "movie"

        // No telling from the link (an extensionless redirect, say): fall
        // back to the group the playlist filed it under.
        if (seriesGroup) return "series"
        if (VOD_GROUP.containsMatchIn(haystack)) return "movie"
        return "live"
    }

    private val LIVE_LINK = Regex("""(\.m3u8?$|\.ts$|/live/|^rtmp|^rtsp|^udp|^rtp)""")

    private fun buildSeries(items: List<Item>): List<Series> {
        val map = linkedMapOf<String, MutableMap<Int, MutableList<Episode>>>()
        val meta = mutableMapOf<String, Item>()

        items.forEach { item ->
            if (item.group.contains("news", ignoreCase = true) || item.name.contains("news", ignoreCase = true)) {
                return@forEach
            }

            val parsedEp = parseSeriesTitle(item.name)
            val showName = parsedEp.showName.ifBlank { item.name }
            val key = showName.lowercase() + "|" + item.group.lowercase()

            map.getOrPut(key) { linkedMapOf() }
                .getOrPut(parsedEp.season) { mutableListOf() }
                .add(
                    Episode(
                        id = "episode:${hash(item.url)}",
                        name = parsedEp.episodeTitle.ifBlank { "Episode ${parsedEp.episode}" },
                        season = parsedEp.season,
                        episode = parsedEp.episode,
                        url = item.url,
                        still = item.logo,
                        duration = item.duration.takeIf { it > 0 }
                    )
                )
            meta.putIfAbsent(key, item)
        }

        return map.map { (key, seasons) ->
            val m = meta[key]!!
            val showName = key.substringBefore('|').split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            val isAdult = isAdultText(showName) || isAdultText(m.group)
            Series(
                id = "series:${hash(key)}",
                name = showName,
                poster = m.logo,
                backdrop = m.logo,
                categoryId = hash(m.group),
                categoryName = m.group,
                seasons = seasons.toSortedMap().map { (n, eps) ->
                    Season(n, "Season $n", episodes = eps.sortedBy { it.episode })
                },
                adult = isAdult
            )
        }
    }

    private data class ParsedEp(val showName: String, val season: Int, val episode: Int, val episodeTitle: String)

    private fun parseSeriesTitle(title: String): ParsedEp {
        var m = Regex("^(.*?)(?:\\s+|[-_.])S(\\d{1,2})\\s*E(\\d{1,3})(?:\\s*[-_. ]?\\s*(.*))?$", RegexOption.IGNORE_CASE).find(title)
        if (m != null) {
            val show = m.groupValues[1].trim()
            val s = m.groupValues[2].toIntOrNull() ?: 1
            val e = m.groupValues[3].toIntOrNull() ?: 1
            val epTitle = m.groupValues.getOrNull(4)?.trim().orEmpty()
            return ParsedEp(show, s, e, epTitle)
        }

        m = Regex("^(.*?)(?:\\s+|[-_.])(\\d{1,2})x(\\d{1,3})(?:\\s*[-_. ]?\\s*(.*))?$", RegexOption.IGNORE_CASE).find(title)
        if (m != null) {
            val show = m.groupValues[1].trim()
            val s = m.groupValues[2].toIntOrNull() ?: 1
            val e = m.groupValues[3].toIntOrNull() ?: 1
            val epTitle = m.groupValues.getOrNull(4)?.trim().orEmpty()
            return ParsedEp(show, s, e, epTitle)
        }

        m = Regex("^(.*?)(?:\\s+|[-_.])Season\\s*(\\d{1,2})\\s*Episode\\s*(\\d{1,3})(?:\\s*[-_. ]?\\s*(.*))?$", RegexOption.IGNORE_CASE).find(title)
        if (m != null) {
            val show = m.groupValues[1].trim()
            val s = m.groupValues[2].toIntOrNull() ?: 1
            val e = m.groupValues[3].toIntOrNull() ?: 1
            val epTitle = m.groupValues.getOrNull(4)?.trim().orEmpty()
            return ParsedEp(show, s, e, epTitle)
        }

        return ParsedEp(title, 1, 1, title)
    }

    private fun categories(items: List<Any>, kind: String): List<Category> {
        val map = linkedMapOf<String, Int>()
        items.forEach {
            val name = when (it) {
                is Channel -> it.categoryName
                is Movie -> it.categoryName
                is Series -> it.categoryName
                else -> "Uncategorised"
            }.ifBlank { "Uncategorised" }
            map[name] = map.getOrDefault(name, 0) + 1
        }
        return map.map { Category(hash(it.key), it.key, kind, it.value, isAdultText(it.key)) }
    }

    private fun parseJson(text: String): Library {
        val o = JSONObject(text)
        val arr = o.optJSONArray("items") ?: o.optJSONArray("data") ?: JSONArray()
        return fromJson(arr, o.optString("epgUrl", o.optString("url-tvg")))
    }

    private fun parseJsonArray(text: String) = fromJson(JSONArray(text), "")

    private fun fromJson(a: JSONArray, epg: String): Library {
        val live = mutableListOf<Channel>()
        val movies = mutableListOf<Movie>()
        val series = mutableListOf<Series>()
        var n = 0
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val name = o.optString("name", o.optString("title", "Untitled"))
            val u = o.optString("url", o.optString("stream_url"))
            val group = o.optString("group", o.optString("group-title", "Uncategorised"))
            val logo = o.optString("logo", o.optString("tvg-logo"))
            val isAdult = isAdultText(name) || isAdultText(group)
            val kind = o.optString("kind", detectKind(u, name, group))
            when (kind) {
                "movie" -> movies += Movie("movie:${hash(u)}", name, logo, logo, categoryId = hash(group), categoryName = group, url = u, adult = isAdult)
                "series" -> series += Series("series:${hash(name)}", name, logo, logo, categoryId = hash(group), categoryName = group, adult = isAdult)
                else -> {
                    n++
                    live += Channel("live:${hash(u)}", name, logo, hash(group), group, o.optString("tvg-id"), o.optInt("number", n), u, u, adult = isAdult)
                }
            }
        }
        return Library(live, movies, series, categories(live, "live"), categories(movies, "movie"), categories(series, "series"), epg)
    }

    private fun cleanTitle(title: String): String {
        return title.replace(Regex("\\b(19|20)\\d{2}\\b"), "").replace(Regex("[\\[\\]().]"), " ").trim()
    }

    private fun parseYear(v: String): Int? {
        return Regex("(19|20)\\d{2}").find(v)?.value?.toIntOrNull()
    }

    private fun isAdultText(text: String): Boolean {
        val s = text.lowercase()
        return s.contains("xxx") || s.contains("adult") || s.contains("18+") || s.contains("porn") || s.contains("erotic")
    }

    private fun hash(s: String) = Integer.toHexString(s.hashCode()).replace('-', '0')
}
