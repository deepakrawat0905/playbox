package com.playboxpro.tv

import android.app.Application
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.playboxpro.tv.api.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.InputStream

class PlayBoxApp : Application() {

    companion object {
        /**
         * Work that must outlive whichever screen started it - the playlist
         * download that the splash kicks off and MainActivity then picks up.
         */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onCreate() {
        super.onCreate()
        com.playboxpro.tv.api.NetworkWatch.start(this)
        try {
            Glide.get(this).registry.replace(
                GlideUrl::class.java,
                InputStream::class.java,
                OkHttpUrlLoader.Factory(Http.client)
            )
        } catch (_: Exception) {}
    }
}
