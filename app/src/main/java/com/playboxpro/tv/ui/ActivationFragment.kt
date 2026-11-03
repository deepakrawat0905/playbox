package com.playboxpro.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.playboxpro.tv.MainActivity
import com.playboxpro.tv.R
import com.playboxpro.tv.api.LicenseApi
import com.playboxpro.tv.model.Account
import com.playboxpro.tv.model.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Device activation and profile selection.
 *
 * Two states share this fragment, matching the STB+ onboarding flow:
 *
 *  1. No playlists assigned - the activation screen, showing the QR code and
 *     the website steps with this device's MAC and App Key, polling the
 *     licensing API in the background until a playlist appears.
 *  2. Playlists assigned - the profile picker rail.
 *
 * Manual Xtream / M3U entry stays available from both states.
 */
class ActivationFragment : Fragment(R.layout.fragment_activation) {
    private val vm: AppViewModel by activityViewModels()

    companion object {
        /** Where users manage playlists for their device. */
        private const val ACTIVATION_SITE = "proplaybox.com"
        private const val ACTIVATION_URL = "https://proplaybox.com/"

        /** How long the on-screen countdown runs before it resets. */
        private const val COUNTDOWN_SECONDS = 5 * 60

        /** How often the licensing API is polled while waiting. */
        private const val POLL_INTERVAL_MS = 8_000L

    }

    // Activation state
    private lateinit var activationView: ViewGroup
    private lateinit var siteText: TextView
    private lateinit var macValue: TextView
    private lateinit var deviceKeyValue: TextView
    private lateinit var countdownText: TextView
    private lateinit var statusBanner: TextView
    private lateinit var imgQrCode: ImageView

    // Profile state
    private lateinit var profileView: ViewGroup
    private lateinit var rvPlaylist: RecyclerView
    private lateinit var footMacValue: TextView
    private lateinit var footAppKeyValue: TextView
    private lateinit var imgQrCodeSmall: ImageView
    private lateinit var profileAdapter: ProfileAdapter

    private lateinit var accountInfoHost: FrameLayout

    // Connect progress / failure surface
    private lateinit var connectingOverlay: View
    private lateinit var connectingSpinner: View
    private lateinit var connectingText: TextView
    private lateinit var btnConnectDismiss: Button

    private var serverPlaylists: List<Playlist> = emptyList()

    /** The licensing server's last answer for this device. */
    private var lastDeviceState: com.playboxpro.tv.model.DeviceState? = null

    /** True while a playlist is being loaded, so polling stays out of the way. */
    private var isConnecting = false

    /** Guards the one-time focus handoff when the profile rail first appears. */
    private var profileStateShown = false

    private var backCallback: androidx.activity.OnBackPressedCallback? = null

    private var isPolling = true
    private var secondsLeft = COUNTDOWN_SECONDS
    private val handler = Handler(Looper.getMainLooper())

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (isPolling && isAdded && !isConnecting && !isConnectOverlayOpen()) {
                checkDeviceState(silent = true)
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isAdded) return
            secondsLeft = if (secondsLeft <= 0) COUNTDOWN_SECONDS else secondsLeft - 1
            countdownText.text = formatCountdown(secondsLeft)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The legal terms come first on a device that has not accepted them.
        LegalTerms.requireAccepted(requireActivity(), vm.repo)

        activationView = view.findViewById(R.id.activationView)
        siteText = view.findViewById(R.id.tvActivationSite)
        macValue = view.findViewById(R.id.macValue)
        deviceKeyValue = view.findViewById(R.id.deviceKeyValue)
        countdownText = view.findViewById(R.id.tvCountdown)
        statusBanner = view.findViewById(R.id.activationStatusBanner)
        imgQrCode = view.findViewById(R.id.imgQrCode)

        profileView = view.findViewById(R.id.profileView)
        rvPlaylist = view.findViewById(R.id.rvPlaylist)
        footMacValue = view.findViewById(R.id.footMacValue)
        footAppKeyValue = view.findViewById(R.id.footAppKeyValue)
        imgQrCodeSmall = view.findViewById(R.id.imgQrCodeSmall)

        accountInfoHost = view.findViewById(R.id.accountInfoHost)

