package com.playboxpro.tv.data

import android.content.Context
import com.playboxpro.tv.api.LicenseApi
import com.playboxpro.tv.model.Account
import com.playboxpro.tv.model.DeviceState
import com.playboxpro.tv.model.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

/**
 * Decides, on every launch, whether the saved playlist may still be played.
 *
 * The licensing server is the only authority: a device keeps watching for
 * exactly as long as the server grants it playlist access (an active trial
 * or a live activation) and the saved playlist is still assigned to it.
 * The saved credentials on their own never unlock the app.
 *
 * When the server cannot be reached, the last confirmed verdict is honoured
 * for a short grace window so that a flaky connection does not lock a paid
 * device out; a device that has never been confirmed gets no grace at all.
 */
object LicenseGate {

    sealed class Verdict {
        object Allowed : Verdict()
        data class Denied(val reason: String) : Verdict()
    }

    /** How long an unreachable server is tolerated after a good check. */
    private const val OFFLINE_GRACE_MS = 3L * 24 * 60 * 60 * 1000

    /** A running app re-confirms with the server this often. */
    const val RECHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** The splash must not hang on a slow licensing server. */
    private const val TIMEOUT_MS = 10_000L

    const val OFFLINE_REASON =
        "Unable to verify this device. Check the internet connection and try again."

    /** True when the last confirmation is old enough to be worth repeating. */
    fun isStale(repo: AppRepository): Boolean {
        val age = System.currentTimeMillis() - repo.licenseVerifiedAt()
        // A confirmation dated in the future means the clock was moved; it
        // proves nothing, so the server is asked again.
        return age < 0 || age > RECHECK_INTERVAL_MS
    }

    suspend fun verify(repo: AppRepository, context: Context): Verdict {
        val account = repo.account() ?: return Verdict.Denied("")
        val wasActive = repo.isActive()

        val mac = LicenseApi.deviceMac(context)
        val key = LicenseApi.deviceKey(mac)

        // The request runs on its own so the limit really holds: a blocking
        // socket stuck on bad DNS ignores cancellation, and waiting inside
        // it kept the splash up for a minute on a poor connection.
        val request = com.playboxpro.tv.PlayBoxApp.scope.async(Dispatchers.IO) {
            runCatching { LicenseApi.fetch(mac, key) }.getOrNull()
        }
        val state: DeviceState? = withTimeoutOrNull(TIMEOUT_MS) { request.await() }

        if (state == null) {
            val okAt = repo.licenseVerifiedAt()
            val age = System.currentTimeMillis() - okAt
            val withinGrace = wasActive && okAt > 0 && age >= 0 && age < OFFLINE_GRACE_MS
            return if (withinGrace) {
                Verdict.Allowed
            } else {
                if (wasActive) repo.revokeAccess(OFFLINE_REASON)
                Verdict.Denied(if (wasActive) OFFLINE_REASON else repo.licenseDeniedReason().ifBlank { OFFLINE_REASON })
            }
        }

        if (!state.active) {
            val reason = state.message.ifBlank { "This device is not activated." }
            repo.revokeAccess(reason)
            return Verdict.Denied(reason)
        }

        // The playlist saved on this device has to be one the server still
        // assigns to it; a playlist removed on the website stops playing here.
        val assigned = state.playlists.firstOrNull { sameSource(it, account) }
        if (assigned == null) {
            val reason = "The playlist on this device was removed. Select a profile to continue."
            repo.revokeAccess(reason)
            return Verdict.Denied(reason)
        }

        // Credentials edited on the website take effect on the next launch.
        if (assigned.password.isNotBlank() && assigned.password != account.password &&
            assigned.username == account.username
        ) {
            repo.saveAccount(account.copy(password = assigned.password))
        }

        // Granted again (renewed on the website, back online): playback
        // returns without the user having to pick the profile again.
        if (!wasActive) repo.setActive(true)
        repo.markLicenseVerified(state)
        return Verdict.Allowed
    }

    /**
     * Same server and, where both sides have one, same user. Only the
     * authority is compared because an M3U link gets rewritten to its
     * Xtream portal once the app connects, which changes path and port
     * style but never the server itself.
     */
    private fun sameSource(p: Playlist, a: Account): Boolean {
        val ph = hostOf(p.host)
        val ah = hostOf(a.host)
        if (ph.isBlank() || ph != ah) return false
        if (p.username.isNotBlank() && a.username.isNotBlank() && p.username != a.username) return false
        return true
    }

    private fun hostOf(raw: String): String {
        val s = raw.trim().let {
            if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "http://$it"
        }
        return try {
            URI(s).host.orEmpty().lowercase().removePrefix("www.")
        } catch (_: Exception) {
            s.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()
        }
    }
}
