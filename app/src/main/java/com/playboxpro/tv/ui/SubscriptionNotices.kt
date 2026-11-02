package com.playboxpro.tv.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.playboxpro.tv.R
import com.playboxpro.tv.data.AppRepository
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Subscription notices.
 *
 * Two different expiries are watched, and the user is told about each one
 * separately, because they are fixed in different places:
 *  - the app licence (free trial or paid activation) - renewed on our website;
 *  - the playlist's own IPTV subscription - renewed with the IPTV provider.
 *
 * A few days before either ends, a "Notification" reminder is shown once a
 * day when the app opens. A playlist that has already expired is reported
 * the same way; an expired licence is handled on the activation screen.
 */
object SubscriptionNotices {

    const val SITE = "https://proplaybox.com"

    /** Reminders start this many days before the end date. */
    private const val WARN_DAYS = 3

    private const val DAY_MS = 24L * 60 * 60 * 1000

    // ------------------------------------------------------------------
    // The popup
    // ------------------------------------------------------------------

    class Notice(
        val title: String,
        val message: String,
        val note: String = "",
        val primary: String = "Dismiss",
        val secondary: String? = null,
        val onSecondary: () -> Unit = {},
        val onPrimary: () -> Unit = {}
    )

    fun show(activity: Activity, mac: String, key: String, n: Notice, onClosed: () -> Unit = {}) {
        if (activity.isFinishing) return
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_notice, null)
        val dialog = Dialog(activity, R.style.Theme_PlayBoxPro_Dialog).apply {
            setContentView(view)
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            ScreenFit.immersive(window)
        }
        view.findViewById<TextView>(R.id.noticeTitle).text = n.title
        view.findViewById<TextView>(R.id.noticeMessage).text = n.message
        view.findViewById<TextView>(R.id.noticeUrl).text = detail("URL: ", SITE)
        view.findViewById<TextView>(R.id.noticeMac).text = detail("Mac Address: ", mac)
        view.findViewById<TextView>(R.id.noticeKey).text = detail("Device Key: ", key)

        val note = view.findViewById<TextView>(R.id.noticeNote)
        val noteVisible = if (n.note.isBlank()) View.GONE else View.VISIBLE
        note.visibility = noteVisible
        view.findViewById<View>(R.id.noticeNoteDivider).visibility = noteVisible
        note.text = n.note

        val primary = view.findViewById<TextView>(R.id.noticePrimary)
        primary.text = n.primary
        primary.setOnClickListener { dialog.dismiss(); n.onPrimary() }

        val secondary = view.findViewById<TextView>(R.id.noticeSecondary)
        if (n.secondary != null) {
            secondary.visibility = View.VISIBLE
            secondary.text = n.secondary
            secondary.setOnClickListener { dialog.dismiss(); n.onSecondary() }
        }

        dialog.setOnDismissListener { onClosed() }
        // The remote lands on the button as soon as the popup appears.
        dialog.setOnShowListener {
            // On a TV the remote must start on the button, even in touch mode.
            if (ScreenFit.isTv(activity)) primary.isFocusableInTouchMode = true
            primary.requestFocus()
        }
        dialog.show()
    }

    /** "Label: value" with the value in the highlight blue. */
    private fun detail(label: String, value: String): CharSequence {
        val s = android.text.SpannableString(label + value)
        s.setSpan(
            android.text.style.ForegroundColorSpan(0xFF4FC3F7.toInt()),
            label.length, s.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return s
    }

    // ------------------------------------------------------------------
    // Reminders when the app opens
    // ------------------------------------------------------------------

    /**
     * Shows whichever reminders are due: the licence first, then the
     * playlist. Each kind appears at most once a day.
     */
    fun showDueReminders(activity: Activity, repo: AppRepository, mac: String, key: String) {
        val prefs = activity.getSharedPreferences("playbox_tv_prefs", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(java.util.Date())
        val due = ArrayList<Pair<String, Notice>>()

        val licenceEnd = parseDate(repo.licenseExpiresAt())
        if (licenceEnd > 0) {
            val days = daysLeft(licenceEnd)
            if (days in 0..WARN_DAYS && prefs.getString("notice_licence_day", "") != today) {
                due += "notice_licence_day" to licenceNotice(days, repo.licenseIsTrial())
            }
        }

        val playlistEnd = repo.playlistExpiresAtMs
        if (playlistEnd > 0) {
            val days = daysLeft(playlistEnd)
            if (days <= WARN_DAYS && prefs.getString("notice_playlist_day", "") != today) {
                due += "notice_playlist_day" to playlistNotice(days)
            }
        }

        fun next(i: Int) {
            val (pref, notice) = due.getOrNull(i) ?: return
            prefs.edit().putString(pref, today).apply()
            show(activity, mac, key, notice) { next(i + 1) }
        }
        next(0)
    }

    private fun inDays(days: Int) = when (days) {
        0 -> "today"
        1 -> "in 1 day"
        else -> "in $days days"
    }

    private fun licenceNotice(days: Int, trial: Boolean) = if (trial) {
        Notice(
            title = "Notification",
            message = "Your free trial will end ${inDays(days)}. To keep using the app without " +
                "interruption, activate your device on our website with the details below.",
            note = "Note: After activating, the app picks up the new date by itself."
        )
    } else {
        Notice(
            title = "Notification",
            message = "Your subscription will expire ${inDays(days)}. To keep watching without " +
                "interruption, renew your subscription on our website with the details below.",
            note = "Note: After renewing, the app picks up the new date by itself."
        )
    }

    private const val PROVIDER_NOTE =
        "Note: The playlist channel expiry date is unrelated to your app license. This is out of our " +
            "control, please contact your IPTV provider for further information."

    private fun playlistNotice(days: Int) = if (days < 0) {
        Notice(
            title = "Playlist Expired",
            message = "Your playlist has expired, so channels, movies and series may not play. " +
                "To extend your playlist please contact your IPTV provider.",
            note = PROVIDER_NOTE
        )
    } else {
        Notice(
            title = "Notification",
            message = "Your playlist will expire ${inDays(days)}. To extend your playlist please " +
                "contact your IPTV provider.",
            note = PROVIDER_NOTE
        )
    }

    // ------------------------------------------------------------------
    // Dates
    // ------------------------------------------------------------------

    /** Days until [endMs], counting a started day as a whole one (6 days 23 hours = 7); negative = already over. */
    fun daysLeft(endMs: Long): Int {
        val left = endMs - System.currentTimeMillis()
        return if (left < 0) -1 else ((left + DAY_MS - 1) / DAY_MS).toInt()
    }

    /** The server's date formats, as epoch millis; 0 when absent or unreadable. */
    fun parseDate(raw: String?): Long {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.startsWith("0000")) return 0
        s.toLongOrNull()?.let { return if (it < 10_000_000_000L) it * 1000 else it }
        val utc = TimeZone.getTimeZone("UTC")
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'" to utc,
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" to utc,
            "yyyy-MM-dd'T'HH:mm:ss'Z'" to utc,
            "yyyy-MM-dd HH:mm:ss" to TimeZone.getDefault(),
            "yyyy-MM-dd" to TimeZone.getDefault()
        )
        for ((p, tz) in patterns) {
            val f = SimpleDateFormat(p, Locale.US).apply { timeZone = tz; isLenient = false }
            runCatching { f.parse(s) }.getOrNull()?.let { return it.time }
        }
        return 0
    }
}