        connectingOverlay = view.findViewById(R.id.connectingOverlay)
        connectingSpinner = view.findViewById(R.id.connectingSpinner)
        connectingText = view.findViewById(R.id.connectingText)
        btnConnectDismiss = view.findViewById(R.id.btnConnectDismiss)
        btnConnectDismiss.setOnClickListener { dismissConnecting() }

        bindDeviceIdentity()
        setupRail()
        setupBackHandling()
        observeErrors()

        showActivationState()
        vm.repo.licenseDeniedReason().takeIf { it.isNotBlank() }?.let { showBanner(it, isError = true) }
        checkDeviceState(silent = false)

        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        handler.post(tickRunnable)
    }

    // ------------------------------------------------------------------
    // Device identity: MAC, App Key, QR
    // ------------------------------------------------------------------

    private fun bindDeviceIdentity() {
        // The headline and the step texts are STB+'s own wording and live
        // in the layout; only the site and the identity are filled in here.
        siteText.text = ACTIVATION_SITE

        // STB+ upper-cases the MAC on this screen.
        macValue.text = vm.mac.uppercase()
        deviceKeyValue.text = vm.deviceKey
        footMacValue.text = vm.mac
        footAppKeyValue.text = vm.deviceKey

        // The QR carries the device identity so the site can pre-fill it.
        val payload = "$ACTIVATION_URL?mac=${vm.mac}&key=${vm.deviceKey}"
        QrCode.encode(payload)?.let { bmp ->
            imgQrCode.setImageBitmap(bmp)
            imgQrCodeSmall.setImageBitmap(bmp)
        }
    }

    private fun formatCountdown(total: Int): String {
        val m = total / 60
        val s = total % 60
        return String.format(java.util.Locale.US, "%02d:%02d", m, s)
    }

    // ------------------------------------------------------------------
    // State switching
    // ------------------------------------------------------------------

    private fun showActivationState() {
        activationView.visibility = View.VISIBLE
        profileView.visibility = View.GONE
        profileStateShown = false
    }

    private fun showProfileState() {
        activationView.visibility = View.GONE
        profileView.visibility = View.VISIBLE

        // Focus has to be handed to the rail when this state appears, and also
        // recovered if it was lost - hiding the activation view destroys
        // whatever was focused inside it, which otherwise leaves the screen
        // with no focus at all and makes D-pad OK do nothing.
        if (!profileStateShown || requireActivity().currentFocus == null) {
            profileStateShown = true
            focusFirstProfile()
        }
    }

    /**
     * Moves focus onto the first profile card.
     *
     * The rail is populated by notifyDataSetChanged and only becomes visible
     * as part of the same pass, so its children may not exist yet on the first
     * attempt. Retry briefly rather than failing silently.
     */
    private fun focusFirstProfile(attempt: Int = 0) {
        if (!isAdded || profileView.visibility != View.VISIBLE) return

        val card = rvPlaylist.getChildAt(0)?.findViewById<View>(R.id.containerProfile)
        if (card != null && card.requestFocus()) return

        if (attempt < 20) {
            rvPlaylist.postDelayed({ focusFirstProfile(attempt + 1) }, 50L)
        }
    }

    // ------------------------------------------------------------------
    // Profile rail
    // ------------------------------------------------------------------

    private fun setupRail() {
        profileAdapter = ProfileAdapter(
            entries = emptyList(),
            onSelect = { entry -> onProfileSelected(entry) },
            onRefresh = { entry -> refreshPlaylist(entry) },
            onInfo = { entry -> showAccountInfo(entry) }
        )
        rvPlaylist.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvPlaylist.adapter = profileAdapter

    }

    private fun rebuildProfiles() {
        val activeHost = vm.repo.account()?.host.orEmpty()

        val entries = serverPlaylists.map { p ->
            ProfileEntry(
                id = p.id,
                name = p.name,
                playlist = p,
                isActive = p.host.isNotBlank() && p.host == activeHost
            )
        }

        profileAdapter.submit(entries)
    }

    private fun onProfileSelected(entry: ProfileEntry) {
        entry.playlist?.let { connectPlaylist(it) }
    }

    private fun connectPlaylist(p: Playlist) {
        if (isConnecting) return

        if (p.host.isBlank()) {
            showConnectError("This profile has no server address. Re-add it from the website.")
            return
        }

        beginConnecting("Loading ${p.name}...")

        vm.connect(
            Account(
                host = normalizeUrl(p.host),
                username = p.username,
                password = p.password,
                type = p.type,
                epgUrl = normalizeUrl(p.epgUrl),
                name = p.name
            ),
            onDone = {
                if (!isAdded) return@connect
                isConnecting = false
                requireActivity().runOnUiThread {
                    // Live TV is the default screen, so a connected playlist lands
                        // there rather than on a separate home page.
                        (activity as? MainActivity)?.open("live")
                }
            }
        )
    }

    /**
     * The card's refresh button: the playlist's details are read again from
     * the licensing server (they may have been edited on the website), then
     * the whole playlist - channels, movies, series, categories - is
     * downloaded fresh. The user stays here and is told when it is done.
     */
    private fun refreshPlaylist(entry: ProfileEntry) {
        if (isConnecting) return
        if (!com.playboxpro.tv.api.NetworkWatch.isOnline) {
            showConnectError(com.playboxpro.tv.api.NetworkWatch.NO_INTERNET)
            return
        }
        beginConnecting("Refreshing ${entry.name}...")

        lifecycleScope.launch {
            val state = runCatching {
                withContext(Dispatchers.IO) { LicenseApi.fetch(vm.mac, vm.deviceKey) }
            }.getOrNull()
            if (!isAdded) return@launch
            if (state != null) {
                lastDeviceState = state
                serverPlaylists = state.playlists
                rebuildProfiles()
            }

            val p = state?.playlists?.firstOrNull { it.id == entry.id } ?: entry.playlist
            if (p == null || p.host.isBlank()) {
                showConnectError("This profile has no server address. Re-add it from the website.")
                return@launch
            }

            vm.refreshPlaylist(
                Account(
                    host = normalizeUrl(p.host),
                    username = p.username,
                    password = p.password,
                    type = p.type,
                    epgUrl = normalizeUrl(p.epgUrl),
                    name = p.name
                )
            ) { lib ->
                if (!isAdded) return@refreshPlaylist
                rebuildProfiles()
                showConnectMessage(
                    "${p.name} refreshed\n${lib.channels.size} channels · " +
                        "${lib.movies.size} movies · ${lib.series.size} series"
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Connect progress / failure
    // ------------------------------------------------------------------

    /** Shows the spinner and holds off polling while the library loads. */
    private fun beginConnecting(message: String) {
        isConnecting = true
        connectingText.text = message
        connectingSpinner.visibility = View.VISIBLE
        btnConnectDismiss.visibility = View.GONE
        connectingOverlay.visibility = View.VISIBLE
        refreshBackCallback()
    }

    /**
     * Surfaces a connection failure. Without this the error only reached the
     * activation banner, which is hidden in the profile state - so a failed
     * profile tap looked like nothing had happened at all.
     */
    private fun showConnectError(message: String) = showConnectMessage(message)

    /** The overlay's final word - an error or a refresh result - with a button to close it. */
    private fun showConnectMessage(message: String) {
        isConnecting = false
        connectingText.text = message
        connectingSpinner.visibility = View.GONE
        btnConnectDismiss.visibility = View.VISIBLE
        connectingOverlay.visibility = View.VISIBLE
        refreshBackCallback()
        btnConnectDismiss.post { btnConnectDismiss.requestFocus() }
    }

    private fun dismissConnecting() {
        isConnecting = false
        connectingOverlay.visibility = View.GONE
        refreshBackCallback()
        if (profileView.visibility == View.VISIBLE) {
            focusFirstProfile()
        }
    }

    private fun isConnectOverlayOpen(): Boolean =
        connectingOverlay.visibility == View.VISIBLE

    /**
     * Keeps D-pad focus inside whichever overlay is open.
     *
     * A scrim stops touches but not focus traversal, so without this the
     * remote could walk off a panel onto the profile rail behind it and
     * activate a card the user cannot even see.
     */
    private fun updateOverlayFocusGuard() {
        val overlayOpen = isConnectOverlayOpen() ||
            accountInfoHost.visibility == View.VISIBLE

        val mode = if (overlayOpen) {
            ViewGroup.FOCUS_BLOCK_DESCENDANTS
        } else {
            ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        activationView.descendantFocusability = mode
        profileView.descendantFocusability = mode
    }

    // ------------------------------------------------------------------
    // Account Info overlay
    // ------------------------------------------------------------------

    /**
     * STB+ "Account Info": the playlist's own details as its server reports
     * them - URL, user, masked password, type, status (green when Active,
     * red otherwise), expiry date, days left and connections in use. The
     * server is asked while the dialog is up, so the rows read "..." /
     * "Loading..." until it answers and "---" if it never does.
     */
    private fun showAccountInfo(entry: ProfileEntry) {
        val p = entry.playlist
        val v = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_account_info, accountInfoHost, false)

        val tvUrl = v.findViewById<TextView>(R.id.tvInfoUrl)
        val tvUser = v.findViewById<TextView>(R.id.tvInfoUsername)
        val tvPass = v.findViewById<TextView>(R.id.tvInfoPassword)
        val tvType = v.findViewById<TextView>(R.id.tvInfoType)
        val tvStatus = v.findViewById<TextView>(R.id.tvInfoStatus)
        val tvConn = v.findViewById<TextView>(R.id.tvInfoConnections)
        val tvExpiry = v.findViewById<TextView>(R.id.tvInfoExpiry)
        val tvDays = v.findViewById<TextView>(R.id.tvInfoDaysLeft)

        tvUrl.text = "URL: " + maskUrl(p?.host.orEmpty())
        tvUser.text = "Username: " + p?.username.orEmpty()
        tvPass.text = "Password: " + p?.password.orEmpty().replace(Regex("."), "*")
        tvType.text = "Type: " + (p?.type ?: "").uppercase()
        tvStatus.text = "Status: Loading..."
        tvStatus.setTextColor(requireContext().getColor(R.color.white))
        tvExpiry.text = "Expire: ..."
        tvDays.text = "Days: ..."
        tvConn.text = "Conn: ..."

        val close = v.findViewById<Button>(R.id.btnCloseDialog)
        close.setOnClickListener { hideAccountInfo() }

        accountInfoHost.removeAllViews()
        accountInfoHost.addView(v)
        accountInfoHost.visibility = View.VISIBLE
        refreshBackCallback()
        close.post { close.requestFocus() }

        if (p == null) {
            tvStatus.text = "Status: ---"; tvExpiry.text = "Expire: ---"
            tvDays.text = "Days: ---"; tvConn.text = "Conn: ---"
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                // The playlist's own server first (Xtream player_api); when
                // that cannot answer - no credentials, a portal link, a
                // server that refuses the request - the licensing server is
                // asked for the account details it holds for this playlist.
                val direct = if (p.type == "stalker") {
                    // Portal playlists report their own expiry once the MAC signs in.
                    runCatching {
                        val s = com.playboxpro.tv.api.StalkerApi.session(normalizeUrl(p.host), p.username)
                        val m = com.playboxpro.tv.api.StalkerApi.accountInfo(s)
                        org.json.JSONObject().apply {
                            put("status", "Active")
                            put("username", com.playboxpro.tv.api.StalkerApi.normalizeMac(p.username))
                            (m["end_date"] ?: m["expire_billing_date"])?.let { put("exp_date", it) }
                        }
                    }.getOrNull()
                } else if (p.username.isNotBlank() && p.password.isNotBlank()) {
                    runCatching {
                        val a = Account(
                            host = normalizeUrl(p.host), username = p.username,
                            password = p.password, type = p.type, name = p.name
                        )
                        com.playboxpro.tv.api.XtreamApi.authenticate(a).optJSONObject("user_info")
                    }.getOrNull()
                } else null
                direct ?: LicenseApi.accountDetails(p.host, vm.mac, vm.deviceKey)
            }
            if (!isAdded || accountInfoHost.visibility != View.VISIBLE) return@launch

            if (info == null) {
                // No account behind the playlist (a plain M3U link): show
                // this device's own subscription from the licensing server.
                showDeviceSubscription(tvStatus, tvExpiry, tvDays, tvConn)
                return@launch
            }

            fun field(vararg keys: String): String {
                for (k in keys) {
                    if (!info.has(k) || info.isNull(k)) continue
                    val v = info.optString(k).trim()
                    if (v.isNotBlank() && !v.equals("null", true)) return v
                }
                return ""
            }

            // Details the licensing server knows that the playlist row lacked.
            field("username", "user").takeIf { it.isNotBlank() && p.username.isBlank() }
                ?.let { tvUser.text = "Username: $it" }
            field("password", "pass").takeIf { it.isNotBlank() && p.password.isBlank() }
                ?.let { tvPass.text = "Password: " + it.replace(Regex("."), "*") }

            val status = field("status", "account_status").ifBlank { "Loading..." }
            tvStatus.text = "Status: $status"
            tvStatus.setTextColor(if (status.equals("Active", true)) 0xFF00FF00.toInt() else 0xFFFF0000.toInt())

            val exp = field("exp_date", "expiry_date", "expires_at", "expire_date", "expiry", "expires")
            val expMs = expiryMillis(exp)
            if (exp.isBlank() || exp == "...") {
                tvExpiry.text = "Expire: ---"
                tvDays.text = "Days: ---"
            } else if (expMs != null) {
                val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                tvExpiry.text = "Expire: " + date.format(java.util.Date(expMs))
                val days = java.util.concurrent.TimeUnit.MILLISECONDS
                    .toDays(expMs - System.currentTimeMillis()).coerceAtLeast(0)
                tvDays.text = "Days: $days"
            } else {
                tvExpiry.text = "Expire: $exp"
                tvDays.text = "Days: -"
            }

            val active = field("active_cons", "active_connections")
            val max = field("max_connections", "max_cons")
            tvConn.text = if (active.isBlank() && max.isBlank()) "Conn: ---" else "Conn: $active / $max"
        }
    }

    /**
     * The server address with all but its start hidden, the way the password
     * is: "http://wolfmonsters.com:8080" reads "http://wol*****************".
     */
    private fun maskUrl(url: String): String {
        val u = url.trim()
        val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").find(u)?.value.orEmpty()
        val rest = u.removePrefix(scheme)
        val shown = rest.take(3)
        return scheme + shown + "*".repeat(rest.length - shown.length)
    }

    private fun showDeviceSubscription(
        tvStatus: TextView, tvExpiry: TextView, tvDays: TextView, tvConn: TextView
    ) {
        tvConn.text = "Conn: ---"
        val s = lastDeviceState
        if (s == null) {
            tvStatus.text = "Status: ---"; tvExpiry.text = "Expire: ---"; tvDays.text = "Days: ---"
            return
        }
        val (label, expiry) = when {
            s.activationExpiresAt.isNotBlank() -> s.status.ifBlank { "Activated" } to s.activationExpiresAt
            s.trialActive -> "Trial" to s.trialExpiresAt
            s.trialExpired -> "Trial Expired" to s.trialExpiresAt
            else -> s.status to ""
        }
        tvStatus.text = "Status: $label"
        tvStatus.setTextColor(if (s.playlistAccess) 0xFF00FF00.toInt() else 0xFFFF0000.toInt())
        val ms = expiryMillis(expiry)
        if (ms == null) {
            tvExpiry.text = "Expire: ---"; tvDays.text = "Days: ---"
        } else {
            tvExpiry.text = "Expire: " + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(ms))
            tvDays.text = "Days: " + java.util.concurrent.TimeUnit.MILLISECONDS
                .toDays(ms - System.currentTimeMillis()).coerceAtLeast(0)
        }
    }

    /** Unix seconds, unix millis or an ISO / "yyyy-MM-dd HH:mm:ss" date -> millis. */
    private fun expiryMillis(raw: String): Long? {
        if (raw.isBlank()) return null
        raw.toLongOrNull()?.let { return if (it < 1_000_000_000_000L) it * 1000 else it }
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")) {
            val fmt = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
            if (pattern.contains("'T'")) fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            runCatching { fmt.parse(raw.take(pattern.replace("'", "").length))?.time }.getOrNull()
                ?.let { return it }
        }
        return null
    }

    private fun hideAccountInfo() {
        accountInfoHost.removeAllViews()
        accountInfoHost.visibility = View.GONE
        refreshBackCallback()
        focusFirstProfile()
    }

    // ------------------------------------------------------------------
    private fun normalizeUrl(raw: String): String {
        val s = raw.trim()
        if (s.isBlank()) return ""
        return if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            "http://$s"
        } else {
            s
        }
    }

    // ------------------------------------------------------------------
    // Licensing API
    // ------------------------------------------------------------------

    private fun checkDeviceState(silent: Boolean) {
        lifecycleScope.launch {
            try {
                val state = withContext(Dispatchers.IO) {
                    LicenseApi.fetch(vm.mac, vm.deviceKey)
                }
                if (!isAdded) return@launch

                lastDeviceState = state
                serverPlaylists = state.playlists
                rebuildProfiles()

                if (state.active && state.playlists.isNotEmpty()) {
                    if (vm.repo.isActive()) vm.repo.markLicenseVerified(state)
                    // A playlist arrived: stop waiting and let the user pick one.
                    secondsLeft = COUNTDOWN_SECONDS
                    resetStatusNote()
                    showProfileState()
                } else {
                    if (vm.repo.isActive()) {
                        vm.repo.revokeAccess(state.message)
                    }
                    showActivationState()
                    // Trial over / subscription over / trial running: say so
                    // plainly, as the expiry screens of other players do.
                    if (bindLicenceNotice(state)) return@launch
                    if (!silent) {
                        if (state.message.isNotBlank()) {
                            showBanner(state.message, isError = true)
                        } else {
                            resetStatusNote()
                        }
                    }
                }
            } catch (e: Exception) {
                if (!silent && isAdded) {
                    showBanner(
                        com.playboxpro.tv.api.NetworkWatch.messageFor(e, "Unable to contact activation server"),
                        isError = true
                    )
                }
            }
        }
    }

    /** The "Subscription Expired" popup appears once per visit to this screen. */
    private var expiredPopupShown = false

    /**
     * Expiry state of the licence on the activation screen:
     *  - subscription ended: "Notice: Subscription Expired!" plus a popup;
     *  - free trial ended:   "Notice: Trial Period Expired!" plus a popup;
     *  - free trial running: how many trial days are left.
     * Returns true when it has set the status line itself.
     */
    private fun bindLicenceNotice(s: com.playboxpro.tv.model.DeviceState): Boolean {
        val headline = requireView().findViewById<TextView>(R.id.tvActivationHeadline)
        val now = System.currentTimeMillis()
        val activationEnd = SubscriptionNotices.parseDate(s.activationExpiresAt)
        // A device that was never activated is on a trial; the server marks an
        // ended trial "Expired" too, so the two are told apart by activation.
        val everActivated = s.activatedAt.isNotBlank() || activationEnd > 0
        val ended = !s.trialActive && !s.playlistAccess &&
            (s.trialExpired || s.status.contains("expired", true) || activationEnd in 1 until now)
        val subscriptionExpired = ended && everActivated
        val trialExpired = ended && !everActivated

        if (subscriptionExpired || trialExpired) {
            headline.text = if (trialExpired) "Notice: Trial Period Expired!" else "Notice: Subscription Expired!"
            headline.setTextColor(requireContext().getColor(R.color.danger))
            showBanner(
                if (trialExpired) "Your trial period has ended. To continue enjoying uninterrupted access to the app, " +
                    "activate your device on our website using the MAC address and device key above."
                else "Your subscription has ended. To continue enjoying uninterrupted access to the app, " +
                    "renew it on our website using the MAC address and device key above.",
                isError = true
            )
            if (!expiredPopupShown) {
                expiredPopupShown = true
                SubscriptionNotices.show(
                    requireActivity(), vm.mac, vm.deviceKey,
                    SubscriptionNotices.Notice(
                        title = if (trialExpired) "Trial Period Expired" else "Subscription Expired",
                        message = if (trialExpired)
                            "Your free trial has ended. To keep using the app, activate your device on our website with the details below."
                        else
                            "Your subscription has expired. To keep watching, renew it on our website with the details below.",
                        note = "Note: After renewing, press Reload or wait a moment - this screen updates by itself.",
                        primary = "OK",
                        secondary = "Reload",
                        onSecondary = { checkDeviceState(silent = false) }
                    )
                )
            }
            return true
        }

        headline.text = "Link your device to continue"
        headline.setTextColor(0xBFFFFFFF.toInt())
        val trialEnd = SubscriptionNotices.parseDate(s.trialExpiresAt)
        if (s.trialActive && trialEnd > now) {
            val days = SubscriptionNotices.daysLeft(trialEnd)
            showBanner(
                if (days >= 1) "You have $days day${if (days == 1) "" else "s"} of free trial to test the app with your own playlist."
                else "Your free trial ends today.",
                isError = false
            )
            return true
        }
        return false
    }

    private fun observeErrors() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.error.collectLatest { err ->
                    if (err.isNotBlank() && isAdded) {
                        // A failed connect must be visible in whichever state
                        // the user is in, not just on the activation screen.
                        if (isConnecting || isConnectOverlayOpen()) {
                            showConnectError(err)
                        } else {
                            showBanner(err, isError = true)
                        }
                    }
                }
            }
        }
    }

    /** Restores the neutral "page may take a few seconds" note. */
    private fun resetStatusNote() {
        statusBanner.setTextColor(requireContext().getColor(R.color.light_gray))
        statusBanner.text =
            "The page may take a few seconds to update after you have completed the steps on your device ..."
    }

    private fun showBanner(msg: String, isError: Boolean) {
        statusBanner.visibility = View.VISIBLE
        statusBanner.text = msg
        statusBanner.setTextColor(
            requireContext().getColor(if (isError) R.color.danger else R.color.light_gray)
        )
    }

    // ------------------------------------------------------------------
    // Back handling
    // ------------------------------------------------------------------

    private fun setupBackHandling() {
        val cb = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                when {
                    accountInfoHost.visibility == View.VISIBLE -> hideAccountInfo()
                    isConnectOverlayOpen() -> dismissConnecting()
                }
            }
        }
        backCallback = cb
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, cb)
    }

    private fun refreshBackCallback() {
        // Every overlay transition routes through here, so this is also where
        // the background focus guard is kept in sync.
        updateOverlayFocusGuard()

        backCallback?.isEnabled =
            accountInfoHost.visibility == View.VISIBLE ||
                (isConnectOverlayOpen() && !isConnecting)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isPolling = false
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(tickRunnable)
    }
}

