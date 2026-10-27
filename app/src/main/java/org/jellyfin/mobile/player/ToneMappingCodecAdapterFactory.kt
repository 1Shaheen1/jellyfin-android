package org.jellyfin.mobile.player

import android.media.MediaFormat
import android.os.Build
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import timber.log.Timber

/**
 * Asks video decoders to tone-map HDR video to SDR.
 *
 * HDR films are mastered for dark rooms, so on a phone they look a lot darker than SDR video at the same
 * screen brightness. Tone-mapped video uses the full SDR brightness instead. Decoders that can't tone-map
 * ignore the request and keep outputting HDR.
 */
class ToneMappingCodecAdapterFactory(
    private val delegate: MediaCodecAdapter.Factory,
) : MediaCodecAdapter.Factory {
    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MimeTypes.isVideo(configuration.format.sampleMimeType)) {
            configuration.mediaFormat.setInteger(
                MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            )
            Timber.i("Requesting HDR to SDR tone mapping from %s", configuration.codecInfo.name)
        }
        return delegate.createAdapter(configuration)
    }
}
