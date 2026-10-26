package com.playboxpro.tv.ui

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.playboxpro.tv.data.AppRepository
import java.util.Locale

/**
 * Audio Track and Aspect Ratio for live TV, shared by the full-screen player
 * and the preview on the Live TV page so both behave the same.
 *
 * The audio choice is remembered per channel and identified by language,
 * channel layout and codec together, so "English 5.1" and "English Stereo"
 * on the same channel are never confused. A channel nobody has set by hand
 * always plays the stream's own default track - a choice made on one
 * channel never carries over to the next.
 */
object LivePlayerOptions {

    data class AudioOption(
        val group: Tracks.Group,
        val index: Int,
        val key: String,
        val label: String
    )

    val ASPECTS = listOf(
        "auto" to "Auto (Fit to Screen)",
        "original" to "Original",
        "16_9" to "16:9",
        "4_3" to "4:3",
        "stretch" to "Stretch to Fit",
        "zoom" to "Zoom"
    )

    // ------------------------------------------------------------------
    // Player setup
    // ------------------------------------------------------------------

    /**
     * Renderers for live TV that decode every audio track to ordinary PCM.
     *
     * By default Dolby (AC-3 / E-AC-3) tracks are handed to the TV's own
     * audio hardware undecoded while stereo tracks are mixed normally, so
     * picking another language moved the sound between two output paths:
     * a gap or silence on every switch, and on many boxes the Dolby path
     * ignores the volume altogether. Decoding everything in the player keeps
     * every language on the same path, so a switch is instant and audible.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun renderers(context: Context): androidx.media3.exoplayer.DefaultRenderersFactory =
        object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            // Video always uses the device's hardware decoders; the app's
            // own (FFmpeg) decoders are for audio only. A software video
            // decoder cannot keep up with HD / 4K channels on a TV box.
            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
            ) = super.buildVideoRenderers(
                context, EXTENSION_RENDERER_MODE_OFF, mediaCodecSelector, enableDecoderFallback,
                eventHandler, eventListener, allowedVideoJoiningTimeMs, out
            )

            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink =
                // The sink accepts decoded PCM only, so the renderer always
                // runs the track through a decoder instead of passing Dolby
                // straight to the TV. (The capabilities setting alone is
                // overridden whenever the TV re-reports what it supports.)
                object : androidx.media3.exoplayer.audio.ForwardingAudioSink(
                    androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context).build()
                ) {
                    override fun getFormatSupport(format: androidx.media3.common.Format): Int =
                        if (format.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_RAW) super.getFormatSupport(format)
                        else androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_UNSUPPORTED

                    override fun supportsFormat(format: androidx.media3.common.Format): Boolean =
                        format.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_RAW && super.supportsFormat(format)
                }
        }.setEnableDecoderFallback(true)
            // The device's own audio decoders come first (they cope best with
            // damaged broadcast audio); the app's FFmpeg decoders take over for
            // any format the device has no decoder for - Dolby AC-3 / E-AC-3,
            // MP2, DTS - so those channels still have sound on every device.
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            // TV-box hardware decoders often hand out a picture before telling
            // its format; the asynchronous decoder mode crashes on that, the
            // synchronous one handles it. Used for every device.
            .forceDisableMediaCodecAsynchronousQueueing()

    // ------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------

    /**
     * Where a player keeps its choices. Live TV remembers audio and
     * subtitles per channel and shares one aspect setting; the movie and
     * series player remembers them per title.
     */
    class Store(
        val repo: AppRepository,
        /** Channel or title id the audio / subtitle choice belongs to; null = not remembered. */
        val itemId: String?,
        val getAspect: () -> String,
        val setAspect: (String) -> Unit
    ) {
        fun audio(): String = itemId?.let { repo.liveAudioFor(it) }.orEmpty()
        fun saveAudio(key: String) { itemId?.let { repo.setLiveAudioFor(it, key) } }
        fun subtitle(): String = itemId?.let { repo.subtitleFor(it) }.orEmpty()
        fun saveSubtitle(key: String) { itemId?.let { repo.setSubtitleFor(it, key) } }
    }

    fun liveStore(repo: AppRepository, channelId: String?) =
        Store(repo, channelId, { repo.liveAspect }, { repo.liveAspect = it })

    /**
     * Audio Track / Subtitles / Aspect Ratio, plus any [extras] a player adds
     * (handled by [onExtra]). [onDone] runs once everything has closed.
     */
    fun showOptions(
        context: Context, player: Player?, view: PlayerView?, store: Store,
        extras: List<Pair<String, String>> = emptyList(),
        onExtra: (String) -> Unit = {},
        onDone: () -> Unit = {}
    ) {
        var chained = false
        StbDialogs.showSelection(
            context, "Player Options",
            listOf("audio" to "Audio Track", "subs" to "Subtitles", "aspect" to "Aspect Ratio") + extras, "",
            onClose = { if (!chained) onDone() }
        ) { picked ->
            when (picked) {
                "audio" -> { chained = true; showAudio(context, player, store, onDone) }
                "subs" -> { chained = true; showSubtitles(context, player, store, onDone) }
                "aspect" -> { chained = true; showAspect(context, player, view, store, onDone) }
                else -> onExtra(picked)
            }
        }
    }