// ----------------------------------------------------------------------
// Profile rail model + adapter
// ----------------------------------------------------------------------

data class ProfileEntry(
    val id: String,
    val name: String,
    val playlist: Playlist? = null,
    val isActive: Boolean = false
)

/**
 * Horizontal profile cards: avatar in a focus ring, a check badge on the
 * active playlist, and refresh / info options beneath.
 */
class ProfileAdapter(
    private var entries: List<ProfileEntry>,
    private val onSelect: (ProfileEntry) -> Unit,
    private val onRefresh: (ProfileEntry) -> Unit,
    private val onInfo: (ProfileEntry) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val container: View = v.findViewById(R.id.containerProfile)
        val icon: ImageView = v.findViewById(R.id.ivIcon)
        val check: ImageView = v.findViewById(R.id.ivActiveCheck)
        val name: TextView = v.findViewById(R.id.tvProfileName)
        val refresh: ImageView = v.findViewById(R.id.ivRefresh)
        val info: ImageView = v.findViewById(R.id.ivInfo)
    }

    fun submit(list: List<ProfileEntry>) {
        // The licence poll re-submits the same profiles every few seconds;
        // rebinding them each time knocked the remote's focus off the card.
        if (list == entries) return
        entries = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false))

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = entries[position]

        holder.name.text = e.name
        holder.check.visibility = if (e.isActive) View.VISIBLE else View.GONE
        holder.info.visibility = View.VISIBLE
        holder.refresh.visibility = View.VISIBLE

        holder.container.setOnClickListener { onSelect(e) }
        holder.refresh.setOnClickListener { onRefresh(e) }
        holder.info.setOnClickListener { onInfo(e) }

        // The small refresh / info buttons sit in a row under each card.
        // Left and right walk that row across cards (info -> next card's
        // refresh), which Android's own search skips over.
        holder.info.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            ) jumpTo(holder, entries.indexOf(e) + 1, toInfo = false) else false
        }
        holder.refresh.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
            ) jumpTo(holder, entries.indexOf(e) - 1, toInfo = true) else false
        }
    }

    /** Focus the refresh (or info) button of the card at [position]; true when it moved. */
    private fun jumpTo(from: VH, position: Int, toInfo: Boolean): Boolean {
        if (position !in entries.indices) return true   // stay put at either end
        val rv = from.itemView.parent as? RecyclerView ?: return false
        rv.scrollToPosition(position)
        val target = rv.findViewHolderForAdapterPosition(position) as? VH ?: return true
        return (if (toInfo) target.info else target.refresh).requestFocus()
    }
}
