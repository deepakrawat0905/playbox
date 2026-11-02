package com.playboxpro.tv.api

import android.content.Context
import com.playboxpro.tv.model.*
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import java.security.MessageDigest

object LicenseApi {
    private const val BASE = "https://api.proplaybox.com/api"

    /**
     * This device's MAC address: permanent for the device.
     *
     * It is calculated from identifiers the device itself provides, never
     * from anything random, so it comes out the same every time - after a
     * restart, an app update, and also after uninstalling and reinstalling:
     *  - the Widevine DRM device ID (fixed per device, kept even through a
     *    factory reset), or where a device has no Widevine,
     *  - the Android ID (fixed per device; reset only by a factory reset).
     * SHA-256 of that, first 10 hex characters, behind the locally
     * administered prefix 02: (02:XX:XX:XX:XX:XX). The hardware MAC is never
     * read (Android returns 02:00:00:00:00:00). The result is also kept in
     * encrypted storage.
     */
    @Synchronized
    fun deviceMac(context: Context? = null): String {
        installIdNow = installId(context).ifBlank { installIdNow }
        cachedMac?.let { return it }
        if (context == null) return derivedMac(null, 0)

        val secure = SecureStore.prefs(context)
        val mac = derivedMac(context, secure.getInt(KEY_MAC_SALT, 0))
        val stored = secure.getString(KEY_VIRTUAL_MAC, null)
        if (stored != mac) {
            secure.edit().putString(KEY_VIRTUAL_MAC, mac).commit()
            // An install that had a MAC from an earlier version starts again
            // at activation: its saved playlist belonged to the old identity.
            val plain = context.getSharedPreferences("playbox_tv_prefs", Context.MODE_PRIVATE)
            if (stored != null || plain.contains("device_mac")) {
                plain.edit().remove("device_mac").remove("device_mac_scheme")
                    .remove("account_host").remove("account_active").remove("registered_mac").apply()
            }
        }
        cachedMac = mac
        return mac
    }

    /**
     * The server reported this MAC as already taken by another device (HTTP
     * 409). The next MAC in this device's own fixed sequence is used - still
     * calculated, not random, so a reinstall arrives at the same one again.
     */
    @Synchronized
    fun regenerateDeviceMac(context: Context): String {
        val secure = SecureStore.prefs(context)
        val salt = secure.getInt(KEY_MAC_SALT, 0) + 1
        val mac = derivedMac(context, salt)
        secure.edit().putInt(KEY_MAC_SALT, salt).putString(KEY_VIRTUAL_MAC, mac).commit()
        context.getSharedPreferences("playbox_tv_prefs", Context.MODE_PRIVATE).edit()
            .remove("account_host").remove("account_active").remove("registered_mac").apply()
        cachedMac = mac
        return mac
    }

    @Volatile private var cachedMac: String? = null

    private const val KEY_VIRTUAL_MAC = "VIRTUAL_MAC"
    private const val KEY_MAC_SALT = "VIRTUAL_MAC_SALT"
    private const val KEY_RANDOM_SEED = "VIRTUAL_MAC_SEED"

    /** This installation's ID, sent with every licensing request (see [installId]). */
    @Volatile private var installIdNow = ""

    /** SHA-256 of the device's own identity, first 10 hex characters, behind 02:. */
    private fun derivedMac(context: Context?, salt: Int): String {
        val identity = widevineId()?.let { "wv:$it" }
            ?: androidId(context)?.let { "aid:$it" }
            // A device with neither (very rare) gets a random seed, kept in
            // encrypted storage; only there can a reinstall change the MAC.
            ?: context?.let { c ->
                val s = SecureStore.prefs(c)
                s.getString(KEY_RANDOM_SEED, null) ?: java.util.UUID.randomUUID().toString().also {
                    s.edit().putString(KEY_RANDOM_SEED, it).commit()
                }
            }.orEmpty()
        val seed = "playboxpro:$identity" + if (salt > 0) "#$salt" else ""
        val hex = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
            .joinToString("") { String.format("%02X", it) }
        return "02:" + hex.take(10).chunked(2).joinToString(":")
    }

