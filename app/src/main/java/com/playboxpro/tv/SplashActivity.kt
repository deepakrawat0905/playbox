package com.playboxpro.tv

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.playboxpro.tv.data.AppRepository
import com.playboxpro.tv.data.LicenseGate
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launch screen, matched to STB+'s SplashActivity.
 *
 * STB+ opens on a dedicated splash activity rather than painting a brand
 * over the first real screen, which is why its start-up never flashes an
 * empty page. Same here: the playlist is fetched and parsed while the
 * splash is up, so MainActivity opens onto a library that is already
 * warm in the on-disk cache.
 */
class SplashActivity : AppCompatActivity() {
    // Phones: the TV layout scaled to fit (see ScreenFit).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.playboxpro.tv.ui.ScreenFit.wrap(newBase))
    }


    private companion object {
        /** The dots have to actually be seen, even on a warm start. */
        const val MIN_DWELL_MS = 500L

        /**
         * Never hold the brand hostage to a slow provider or a dead
         * connection. This only applies when nothing is saved yet; past it
         * the app opens and Live TV says the playlist is still loading (or
         * why it could not be).
         */
        const val MAX_WAIT_MS = 20_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        com.playboxpro.tv.ui.ScreenFit.immersive(this)

        val repo = AppRepository(application)
        val startedAt = SystemClock.elapsedRealtime()

        lifecycleScope.launch {
            // First start with this MAC: register the device with the licensing
            // server before anything asks about it. Skipped once confirmed;
            // never holds the splash longer than 8 seconds.
            // The identity is read (or created) off the main thread: encrypted
            // storage talks to the Android Keystore.
            withContext(Dispatchers.IO) { com.playboxpro.tv.api.LicenseApi.deviceMac(applicationContext) }
            if (com.playboxpro.tv.api.NetworkWatch.isOnline) {
                kotlinx.coroutines.withTimeoutOrNull(8_000) {
                    withContext(Dispatchers.IO) {
                        com.playboxpro.tv.api.LicenseApi.registerDeviceOnce(applicationContext)
                    }
                }
            }

            // A saved playlist is checked even when access was taken away,
            // so a renewed (or back-online) device returns by itself.
            if (repo.account() != null) {
                // The licensing server decides whether the saved playlist may
                // still play; the cache is read in parallel so a granted
                // device does not pay for the round trip.
                // A device the server confirmed within the last few hours
                // opens straight away and is re-checked in the background;
                // MainActivity takes it back to activation if that fails.
                // An activated device with its playlist saved opens straight away
                // and is checked behind the first screen; MainActivity moves it to
                // the activation screen within seconds if the server says no.
                val confirmedRecently = repo.isActive() && (!LicenseGate.isStale(repo) || repo.hasLibraryCache())
                val verdict = if (confirmedRecently) {
                    PlayBoxApp.scope.launch { LicenseGate.verify(repo, applicationContext) }
                    kotlinx.coroutines.CompletableDeferred<LicenseGate.Verdict>(LicenseGate.Verdict.Allowed)
                } else {
                    async { LicenseGate.verify(repo, applicationContext) }
                }

                // Reading the cache costs seconds for a large playlist, so it
                // happens exactly once - here, behind the brand - and is left
                // in memory for MainActivity to paint from instantly.
                val cacheRead = if (repo.hasLibraryCache()) {
                    // Only the live half. The catalogue is tens of thousands
                    // of titles that the first screen never draws, so it is
                    // parsed afterwards instead of holding the splash open.
                    async(Dispatchers.IO) { repo.loadLibraryFromDisk(includeVod = false) }
                } else {
                    null
                }

                val allowed = verdict.await() is LicenseGate.Verdict.Allowed
                val cached = if (allowed) {
                    cacheRead?.await()
                } else {
                    // A refused device has nothing to paint; do not keep it
                    // on the splash for a cache it will not be shown.
                    cacheRead?.cancel()
                    null
                }

                if (!allowed) {
                    // MainActivity routes to the activation screen, which
                    // shows the server's reason and keeps polling.
                } else if (cached != null) {
                    repo.setCachedLibrary(cached)
                } else {
                    // Nothing to fall back on. Fetch on the application scope
                    // so the catalogue keeps downloading after this screen is
                    // gone, and hold the brand only until the channels are in.
                    // Failures are not fatal - MainActivity surfaces the error.
                    val liveReady = kotlinx.coroutines.CompletableDeferred<Unit>()
                    PlayBoxApp.scope.launch {
                        runCatching {
                            repo.loadLibrary(force = true, onLive = { liveReady.complete(Unit) })
                        }
                        liveReady.complete(Unit)
                    }
                    kotlinx.coroutines.withTimeoutOrNull(MAX_WAIT_MS) { liveReady.await() }
                }
            }

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed < MIN_DWELL_MS) delay(MIN_DWELL_MS - elapsed)

            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            // Both screens sit on the same gradient, so a cross-fade would
            // only show as a flicker.
            overridePendingTransition(0, 0)
            finish()
        }
    }
}
