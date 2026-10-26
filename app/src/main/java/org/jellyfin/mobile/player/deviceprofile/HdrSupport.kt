package org.jellyfin.mobile.player.deviceprofile

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import android.view.Display.HdrCapabilities
import androidx.core.content.getSystemService

/**
 * Describes which HDR formats can be shown, based on the HDR types supported by the built-in display
 * and the profiles advertised by the video decoders.
 */
class HdrSupport(context: Context) {
    private val displayHdrTypes: Set<Int> = getDisplayHdrTypes(context)
    private val tenBitCodecs = HashSet<String>()
    private var hasDolbyVisionDecoder = false
    private var hasDolbyVisionProfile7Decoder = false

    init {
        val androidCodecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (codecInfo in androidCodecs.codecInfos) {
            if (codecInfo.isEncoder) continue

            for (mimeType in codecInfo.supportedTypes) {
                val profiles = codecInfo.getCapabilitiesForType(mimeType).profileLevels.map { it.profile }
                when (mimeType) {
                    MediaFormat.MIMETYPE_VIDEO_HEVC -> if (profiles.any(HEVC_10_BIT_PROFILES::contains)) tenBitCodecs += "hevc"
                    MediaFormat.MIMETYPE_VIDEO_AV1 -> if (profiles.any(AV1_10_BIT_PROFILES::contains)) tenBitCodecs += "av1"
                    MediaFormat.MIMETYPE_VIDEO_VP9 -> if (profiles.any(VP9_10_BIT_PROFILES::contains)) tenBitCodecs += "vp9"
                    MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION -> {
                        hasDolbyVisionDecoder = true
                        if (CodecProfileLevel.DolbyVisionProfileDvheDtb in profiles) hasDolbyVisionProfile7Decoder = true
                    }
                }
            }
        }
    }

    private val displaySupportsHdr10: Boolean
        get() = HdrCapabilities.HDR_TYPE_HDR10 in displayHdrTypes || HdrCapabilities.HDR_TYPE_HDR10_PLUS in displayHdrTypes

    private val displaySupportsHlg: Boolean
        get() = HdrCapabilities.HDR_TYPE_HLG in displayHdrTypes

    private val displaySupportsDolbyVision: Boolean
        get() = HdrCapabilities.HDR_TYPE_DOLBY_VISION in displayHdrTypes

    /**
     * Whether Dolby Vision profile 7 video should be decoded as its HEVC base layer (HDR10).
     *
     * Media3 only falls back to the base layer for profiles 4 and 8. Without this, profile 7 files
     * (UHD Blu-ray remuxes) play with sound but without picture on devices that can't decode
     * profile 7 natively, which includes all phones and tablets with Samsung displays.
     */
    val decodeDolbyVisionProfile7AsHevc: Boolean
        get() = !(displaySupportsDolbyVision && hasDolbyVisionProfile7Decoder)

    /**
     * Returns the Jellyfin video range types that can be direct played for the given [codec],
     * or null if the codec doesn't carry HDR video and therefore doesn't need a restriction.
     */
    fun getVideoRangeTypes(codec: String): List<String>? {
        if (codec !in HDR_CAPABLE_CODECS) return null

        val hdr10 = displaySupportsHdr10 && codec in tenBitCodecs
        val hlg = displaySupportsHlg && codec in tenBitCodecs
        return buildList {
            add(RANGE_SDR)
            if (hdr10) {
                add(RANGE_HDR10)
                add(RANGE_HDR10_PLUS)
            }
            if (hlg) add(RANGE_HLG)

            if (codec in DOLBY_VISION_BASE_LAYER_CODECS) {
                // Dolby Vision with a backwards compatible base layer plays as that base layer
                add(RANGE_DOVI_WITH_SDR)
                if (hdr10) {
                    add(RANGE_DOVI_WITH_HDR10)
                    add(RANGE_DOVI_WITH_HDR10_PLUS)
                    add(RANGE_DOVI_WITH_EL)
                    add(RANGE_DOVI_WITH_EL_HDR10_PLUS)
                }
                if (hlg) add(RANGE_DOVI_WITH_HLG)
                // Profile 5 has no base layer and needs real Dolby Vision support
                if (displaySupportsDolbyVision && hasDolbyVisionDecoder) add(RANGE_DOVI)
            }
        }
    }

    companion object {
        private val HDR_CAPABLE_CODECS = setOf("hevc", "av1", "vp9")
        private val DOLBY_VISION_BASE_LAYER_CODECS = setOf("hevc", "av1")

        private val HEVC_10_BIT_PROFILES = setOf(
            CodecProfileLevel.HEVCProfileMain10,
            CodecProfileLevel.HEVCProfileMain10HDR10,
            CodecProfileLevel.HEVCProfileMain10HDR10Plus,
        )
        private val AV1_10_BIT_PROFILES = setOf(
            CodecProfileLevel.AV1ProfileMain10,
            CodecProfileLevel.AV1ProfileMain10HDR10,
            CodecProfileLevel.AV1ProfileMain10HDR10Plus,
        )
        private val VP9_10_BIT_PROFILES = setOf(
            CodecProfileLevel.VP9Profile2,
            CodecProfileLevel.VP9Profile2HDR,
            CodecProfileLevel.VP9Profile2HDR10Plus,
        )

        // Names of the server's VideoRangeType values, some are newer than the SDK's enum
        private const val RANGE_SDR = "SDR"
        private const val RANGE_HDR10 = "HDR10"
        private const val RANGE_HDR10_PLUS = "HDR10Plus"
        private const val RANGE_HLG = "HLG"
        private const val RANGE_DOVI = "DOVI"
        private const val RANGE_DOVI_WITH_SDR = "DOVIWithSDR"
        private const val RANGE_DOVI_WITH_HDR10 = "DOVIWithHDR10"
        private const val RANGE_DOVI_WITH_HDR10_PLUS = "DOVIWithHDR10Plus"
        private const val RANGE_DOVI_WITH_HLG = "DOVIWithHLG"
        private const val RANGE_DOVI_WITH_EL = "DOVIWithEL"
        private const val RANGE_DOVI_WITH_EL_HDR10_PLUS = "DOVIWithELHDR10Plus"

        private fun getDisplayHdrTypes(context: Context): Set<Int> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptySet()
            val display = context.getSystemService<DisplayManager>()?.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptySet()
            val hdrTypes = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> display.mode.supportedHdrTypes
                else -> @Suppress("DEPRECATION") display.hdrCapabilities?.supportedHdrTypes
            }
            return hdrTypes?.toSet().orEmpty()
        }
    }
}
