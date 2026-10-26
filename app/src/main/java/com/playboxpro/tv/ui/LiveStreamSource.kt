package com.playboxpro.tv.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * A live TV connection that never ends.
 *
 * IPTV servers commonly close a live MPEG-TS connection after a fixed
 * amount of data (this provider: about 16 MB, roughly a minute of a sports
 * channel) and expect the player to connect again. Seen by the player, that
 * is "end of stream": the picture stops and the channel has to be tuned from
 * scratch, leaving a black gap.
 *
 * This source reconnects underneath the player instead: when the server
 * closes the connection, or it drops, the same address is opened again and
 * the bytes keep flowing as one continuous stream, so playback carries on
 * without a gap. Only used for live channels; films keep their normal end.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiveStreamSource(private val upstream: DataSource) : DataSource {

    private var spec: DataSpec? = null
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    /** False for HLS, where every segment ends normally. */
    var continuous = true

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        val length = upstream.open(dataSpec)
        opened = true
        // A live stream has no length; the player must not expect an end.
        return if (continuous) C.LENGTH_UNSET.toLong() else length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!continuous) return upstream.read(buffer, offset, length)
        var attempt = 0
        while (true) {
            val n = try {
                upstream.read(buffer, offset, length)
            } catch (e: IOException) {
                if (attempt >= MAX_ATTEMPTS) throw e
                C.RESULT_END_OF_INPUT
            }
            if (n != C.RESULT_END_OF_INPUT) return n

            // The server closed the connection: open it again and carry on.
            if (attempt >= MAX_ATTEMPTS) return C.RESULT_END_OF_INPUT
            attempt++
            reconnect(attempt)
        }
    }

    private fun reconnect(attempt: Int) {
        val s = spec ?: throw IOException("Live stream was not opened")
        runCatching { upstream.close() }
        if (attempt > 1) Thread.sleep(300L * attempt)
        // From the live point again, whatever was asked for first.
        upstream.open(s.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build())
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (opened) {
            opened = false
            upstream.close()
        }
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        /** Set before each channel starts: continuous for plain TS, normal for HLS. */
        @Volatile var continuous = true

        fun prepareFor(url: String) {
            continuous = !url.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.endsWith(".m3u") }
        }

        override fun createDataSource(): DataSource =
            LiveStreamSource(upstream.createDataSource()).also { it.continuous = continuous }
    }

    private companion object {
        /** Reconnects in a row before giving up (then the player's own retry takes over). */
        const val MAX_ATTEMPTS = 5
    }
}