    fun showAudio(context: Context, player: Player?, store: Store, onDone: () -> Unit = {}) {
        val options = audioOptions(player)
        if (player == null || options.isEmpty()) {
            StbDialogs.showSelection(context, "Audio Track", listOf("none" to "No audio tracks available"), "", onDone) {}
            return
        }
        val selected = options.indexOfFirst { it.group.isTrackSelected(it.index) }.coerceAtLeast(0)
        StbDialogs.showSelection(
            context, "Audio Track",
            options.mapIndexed { i, o -> i.toString() to o.label },
            selected.toString(),
            onDone
        ) { picked ->
            options.getOrNull(picked.toIntOrNull() ?: -1)?.let { o ->
                select(player, o)
                store.saveAudio(o.key)
            }
        }
    }

    fun showSubtitles(context: Context, player: Player?, store: Store, onDone: () -> Unit = {}) {
        val options = subtitleOptions(player)
        if (player == null || options.isEmpty()) {
            StbDialogs.showSelection(context, "Subtitles", listOf("none" to "No subtitles available"), "", onDone) {}
            return
        }
        val on = !player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val selected = options.indexOfFirst { on && it.group.isTrackSelected(it.index) }
        val rows = listOf("off" to "Off") + options.mapIndexed { i, o -> i.toString() to o.label }
        StbDialogs.showSelection(context, "Subtitles", rows, if (selected >= 0) selected.toString() else "off", onDone) { picked ->
            if (picked == "off") {
                subtitlesOff(player)
                store.saveSubtitle("off")
            } else {
                options.getOrNull(picked.toIntOrNull() ?: -1)?.let { o ->
                    selectSubtitle(player, o)
                    store.saveSubtitle(o.key)
                }
            }
        }
    }

    fun showAspect(context: Context, player: Player?, view: PlayerView?, store: Store, onDone: () -> Unit = {}) {
        StbDialogs.showSelection(context, "Aspect Ratio", ASPECTS, store.getAspect(), onDone) { picked ->
            store.setAspect(picked)
            if (player != null && view != null) applyAspect(view, player, picked)
        }
    }

