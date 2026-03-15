package com.devson.devsonplayer.player

import android.media.MediaFormat
import android.util.Log
import com.devson.devsonplayer.utils.CodecDetector

/**
 * DecoderSelector
 *
 * Chooses between hardware (MediaCodec via Media3/ExoPlayer) and
 * software (FFmpeg via JNI) decoding paths based on:
 *  - Codec type (H264, HEVC, VP9, AV1)
 *  - Bit depth (8-bit vs 10-bit)
 *  - Device hardware capability
 */
object DecoderSelector {

    private const val TAG = "DecoderSelector"

    enum class DecoderType { HARDWARE, SOFTWARE }

    data class VideoInfo(
        val mimeType: String,
        val width: Int,
        val height: Int,
        val bitDepth: Int = 8,         // 8 or 10
        val profile: Int = -1,
        val level: Int = -1,
        val frameRate: Float = 30f
    )

    /** Cached capability map to avoid repeated MediaCodecList queries. */
    private val capabilityCache = mutableMapOf<String, CodecDetector.CodecCapability?>()

    fun selectDecoder(info: VideoInfo): DecoderType {
        Log.i(TAG, "selectDecoder: mime=${info.mimeType} ${info.width}x${info.height} ${info.bitDepth}bit")

        val codecType = mimeToCodecType(info.mimeType)

        // If codec is unknown or unsupported by MediaCodec, always software
        if (codecType == null) {
            Log.i(TAG, "Unknown MIME → SOFTWARE")
            return DecoderType.SOFTWARE
        }

        val cap = capabilityCache.getOrPut(info.mimeType) {
            CodecDetector.detectCapability(codecType)
        }

        if (cap == null || !cap.hasHardwareDecoder) {
            Log.i(TAG, "No HW decoder for $codecType → SOFTWARE")
            return DecoderType.SOFTWARE
        }

        // 10-bit: only go hardware if device explicitly supports it
        if (info.bitDepth >= 10 && !cap.supports10Bit) {
            Log.i(TAG, "HW decoder lacks 10-bit for $codecType → SOFTWARE")
            return DecoderType.SOFTWARE
        }

        // Resolution check: hardware decoder max size
        if (info.width > cap.maxWidth || info.height > cap.maxHeight) {
            Log.i(TAG, "Resolution ${info.width}x${info.height} exceeds HW max " +
                    "${cap.maxWidth}x${cap.maxHeight} → SOFTWARE")
            return DecoderType.SOFTWARE
        }

        // Validate format with MediaCodecList
        val format = buildMediaFormat(info)
        if (!CodecDetector.canHardwareDecode(format)) {
            Log.i(TAG, "canHardwareDecode=false for format → SOFTWARE")
            return DecoderType.SOFTWARE
        }

        Log.i(TAG, "Selected HARDWARE decoder: ${cap.hardwareDecoderName}")
        return DecoderType.HARDWARE
    }

    private fun buildMediaFormat(info: VideoInfo): MediaFormat {
        return MediaFormat.createVideoFormat(info.mimeType, info.width, info.height).apply {
            if (info.profile > 0) setInteger(MediaFormat.KEY_PROFILE, info.profile)
            if (info.level > 0)   setInteger(MediaFormat.KEY_LEVEL,   info.level)
            setInteger(MediaFormat.KEY_FRAME_RATE, info.frameRate.toInt())
        }
    }

    private fun mimeToCodecType(mime: String): CodecDetector.CodecType? {
        return when (mime.lowercase()) {
            "video/avc"             -> CodecDetector.CodecType.H264
            "video/hevc"            -> CodecDetector.CodecType.HEVC
            "video/x-vnd.on2.vp9"  -> CodecDetector.CodecType.VP9
            "video/av01"            -> CodecDetector.CodecType.AV1
            "video/mp4v-es"         -> CodecDetector.CodecType.MPEG4
            "video/x-vnd.on2.vp8"  -> CodecDetector.CodecType.VP8
            else                     -> null
        }
    }
}
