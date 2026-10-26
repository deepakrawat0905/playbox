package com.playboxpro.tv.ui

import android.net.Uri
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * MPEG-TS reading that survives damaged data.
 *
 * Satellite-sourced IPTV streams now and then carry a corrupt frame - on
 * Star Sports a Dolby AC-3 header with an impossible frame size. The
 * player's stream parser throws on it, and one bad frame stops the whole
 * channel: the picture goes black until it is tuned again.
 *
 * Here each audio / video / subtitle reader is wrapped: when it throws, it
 * is reset and that piece of data is skipped, so it picks up again at the
 * next frame and playback carries on. Everything else is the player's
 * standard TS handling, set up exactly as its default factory does.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TolerantExtractorsFactory : ExtractorsFactory {

    private val delegate = DefaultExtractorsFactory()
    private var subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var textTrackTranscoding = true

    override fun createExtractors(): Array<Extractor> = replaceTs(delegate.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        replaceTs(delegate.createExtractors(uri, responseHeaders))

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        this.subtitleParserFactory = subtitleParserFactory
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    @Deprecated("Deprecated in Media3")
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        this.textTrackTranscoding = textTrackTranscodingEnabled
        @Suppress("DEPRECATION")
        delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    private fun replaceTs(list: Array<Extractor>): Array<Extractor> =
        Array(list.size) { i -> if (list[i] is TsExtractor) tolerantTs() else list[i] }

    private fun tolerantTs(): Extractor = TsExtractor(
        TsExtractor.MODE_SINGLE_PMT,
        if (textTrackTranscoding) 0 else TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
        subtitleParserFactory,
        TimestampAdjuster(0),
        TolerantPayloadFactory(DefaultTsPayloadReaderFactory(0, emptyList())),
        TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
    )
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class TolerantPayloadFactory(private val inner: TsPayloadReader.Factory) : TsPayloadReader.Factory {
    override fun createInitialPayloadReaders(): android.util.SparseArray<TsPayloadReader> =
        inner.createInitialPayloadReaders()

    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? =
        inner.createPayloadReader(streamType, esInfo)?.let { TolerantReader(it) }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class TolerantReader(private val inner: TsPayloadReader) : TsPayloadReader {
    override fun init(
        timestampAdjuster: TimestampAdjuster,
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator
    ) = inner.init(timestampAdjuster, extractorOutput, idGenerator)

    override fun seek() = inner.seek()

    override fun consume(data: ParsableByteArray, flags: Int) {
        try {
            inner.consume(data, flags)
        } catch (e: RuntimeException) {
            // A damaged frame: start this stream again at its next frame.
            inner.seek()
            data.setPosition(data.limit())
        }
    }
}
