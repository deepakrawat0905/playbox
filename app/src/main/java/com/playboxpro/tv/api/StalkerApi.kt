package com.playboxpro.tv.api

import com.playboxpro.tv.model.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone

/**
 * Stalker / Ministra ("MAG") portals: a playlist identified by a portal
 * address and a MAC address instead of a username and password.
 *
 * The app talks to the portal the way a MAG box does: a handshake hands out
 * a token, a profile request registers the "box", and every later call
 * carries that token plus the MAC in a cookie. Channels, films and series
 * come from the portal's own listings. Their play commands are not URLs:
 * a fresh, short-lived link is asked for each time something starts
 * playing (see [resolve]), which is why stored items use the private
 * `stalker://` scheme.
 */
object StalkerApi {

    // The box STB+ presents itself as: a MAG254 on Ethernet.
    private const val UA =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG254"

    /** The serial STB+ sends; portals accept it in place of a real box serial. */
    private const val SN = "00000BYPASSMOL"

    /** Same connection pool and DNS fallback as the rest of the app, with MAG headers. */
    private val client: OkHttpClient = Http.client.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", UA)
                    .header("X-User-Agent", "Model: MAG254; Link: Ethernet")
                    .header("Accept", "*/*")
                    .build()
            )
        }
        .build()

    class Session(
        val portal: String,
        val loadUrl: String,
        val mac: String
    ) {
        @Volatile var token: String = ""

        /** Cookies the portal set itself (its PHP session), sent back on every call. */
        val cookies = java.util.concurrent.ConcurrentHashMap<String, String>()
    }

    private val sessions = HashMap<String, Session>()

    // ------------------------------------------------------------------
    // Addresses
    // ------------------------------------------------------------------

    /** Portal links come in many shapes; these are the API endpoints worth trying, best first. */
    fun candidates(portal: String): List<String> {
        var p = portal.trim()
        if (!p.startsWith("http://", true) && !p.startsWith("https://", true)) p = "http://$p"
        val uri = runCatching { java.net.URI(p) }.getOrNull() ?: return emptyList()
        val origin = "${uri.scheme}://${uri.host}" + if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.path.orEmpty().trimEnd('/')

        val out = LinkedHashSet<String>()
        when {
            path.endsWith(".php", true) -> out += origin + path
            path.endsWith("/c", true) -> {
                val base = path.dropLast(2)
                out += "$origin$base/portal.php"
                out += "$origin$base/server/load.php"
            }
            path.isNotEmpty() -> {
                out += "$origin$path/portal.php"
                out += "$origin$path/server/load.php"
            }
        }
        out += "$origin/portal.php"
        out += "$origin/stalker_portal/server/load.php"
        out += "$origin/server/load.php"
        out += "$origin/c/portal.php"
        return out.toList()
    }

    private fun origin(s: Session): String {
        val uri = java.net.URI(s.loadUrl)
        return "${uri.scheme}://${uri.host}" + if (uri.port > 0) ":${uri.port}" else ""
    }

    // ------------------------------------------------------------------
    // Device identity (what a MAG box derives from its MAC)
    // ------------------------------------------------------------------

    private fun hex(algo: String, s: String): String =
        MessageDigest.getInstance(algo).digest(s.toByteArray()).joinToString("") { "%02X".format(it) }

    private fun serial(mac: String) = hex("MD5", mac).take(13)
    private fun deviceId(mac: String) = hex("SHA-256", mac)
    private fun signature(mac: String) = hex("SHA-256", serial(mac) + mac)

    /** Portals expect upper-case, colon-separated MACs. */
    fun normalizeMac(mac: String): String {
        val hexOnly = mac.uppercase(Locale.US).filter { it in "0123456789ABCDEF" }
        return if (hexOnly.length == 12) hexOnly.chunked(2).joinToString(":") else mac.trim().uppercase(Locale.US)
    }

    // ------------------------------------------------------------------
    // Requests
    // ------------------------------------------------------------------

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun rawCall(s: Session, params: Map<String, String>, withToken: Boolean = true): String {
        val query = (params + ("JsHttpRequest" to "1-xml")).entries
            .joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val tz = TimeZone.getDefault().id
        val builder = Request.Builder()
            .url("${s.loadUrl}?$query")
            .header("Cookie", cookieHeader(s, tz))
            .header("Referer", s.portal.trimEnd('/') + "/")
        if (withToken && s.token.isNotBlank()) builder.header("Authorization", "Bearer ${s.token}")
        client.newCall(builder.get().build()).execute().use { r ->
            // Keep the session cookie the portal hands out.
            for (h in r.headers("Set-Cookie")) {
                val pair = h.substringBefore(";")
                val i = pair.indexOf('=')
                if (i > 0) s.cookies[pair.substring(0, i).trim()] = pair.substring(i + 1).trim()
            }
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful && body.isBlank()) throw java.io.IOException("Portal answered HTTP ${r.code}")
            return body
        }
    }

    private fun cookieHeader(s: Session, tz: String): String {
        val base = linkedMapOf(
            "PHPSESSID" to "null", "sn" to SN, "mac" to enc(s.mac),
            "stb_lang" to "en", "timezone" to enc(tz)
        )
        base.putAll(s.cookies)
        return base.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun parseJs(text: String): Any? {
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("<")) return null
        val o = runCatching { JSONObject(t) }.getOrNull() ?: return null
        return o.opt("js")
    }

    /** One API call; an expired token is renewed once and the call repeated. */
    private fun call(s: Session, params: Map<String, String>): Any? {
        val first = runCatching { rawCall(s, params) }.getOrNull()
        if (first == null || first.contains("Authorization failed", true) || s.token.isBlank()) {
            authorize(s)
            return parseJs(rawCall(s, params))
        }
        return parseJs(first)
    }

    private fun handshake(s: Session): Boolean {
        val js = parseJs(rawCall(s, mapOf("type" to "stb", "action" to "handshake", "token" to "", "prehash" to "0"), withToken = false))
            as? JSONObject ?: return false
        val token = js.optString("token")
        if (token.isBlank()) return false
        s.token = token
        return true
    }

    private fun profile(s: Session): JSONObject? {
        val mac = s.mac
        val params = linkedMapOf(
            "type" to "stb", "action" to "get_profile", "hd" to "1",
            "ver" to "ImageDescription: 0.2.18-r23-250; ImageDate: Thu Sep 13 11:31:16 EEST 2018; PORTAL version: 5.6.1; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c",
            "num_banks" to "2", "sn" to SN, "stb_type" to "MAG254", "client_type" to "STB",
            "image_version" to "218", "video_out" to "hdmi", "device_id" to deviceId(mac),
            "device_id2" to deviceId(mac), "signature" to signature(mac), "auth_second_step" to "1",
            "hw_version" to "1.5", "not_valid_token" to "0", "api_signature" to "262",
            "prehash" to "0", "timestamp" to (System.currentTimeMillis() / 1000).toString()
        )
        return parseJs(rawCall(s, params)) as? JSONObject
    }

    @Synchronized
    private fun authorize(s: Session) {
        if (!handshake(s)) throw java.io.IOException("The portal did not accept this device.")
        val p = profile(s)
        // A portal that does not know the MAC says so in the profile.
        val blocked = p?.optString("status")?.let { it == "1" } == true &&
            p.optString("msg").isNotBlank()
        if (blocked) throw java.io.IOException(p!!.optString("msg"))
    }

    /** A signed-in session for this portal and MAC, reused while it lasts. */
    @Synchronized
    fun session(portal: String, macRaw: String): Session {
        val mac = normalizeMac(macRaw)
        val key = portal.trim().lowercase() + "|" + mac
        sessions[key]?.let { if (it.token.isNotBlank()) return it }

        var lastError: Exception? = null
        for (loadUrl in candidates(portal)) {
            val s = Session(portal, loadUrl, mac)
            val shook = try { handshake(s) } catch (e: Exception) { lastError = e; false }
            if (!shook) continue
            // This is the portal: whatever it says next is the answer.
            run {
                val p = profile(s)
                if (p != null && p.optString("status") == "1" && p.optString("msg").isNotBlank()) {
                    throw java.io.IOException(p.optString("msg"))
                }
                // An unknown MAC still gets a profile, just an empty one with
                // no channels behind it; say so instead of showing an empty list.
                val noAccount = p == null || p.isNull("id") || p.optString("id").isBlank()
                if (noAccount && liveCategories(s).isEmpty()) {
                    throw java.io.IOException("This MAC address ($mac) is not registered on the portal.")
                }
                sessions[key] = s
                return s
            }
        }
        throw lastError ?: java.io.IOException("Could not reach the portal at $portal")
    }

    // ------------------------------------------------------------------
    // Account
    // ------------------------------------------------------------------

    /** Expiry and status as the portal reports them; empty when it does not say. */
    fun accountInfo(s: Session): Map<String, String> {
        val js = call(s, mapOf("type" to "account_info", "action" to "get_main_info")) as? JSONObject
            ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (k in listOf("end_date", "expire_billing_date", "phone", "tariff_plan", "status", "mac")) {
            val v = js.optString(k).trim()
            if (v.isNotBlank() && v != "null" && !v.startsWith("0000-00-00")) out[k] = v
        }
        // Xtream-based portals put the expiry date in "phone",
        // e.g. "November 17, 2026, 6:54 am".
        if (out["end_date"] == null && out["expire_billing_date"] == null) {
            out["phone"]?.let(::portalDate)?.let { out["end_date"] = it }
        }
        return out
    }

    /** A portal date in any of the shapes portals use, as "yyyy-MM-dd HH:mm:ss"; null if it is not a date. */
    private fun portalDate(raw: String): String? {
        val out = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (p in listOf("MMMM d, yyyy, h:mm a", "MMMM d, yyyy h:mm a", "MMMM d, yyyy", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")) {
            val d = runCatching { java.text.SimpleDateFormat(p, Locale.US).apply { isLenient = false }.parse(raw.trim()) }.getOrNull()
            if (d != null) return out.format(d)
        }
        return null
    }

    // ------------------------------------------------------------------
    // Live TV
    // ------------------------------------------------------------------

    fun liveCategories(s: Session): List<Category> {
        val arr = call(s, mapOf("type" to "itv", "action" to "get_genres")) as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank() || id == "*") null
            else Category(id, o.optString("title").ifBlank { o.optString("alias") }, "live", 0,
                o.optString("censored") == "1")
        }
    }

    fun channels(s: Session, catMap: Map<String, String>): List<Channel> {
        val all = (call(s, mapOf("type" to "itv", "action" to "get_all_channels", "pcode" to "0000")) as? JSONObject)
            ?.optJSONArray("data")
        val rows = ArrayList<JSONObject>()
        if (all != null && all.length() > 0) {
            for (i in 0 until all.length()) all.optJSONObject(i)?.let(rows::add)
        } else {
            // Portals that do not offer the full list are read genre by genre.
            for (genre in catMap.keys) rows += pages(s, mapOf("type" to "itv", "action" to "get_ordered_list", "genre" to genre))
        }
        val seen = HashSet<String>()
        return rows.mapNotNull { o ->
            val id = o.optString("id")
            if (id.isBlank() || !seen.add(id)) return@mapNotNull null
            val cmd = o.optString("cmd")
            val genre = o.optString("tv_genre_id")
            val url = playUrl("itv", cmd)
            Channel(
                id = "stk:$id",
                name = o.optString("name").trim(),
                logo = image(s, o.optString("logo")),
                categoryId = genre,
                categoryName = catMap[genre].orEmpty(),
                epgId = o.optString("xmltv_id"),
                number = o.optString("number").toIntOrNull() ?: 0,
                url = url,
                urlTs = url,
                archive = o.optString("tv_archive") == "1" || o.optInt("enable_tv_archive") == 1,
                archiveDays = (o.optString("tv_archive_duration").toIntOrNull() ?: 0) / 24,
                adult = o.optString("censored") == "1"
            )
        }
    }

    /** What is on now and next for a channel ([channelId] is the portal's own id). */
    fun shortEpg(s: Session, channelId: String, size: Int = 10): List<EpgProgram> {
        val js = call(s, mapOf("type" to "itv", "action" to "get_short_epg", "ch_id" to channelId, "size" to size.toString()))
        val arr = when (js) {
            is JSONArray -> js
            is JSONObject -> js.optJSONArray("data")
            else -> null
        } ?: return emptyList()
        val now = System.currentTimeMillis()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val start = o.optLong("start_timestamp") * 1000
            val end = o.optLong("stop_timestamp") * 1000
            if (start <= 0 || end <= start) return@mapNotNull null
            EpgProgram(
                title = o.optString("name"),
                start = start,
                end = end,
                description = o.optString("descr"),
                nowPlaying = now in start until end
            )
        }
    }

    // ------------------------------------------------------------------
    // Films and series
    // ------------------------------------------------------------------

    fun vodCategories(s: Session): List<Category> = categories(s, "vod", "movies")

    fun seriesCategories(s: Session): List<Category> = categories(s, "series", "series")

    private fun categories(s: Session, type: String, kind: String): List<Category> {
        val arr = runCatching { call(s, mapOf("type" to type, "action" to "get_categories")) }.getOrNull() as? JSONArray
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank() || id == "*") null
            else Category(id, o.optString("title").ifBlank { o.optString("alias") }, kind, 0, o.optString("censored") == "1")
        }
    }

    /** Every page of a listing. The portal decides the page size (often 14). */
    fun pages(s: Session, params: Map<String, String>, maxPages: Int = 500): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages && page <= maxPages) {
            val js = call(s, params + ("p" to page.toString())) as? JSONObject ?: break
            val data = js.optJSONArray("data") ?: break
            if (data.length() == 0) break
            for (i in 0 until data.length()) data.optJSONObject(i)?.let(out::add)
            val total = js.optInt("total_items", out.size)
            val perPage = js.optInt("max_page_items", data.length()).coerceAtLeast(1)
            totalPages = (total + perPage - 1) / perPage
            page++
        }
        return out
    }

    /** One category of films (or of series, with [type] "series"). */
    fun categoryItems(s: Session, type: String, categoryId: String): List<JSONObject> =
        pages(s, mapOf("type" to type, "action" to "get_ordered_list", "category" to categoryId, "sortby" to "added"))

    fun movieFrom(s: Session, o: JSONObject, catMap: Map<String, String>): Movie? {
        val id = o.optString("id").ifBlank { return null }
        val cat = o.optString("category_id")
        return Movie(
            id = "stkvod:$id",
            name = o.optString("name").trim(),
            poster = image(s, o.optString("screenshot_uri")),
            categoryId = cat,
            categoryName = catMap[cat].orEmpty(),
            rating = rating(o),
            year = o.optString("year").take(4).toIntOrNull(),
            plot = txt(o, "description"),
            cast = txt(o, "actors"),
            director = txt(o, "director"),
            genre = txt(o, "genres_str"),
            duration = o.optString("time").toIntOrNull()?.let { it * 60 },
            url = playUrl("vod", o.optString("cmd")),
            adult = o.optString("censored") == "1",
            added = added(o)
        )
    }

    fun seriesFrom(s: Session, o: JSONObject, type: String, catMap: Map<String, String>): Series? {
        val id = o.optString("id").ifBlank { return null }
        val cat = o.optString("category_id")
        // Older portals keep series among the films; the episode numbers and
        // play command travel with the item itself.
        val inline = o.optJSONArray("series")
        val seasons = if (type == "vod" && inline != null && inline.length() > 0) {
            listOf(Season(1, "Season 1", episodes = (0 until inline.length()).map { i ->
                val e = inline.optInt(i, i + 1)
                Episode("stkep:$id:1:$e", "Episode $e", 1, e, url = playUrl("vod", o.optString("cmd"), e))
            }))
        } else emptyList()
        return Series(
            id = "stkser:$type:$id",
            name = o.optString("name").trim(),
            poster = image(s, o.optString("screenshot_uri")),
            categoryId = cat,
            categoryName = catMap[cat].orEmpty(),
            rating = rating(o),
            year = o.optString("year").take(4).toIntOrNull(),
            plot = txt(o, "description"),
            cast = txt(o, "actors"),
            director = txt(o, "director"),
            genre = txt(o, "genres_str"),
            seasons = seasons,
            adult = o.optString("censored") == "1",
            added = added(o)
        )
    }

    /**
     * Seasons and episodes of a series from the portal's series section.
     * [portalId] is the portal's own id of the series.
     */
    fun seasons(s: Session, portalId: String): List<Season> {
        val rows = pages(s, mapOf(
            "type" to "series", "action" to "get_ordered_list",
            "movie_id" to portalId, "season_id" to "0", "episode_id" to "0"
        ), maxPages = 20)
        return rows.mapIndexedNotNull { idx, o ->
            val name = o.optString("name").trim()
            val number = Regex("(\\d+)").find(name)?.value?.toIntOrNull() ?: (idx + 1)
            val cmd = o.optString("cmd")
            val eps = o.optJSONArray("series") ?: JSONArray()
            val episodes = (0 until eps.length()).map { i ->
                val e = eps.optInt(i, i + 1)
                Episode("stkep:$portalId:$number:$e", "Episode $e", number, e, url = playUrl("vod", cmd, e))
            }
            if (episodes.isEmpty()) null
            else Season(number, name.ifBlank { "Season $number" }, cover = image(s, o.optString("screenshot_uri")), episodes = episodes)
        }.sortedBy { it.number }
    }

    /** A text field, with the portal's "N/A" placeholders treated as empty. */
    private fun txt(o: JSONObject, key: String): String {
        val v = o.optString(key).trim()
        return if (v.equals("N/A", true) || v.equals("null", true)) "" else v
    }

    private fun rating(o: JSONObject): Double? =
        listOf("rating_imdb", "rating_kinopoisk", "rating").firstNotNullOfOrNull { k ->
            o.optString(k).toDoubleOrNull()?.takeIf { it > 0 }
        }

    private fun added(o: JSONObject): Long {
        val raw = o.optString("added").ifBlank { return 0L }
        raw.toLongOrNull()?.let { return if (it < 10_000_000_000L) it * 1000 else it }
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(raw)?.time ?: 0L
        }.getOrDefault(0L)
    }

    /** Logos and posters are often given as a path on the portal. */
    private fun image(s: Session, raw: String): String {
        val v = raw.trim()
        if (v.isBlank() || v == "null") return ""
        if (v.startsWith("http://", true) || v.startsWith("https://", true)) return v
        return if (v.startsWith("/")) origin(s) + v else origin(s) + "/stalker_portal/misc/logos/320/" + v
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    /** Stored form of something playable: the portal command, resolved when it starts. */
    fun playUrl(type: String, cmd: String, series: Int? = null): String =
        "stalker://$type?cmd=" + enc(cmd) + (series?.let { "&series=$it" } ?: "")

    fun isStalker(url: String) = url.startsWith("stalker://")

    /** Turns a `stalker://` item into a real stream address for this session. */
    fun resolve(s: Session, url: String): String {
        val uri = java.net.URI(url)
        val type = uri.host ?: "itv"
        val q = uri.rawQuery.orEmpty().split("&").mapNotNull { p ->
            val i = p.indexOf('=')
            if (i <= 0) null else p.substring(0, i) to java.net.URLDecoder.decode(p.substring(i + 1), "UTF-8")
        }.toMap()
        val cmd = q["cmd"].orEmpty()
        // The same request STB+ makes for live channels and for films / episodes.
        val params = linkedMapOf(
            "type" to type, "action" to "create_link", "cmd" to cmd,
            "series" to (q["series"] ?: ""),
            "forced_storage" to if (type == "itv") "undefined" else "",
            "disable_ad" to "0"
        )
        val js = call(s, params) as? JSONObject
        var link = clean(js?.optString("cmd").orEmpty())
        if (link.isBlank()) return clean(cmd)

        // Some portals return the link with an empty stream number; fill it
        // in from the item itself ("./ch/1234_" for a channel, "./media/1234.mpg"
        // or the id in the response for a film), as STB+ does.
        if (Regex("stream=(&|$)").containsMatchIn(link)) {
            val id = js?.optString("id")?.takeIf { it.isNotBlank() && it != "null" }
                ?: Regex("/(?:ch|media)/(\\d+)").find(cmd)?.groupValues?.get(1)
            if (id != null) link = link.replace(Regex("stream=(&|$)")) { "stream=$id" + it.groupValues[1] }
        }
        return link
    }

    /** Play commands carry a player prefix ("ffmpeg ", "auto "). */
    private fun clean(cmd: String): String {
        val t = cmd.trim()
        val i = t.indexOf("http")
        return if (i > 0) t.substring(i).trim() else t
    }
}