    private fun androidId(context: Context?): String? = context?.let {
        runCatching {
            android.provider.Settings.Secure.getString(it.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        }.getOrNull()
    }?.trim()?.takeIf { it.length >= 8 && it != "9774d56d682e549c" && it.any { c -> c != '0' } }

    /** The Widevine DRM device ID as hex, or null where Widevine is not available. */
    private fun widevineId(): String? = runCatching {
        val widevine = java.util.UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
        val drm = android.media.MediaDrm(widevine)
        try {
            drm.getPropertyByteArray(android.media.MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
                .joinToString("") { String.format("%02x", it) }
                .takeIf { it.length >= 16 && it.any { c -> c != '0' } }
        } finally {
            if (android.os.Build.VERSION.SDK_INT >= 28) drm.close() else @Suppress("DEPRECATION") drm.release()
        }
    }.getOrNull()

    /**
     * A random ID for this installation, sent alongside the MAC so the
     * licensing server can tell apart two devices presenting the same MAC
     * (a cloned or copied install) and refuse the second one.
     */
    fun installId(context: Context?): String {
        val prefs = context?.getSharedPreferences("playbox_tv_prefs", Context.MODE_PRIVATE)
            ?: return ""
        prefs.getString("install_id", null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("install_id", id).apply()
        return id
    }

    /**
     * Exact 1:1 match with React deriveDeviceKey algorithm (FNV-1a 32-bit hash + base32)
     */
    fun deviceKey(mac: String): String {
        val clean = mac.replace(Regex("[^0-9A-Fa-f]"), "")
        var hash = 0x811c9dc5L
        for (ch in clean) {
            hash = hash xor ch.code.toLong()
            hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        }
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        var out = ""
        var value = hash
        for (i in 0 until 6) {
            val idx = (value % alphabet.length).toInt()
            out += alphabet[idx]
            value /= alphabet.length
        }
        return "${out.substring(0, 3)}-${out.substring(3)}"
    }

    /**
     * First non-blank value among [keys].
     *
     * JSONObject.optString turns a JSON null into the literal string "null",
     * which would otherwise be stored as a real username/password and sent
     * upstream. isNull() screens those out.
     */
    private fun JSONObject.firstString(vararg keys: String): String {
        for (k in keys) {
            if (isNull(k)) continue
            val v = optString(k).trim()
            if (v.isNotBlank() && !v.equals("null", ignoreCase = true)) return v
        }
        return ""
    }

    /** A Stalker / MAG portal address (".../c/", "portal.php", "stalker_portal", "load.php"). */
    private fun isPortalUrl(raw: String): Boolean {
        val u = raw.trim().lowercase()
        if (u.isBlank()) return false
        val path = runCatching { java.net.URI(u).path.orEmpty() }.getOrDefault(u).trimEnd('/')
        return path.endsWith("portal.php") || path.endsWith("load.php") ||
            path.endsWith("/c") || u.contains("stalker_portal")
    }

    /** Xtream host + credentials recovered from a get.php / player_api.php link. */
    private fun xtreamCredentialsFromUrl(raw: String): Account? {
        if (raw.isBlank()) return null
        val uri = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return null
        val path = uri.path.orEmpty().lowercase()
        if (!path.endsWith("get.php") && !path.endsWith("player_api.php")) return null
        val q = uri.rawQuery.orEmpty().split("&").mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null else {
                val k = java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8")
                val v = java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8")
                k to v
            }
        }.toMap()
        val user = q["username"].orEmpty()
        val pass = q["password"].orEmpty()
        if (user.isBlank() || pass.isBlank()) return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return Account(host = "${uri.scheme}://${uri.host}$port", username = user, password = pass, type = "xtream")
    }

    /**
     * Registers this device (MAC + App Key) with the licensing server the
     * first time the app runs with that MAC. Once the server has confirmed
     * it, the app never calls again for the same MAC. Returns true when the
     * device is registered (now or before).
     */
    fun registerDeviceOnce(context: Context): Boolean {
        val prefs = context.getSharedPreferences("playbox_tv_prefs", Context.MODE_PRIVATE)
        var mac = deviceMac(context)
        if (prefs.getString("registered_mac", null) == mac) return true

        // A 409 Conflict means another device already holds this MAC: a new
        // one is generated from a fresh UUID and registered instead.
        repeat(MAX_COLLISION_RETRIES + 1) {
            val body = JSONObject()
                .put("mac_address", mac)
                .put("device_key", deviceKey(mac))
                .put("install_id", installId(context))
                .put("device_model", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
            val request = okhttp3.Request.Builder()
                .url("$BASE/TvDeviceRegister")
                .header("Accept", "application/json")
                .post(okhttp3.RequestBody.create("application/json".toMediaType(), body.toString()))
                .build()
            val (code, text) = runCatching {
                Http.client.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
            }.getOrElse { return false }

            if (code == 409) {
                mac = regenerateDeviceMac(context)
                return@repeat
            }
            val ok = code in 200..299 && runCatching { JSONObject(text).optBoolean("status", false) }.getOrDefault(false)
            // Only a confirmed registration is remembered; otherwise it is
            // tried again on the next start.
            if (ok) prefs.edit().putString("registered_mac", mac).apply()
            return ok
        }
        return false
    }

    /** New MACs tried after a 409 before giving up until the next start. */
    private const val MAX_COLLISION_RETRIES = 3

    /**
     * The licensing server's own view of a playlist account (expiry, status,
     * connections) for playlists whose details cannot be read directly -
     * e.g. a portal link with no Xtream credentials. Returns the JSON object
     * holding the account fields, or null when the server has nothing.
     */
    fun accountDetails(playlistUrl: String, mac: String, key: String): JSONObject? {
        val body = JSONObject()
            .put("playlist_url", playlistUrl)
            .put("mac_address", mac)
            .put("mac", mac)
            .put("install_id", installIdNow)
            .put("device_model", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
            .put("device_key", key)
        val text = runCatching { Http.postJson("$BASE/admin/Playlist/accountDetails", body.toString()) }
            .getOrNull() ?: return null
        if (text.isBlank() || text.trimStart().startsWith("<")) return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.has("message") && root.length() == 1) return null   // "Unauthenticated." etc.
        val data = root.optJSONObject("data") ?: root
        return data.optJSONObject("user_info")
            ?: data.optJSONObject("account")
            ?: data.optJSONObject("details")
            ?: data
    }

    fun fetch(mac: String, key: String): DeviceState {
        val body = JSONObject()
            .put("mac_address", mac)
            .put("mac", mac)
            .put("install_id", installIdNow)
            .put("device_model", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
            .put("device_key", key)

        val endpoints = listOf(
            "/getDevicePlaylistSubscription",
            "/device/getDevicePlaylistSubscription",
            "/user/device/getDevicePlaylistSubscription"
        )

        var lastEx: Exception? = null
        for (endpoint in endpoints) {
            try {
                val text = Http.postJson(BASE + endpoint, body.toString())
                if (text.isBlank()) continue

                val root = JSONObject(text)
                val data = root.optJSONObject("data") ?: root
                val device = data.optJSONObject("device")
                val subscription = data.optJSONObject("subscription")
                val rows = data.optJSONArray("playlists") ?: device?.optJSONArray("playlists")

                val playlists = mutableListOf<Playlist>()
                if (rows != null) {
                    for (i in 0 until rows.length()) {
                        val r = rows.optJSONObject(i) ?: continue
                        val status = r.optString("status").lowercase()
                        if (status in setOf("inactive", "disabled", "blocked", "suspended", "expired", "deleted")) {
                            continue
                        }

                        val rawType = r.firstString("type", "playlist_type", "source_type").lowercase()
                        val portal = r.firstString("portal_url", "server_url")
                        val url = r.firstString("url", "playlist_url", "link", "m3u_url")
                        var username = r.firstString("username", "user")
                        var password = r.firstString("password", "pass", "playlist_password")
                        var hostVal = r.firstString("host", "server", "portal", "dns")

                        // A playlist handed over as a bare get.php link still
                        // carries its Xtream credentials in the query; pull
                        // them out so the account can be authenticated and its
                        // details shown, exactly as if they had been sent
                        // as separate fields.
                        val fromUrl = xtreamCredentialsFromUrl(url.ifBlank { portal })
                        if (fromUrl != null) {
                            if (username.isBlank()) username = fromUrl.username
                            if (password.isBlank()) password = fromUrl.password
                            if (hostVal.isBlank()) hostVal = fromUrl.host
                        }

                        var type = "m3u"
                        if (rawType.contains("xtream") || rawType == "xc") type = "xtream"
                        else if (rawType.contains("m3u") && fromUrl == null) type = "m3u"
                        else if (username.isNotBlank() && (hostVal.isNotBlank() || portal.isNotBlank() || url.isNotBlank())) type = "xtream"

                        // Stalker / MAG portals: a portal address and a MAC
                        // address instead of a username and password.
                        val portalLink = listOf(portal, url, hostVal).firstOrNull { isPortalUrl(it) }.orEmpty()
                        val portalType = rawType.contains("stalker") || rawType.contains("mag") ||
                            rawType.contains("ministra") || rawType == "mac" || rawType == "portal"
                        if (fromUrl == null && (portalType || portalLink.isNotBlank())) {
                            type = "stalker"
                            hostVal = portalLink.ifBlank { portal.ifBlank { url.ifBlank { hostVal } } }
                            // The portal MAC the server assigned, else this device's own.
                            username = r.firstString("portal_mac", "stb_mac", "mac_address", "mac").ifBlank { mac }
                            password = ""
                        }

                        val finalHost = if (type == "xtream" || type == "stalker") {
                            hostVal.ifBlank { portal.ifBlank { url } }
                        } else {
                            url.ifBlank { portal.ifBlank { hostVal } }
                        }

                        if (finalHost.isBlank()) continue

                        val name = r.firstString("name", "title", "playlist_name")
                            .ifBlank { "Playlist ${playlists.size + 1}" }
                        val id = r.firstString("id", "playlist_id")
                            .ifBlank { "srv-${playlists.size}" }
                        val epg = r.firstString("epg_url", "epg", "xmltv_url")

                        playlists.add(
                            Playlist(
                                id = id,
                                name = name,
                                type = type,
                                host = finalHost,
                                username = username,
                                password = password,
                                epgUrl = epg,
                                enabled = true
                            )
                        )
                    }
                }

                val deviceStatus = device?.optString("status") ?: "unknown"
                val deviceFound = device != null
                val isBlocked = deviceStatus.equals("blocked", true) ||
                    deviceStatus.equals("suspended", true) ||
                    deviceStatus.equals("inactive", true)

                val trialExpired = device?.optBoolean("trial_expired", false) == true
                val trialActive = device?.optBoolean("trial_active", false) == true
                val trialExpiry = device?.firstString("trial_expires_at").orEmpty()
                val activatedAt = device?.firstString("activated_at").orEmpty()
                val activationExpiry = device?.firstString("activation_expires_at").orEmpty()

                // The server is the authority on whether this device may
                // play. `playlist_access` already folds in the trial window,
                // activation expiry and the device status; the local checks
                // only cover an older server that does not send it.
                val playlistAccess = when {
                    device == null -> false
                    device.has("playlist_access") && !device.isNull("playlist_access") ->
                        device.optBoolean("playlist_access", false)
                    else -> !isBlocked && (trialActive || activationExpiry.isNotBlank())
                }
                val isActive = !isBlocked && playlistAccess && playlists.isNotEmpty()

                // The API's own message is a request status ("Device details
                // fetched successfully"), not something to tell the user when
                // there is nothing to play. Say what actually happened.
                val message = when {
                    isActive -> ""
                    isBlocked -> "This device has been blocked. Please contact support."
                    !deviceFound -> "This device is not registered yet."
                    !playlistAccess && activationExpiry.isNotBlank() && !trialActive ->
                        "Your activation has expired. Renew this device on the website to keep watching."
                    !playlistAccess && trialExpired ->
                        "Your free trial has ended. Activate this device on the website to keep watching."
                    !playlistAccess -> "This device is not activated yet."
                    playlists.isEmpty() -> "No playlist is assigned to this device yet."
                    else -> ""
                }

                return DeviceState(
                    active = isActive,
                    found = deviceFound,
                    status = deviceStatus,
                    message = message,
                    mac = device?.optString("mac_address", mac) ?: mac,
                    deviceKey = key,
                    playlists = playlists,
                    expiresAt = subscription?.firstString("end_date", "ends_at", "expires_at")
                        ?.ifBlank { null }
                        ?: activationExpiry.ifBlank { trialExpiry.ifBlank { null } },
                    trialActive = trialActive,
                    trialExpired = trialExpired,
                    trialExpiresAt = trialExpiry,
                    activatedAt = activatedAt,
                    activationExpiresAt = activationExpiry,
                    playlistAccess = playlistAccess
                )
            } catch (e: Exception) {
                lastEx = e
            }
        }

        throw lastEx ?: Exception("No licensing endpoint answered")
    }
}
