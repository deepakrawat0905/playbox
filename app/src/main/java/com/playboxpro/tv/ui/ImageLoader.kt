package com.playboxpro.tv.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.FitCenter
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.playboxpro.tv.R

object ImageLoader {
    private val transparentDrawable = ColorDrawable(Color.TRANSPARENT)
    private val headers = LazyHeaders.Builder()
        .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; PlayBoxPro TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .addHeader("Accept", "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        .build()

    fun loadPoster(view: ImageView, url: String?, cornerRadiusDp: Int = 14) {
        val radiusPx = (cornerRadiusDp * view.resources.displayMetrics.density).toInt()
        val glideUrl = toGlideUrl(url)

        if (glideUrl == null) {
            view.setImageResource(R.drawable.bg_poster_placeholder)
            return
        }

        Glide.with(view.context)
            .load(glideUrl)
            .transform(CenterCrop(), RoundedCorners(radiusPx))
            .transition(DrawableTransitionOptions.withCrossFade(100))
            .placeholder(R.drawable.bg_poster_placeholder)
            .error(R.drawable.bg_poster_placeholder)
            .fallback(R.drawable.bg_poster_placeholder)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(view)
    }

    fun loadBackdrop(view: ImageView, url: String?, cornerRadiusDp: Int = 0) {
        val glideUrl = toGlideUrl(url)
        if (glideUrl == null) {
            view.setImageResource(R.drawable.bg_backdrop_placeholder)
            return
        }

        val builder = Glide.with(view.context).load(glideUrl)
        if (cornerRadiusDp > 0) {
            val radiusPx = (cornerRadiusDp * view.resources.displayMetrics.density).toInt()
            builder.transform(CenterCrop(), RoundedCorners(radiusPx))
        } else {
            builder.transform(CenterCrop())
        }

        builder.transition(DrawableTransitionOptions.withCrossFade(100))
            .placeholder(R.drawable.bg_backdrop_placeholder)
            .error(R.drawable.bg_backdrop_placeholder)
            .fallback(R.drawable.bg_backdrop_placeholder)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(view)
    }

    fun loadLogo(view: ImageView, url: String?) {
        val glideUrl = toGlideUrl(url)
        if (glideUrl == null) {
            view.setImageResource(R.drawable.bg_channel_placeholder)
            return
        }

        Glide.with(view.context)
            .load(glideUrl)
            .transform(FitCenter())
            .transition(DrawableTransitionOptions.withCrossFade(100))
            .placeholder(R.drawable.bg_channel_placeholder)
            .error(R.drawable.bg_channel_placeholder)
            .fallback(R.drawable.bg_channel_placeholder)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(view)
    }

    private fun toGlideUrl(url: String?): Any? {
        val u = url?.trim()
        if (u.isNullOrBlank()) return null
        return try {
            val sanitized = u.replace(" ", "%20")
            if (sanitized.startsWith("http://", ignoreCase = true) || sanitized.startsWith("https://", ignoreCase = true)) {
                GlideUrl(sanitized, headers)
            } else {
                sanitized
            }
        } catch (_: Exception) {
            u
        }
    }
}