    /**
     * Before a new channel or title starts: no earlier choice may follow it.
     * Subtitles start switched off unless the stream marks a track as
     * forced or default.
     */
    fun resetTracks(player: Player) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
    }

    /** Puts back the audio and subtitle choices saved for this channel / title. */
    fun restore(player: Player, store: Store) {
        restoreAudio(player, store)
        restoreSubtitle(player, store)
    }

    /** Bigger, outlined subtitles that read well from the sofa. */
    fun styleSubtitles(view: PlayerView) {
        view.subtitleView?.apply {
            setApplyEmbeddedStyles(false)
            setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.15f)
            setStyle(
                androidx.media3.ui.CaptionStyleCompat(
                    android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    android.graphics.Color.BLACK, null
                )
            )
        }
    }

    // ------------------------------------------------------------------
    // Subtitles
    // ------------------------------------------------------------------

    fun subtitleOptions(player: Player?): List<AudioOption> {
        val p = player ?: return emptyList()
        val out = ArrayList<AudioOption>()
        var n = 0
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                val f = g.getTrackFormat(i)
                val lang = f.language.orEmpty()
                val langName = lang.takeIf { it.isNotBlank() && it != "und" }
                    ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).ifBlank { it } }
                val mime = f.sampleMimeType.orEmpty()
                val cc = mime.contains("cea-608") || mime.contains("cea-708")
                val base = f.label?.takeIf { it.isNotBlank() }
                    ?: langName
                    ?: if (cc) "Closed Captions" else "Subtitle $n"
                val extra = when {
                    cc && langName != null -> "CC"
                    (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 -> "Forced"
                    else -> ""
                }
                val label = listOf(base, extra).filter { it.isNotBlank() }.joinToString("  •  ")
                out.add(AudioOption(g, i, "$lang|$mime|${f.label.orEmpty()}", label))
            }
        }
        val counts = out.groupingBy { it.label }.eachCount()
        val seen = HashMap<String, Int>()
        return out.map { o ->
            if ((counts[o.label] ?: 0) < 2) o
            else {
                val k = (seen[o.label] ?: 0) + 1
                seen[o.label] = k
                o.copy(label = "${o.label}  ($k)", key = "${o.key}|$k")
            }
        }
    }

    fun selectSubtitle(player: Player, o: AudioOption) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, listOf(o.index)))
            .build()
    }

    fun subtitlesOff(player: Player) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    private fun restoreSubtitle(player: Player, store: Store) {
        val wanted = store.subtitle()
        if (wanted.isBlank()) return
        if (wanted == "off") {
            if (!player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) subtitlesOff(player)
            return
        }
        val match = subtitleOptions(player).firstOrNull { it.key == wanted } ?: return
        if (!match.group.isTrackSelected(match.index)) selectSubtitle(player, match)
    }

    // ------------------------------------------------------------------
    // Audio
    // ------------------------------------------------------------------

    fun audioOptions(player: Player?): List<AudioOption> {
        val p = player ?: return emptyList()
        val out = ArrayList<AudioOption>()
        var n = 0
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                val f = g.getTrackFormat(i)
                val lang = f.language.orEmpty()
                val langName = lang.takeIf { it.isNotBlank() && it != "und" }
                    ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).ifBlank { it } }
                val base = f.label?.takeIf { it.isNotBlank() } ?: langName ?: "Track $n"
                val layout = when (f.channelCount) {
                    1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"
                    else -> if (f.channelCount > 0) "${f.channelCount} ch" else ""
                }
                val label = listOf(base, layout).filter { it.isNotBlank() }.joinToString("  •  ")
                val key = listOf(lang, f.channelCount.toString(), f.sampleMimeType.orEmpty()).joinToString("|")
                out.add(AudioOption(g, i, key, label))
            }
        }
        // Several tracks can share a label (two "English • Stereo"); number them.
        val counts = out.groupingBy { it.label }.eachCount()
        val seen = HashMap<String, Int>()
        return out.map { o ->
            if ((counts[o.label] ?: 0) < 2) o
            else {
                val k = (seen[o.label] ?: 0) + 1
                seen[o.label] = k
                o.copy(label = "${o.label}  ($k)", key = "${o.key}|$k")
            }
        }
    }

    /**
     * Audio tracks this device failed to decode. Some broadcasts carry a
     * Dolby track the device's decoder chokes on (Star Sports' E-AC-3 on
     * many boxes); instead of the channel going "offline", playback moves
     * to another audio track - same language first - and carries on. With
     * no other track, the picture plays without sound.
     */
    class AudioGuard {
        private val bad = HashMap<String, MutableSet<String>>()

        /** The channel / title now playing. */
        var itemId: String = ""

        /** Records a failed audio decoder; true when that is what [error] was. */
        fun onError(error: androidx.media3.common.PlaybackException): Boolean {
            val e = error as? androidx.media3.exoplayer.ExoPlaybackException ?: return false
            if (e.type != androidx.media3.exoplayer.ExoPlaybackException.TYPE_RENDERER) return false
            val f = e.rendererFormat ?: return false
            if (!androidx.media3.common.MimeTypes.isAudio(f.sampleMimeType)) return false
            // The track itself (its stream id), not its codec: a damaged track
            // can be read as a different codec from one moment to the next.
            return bad.getOrPut(itemId) { mutableSetOf() }.add(trackKey(f))
        }

        private fun trackKey(f: androidx.media3.common.Format) = f.id ?: f.sampleMimeType.orEmpty()

        /** Once the tracks are known: steer away from a track that failed before. */
        fun apply(player: Player) {
            val failed = bad[itemId] ?: return
            val options = audioOptions(player)
            if (options.isEmpty()) return
            fun mime(o: AudioOption) = trackKey(o.group.getTrackFormat(o.index))
            val current = options.firstOrNull { it.group.isTrackSelected(it.index) }
            if (current != null && mime(current) !in failed) return
            val lang = current?.let { it.group.getTrackFormat(it.index).language }
            val alt = options.filter { mime(it) !in failed }
                .sortedByDescending { it.group.getTrackFormat(it.index).language == lang }
                .firstOrNull()
            if (alt != null) {
                select(player, alt)
            } else if (!player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
            }
        }
    }

    fun select(player: Player, o: AudioOption) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, listOf(o.index)))
            .build()
    }

    private fun restoreAudio(player: Player, store: Store) {
        val wanted = store.audio()
        if (wanted.isBlank()) return
        val options = audioOptions(player)
        val match = options.firstOrNull { it.key == wanted }
            // Saved before tracks were told apart by layout and codec.
            ?: options.firstOrNull { it.key.substringBefore('|') == wanted }
            ?: return
        if (!match.group.isTrackSelected(match.index)) select(player, match)
    }

    // ------------------------------------------------------------------
    // Aspect ratio
    // ------------------------------------------------------------------

    fun applyAspect(view: PlayerView, player: Player, mode: String) {
        val frame = view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
        when (mode) {
            "auto" -> VideoFit.apply(view, player.videoSize)
            "stretch" -> view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            "zoom" -> view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        val fixed = when (mode) {
            "16_9" -> 16f / 9f
            "4_3" -> 4f / 3f
            else -> null
        }
        if (fixed != null) {
            frame?.setAspectRatio(fixed)
        } else {
            val v = player.videoSize
            if (v.width > 0 && v.height > 0) frame?.setAspectRatio(v.width * v.pixelWidthHeightRatio / v.height)
        }
    }
}
