package org.jellyfin.mobile.player.source

import android.media.MediaCodecInfo.CodecProfileLevel
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser

/**
 * Wraps an [ExtractorsFactory] so that Dolby Vision profile 7 video tracks are exposed as HEVC.
 *
 * Profile 7 (used by UHD Blu-ray remuxes) consists of an HDR10 base layer and an enhancement layer.
 * HEVC decoders skip the enhancement layer, so decoding the track as HEVC shows the HDR10 picture
 * instead of no picture at all on devices without a profile 7 capable Dolby Vision decoder.
 */
class DolbyVisionCompatExtractorsFactory(
    private val delegate: ExtractorsFactory,
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = delegate.createExtractors().wrap()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).wrap()

    @Deprecated("Deprecated in Java")
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory = apply {
        @Suppress("DEPRECATION")
        delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory = apply {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory = apply {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
    }

    private fun Array<Extractor>.wrap(): Array<Extractor> = Array(size) { i -> DolbyVisionCompatExtractor(this[i]) }

    private class DolbyVisionCompatExtractor(private val delegate: Extractor) : Extractor {
        override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

        override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails

        override fun init(output: ExtractorOutput) {
            delegate.init(DolbyVisionCompatExtractorOutput(output))
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = delegate.read(input, seekPosition)

        override fun seek(position: Long, timeUs: Long) {
            delegate.seek(position, timeUs)
        }

        override fun release() {
            delegate.release()
        }

        override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
    }

    private class DolbyVisionCompatExtractorOutput(output: ExtractorOutput) : ForwardingExtractorOutput(output) {
        override fun track(id: Int, type: Int): TrackOutput {
            val trackOutput = super.track(id, type)
            return if (type == C.TRACK_TYPE_VIDEO) DolbyVisionCompatTrackOutput(trackOutput) else trackOutput
        }
    }

    private class DolbyVisionCompatTrackOutput(private val delegate: TrackOutput) : TrackOutput {
        override fun format(format: Format) {
            delegate.format(format.withProfile7AsHevc())
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            delegate.sampleData(data, length, sampleDataPart)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }

        override fun durationUs(durationUs: Long) {
            delegate.durationUs(durationUs)
        }

        private fun Format.withProfile7AsHevc(): Format {
            if (sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return this
            val profile = CodecSpecificDataUtil.getCodecProfileAndLevel(this)?.first
            if (profile != CodecProfileLevel.DolbyVisionProfileDvheDtb) return this

            // The codec string describes the Dolby Vision stream, the HEVC decoder reads the base layer's profile from the SPS
            return buildUpon()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setCodecs(null)
                .build()
        }
    }
}
