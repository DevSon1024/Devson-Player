package com.devson.devsonplayer.utils

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log

/**
 * CodecDetector
 *
 * Queries the device's MediaCodecList to determine hardware decoder capability
 * for a given codec, profile, and bit depth.
 */
object CodecDetector {

    private const val TAG = "CodecDetector"

    enum class CodecType(val mimeType: String) {
        H264("video/avc"),
        HEVC("video/hevc"),
        VP9("video/x-vnd.on2.vp9"),
        AV1("video/av01"),
        MPEG4("video/mp4v-es"),
        H263("video/3gpp"),
        VP8("video/x-vnd.on2.vp8"),
    }

    data class CodecCapability(
        val codecType: CodecType,
        val hasHardwareDecoder: Boolean,
        val supports10Bit: Boolean,
        val maxWidth: Int,
        val maxHeight: Int,
        val hardwareDecoderName: String?
    )

    private val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)

    /** Full capability check for a codec. */
    fun detectCapability(type: CodecType): CodecCapability {
        val infos = codecList.codecInfos
        var hardwareName: String? = null
        var supports10bit = false
        var maxW = 0
        var maxH = 0

        for (info in infos) {
            if (info.isEncoder) continue
            if (!info.supportedTypes.contains(type.mimeType)) continue

            val caps = try {
                info.getCapabilitiesForType(type.mimeType)
            } catch (e: Exception) {
                Log.w(TAG, "getCapabilitiesForType failed for ${info.name}: ${e.message}")
                continue
            }

            val isHardware = isHardwareCodec(info)

            // Prefer hardware decoder
            if (isHardware && hardwareName == null) {
                hardwareName = info.name
            }

            // Check max resolution
            val vidCaps = caps.videoCapabilities
            if (vidCaps != null) {
                maxW = maxOf(maxW, vidCaps.supportedWidths.upper)
                maxH = maxOf(maxH, vidCaps.supportedHeights.upper)
            }

            // Check 10-bit support via profile
            if (type == CodecType.HEVC) {
                supports10bit = supports10bit || hasHevc10BitProfile(caps)
            } else if (type == CodecType.VP9) {
                supports10bit = supports10bit || hasVp910BitProfile(caps)
            } else if (type == CodecType.AV1) {
                supports10bit = supports10bit || hasAv110BitProfile(caps)
            }
        }

        val result = CodecCapability(
            codecType = type,
            hasHardwareDecoder = hardwareName != null,
            supports10Bit = supports10bit,
            maxWidth = maxW,
            maxHeight = maxH,
            hardwareDecoderName = hardwareName
        )

        Log.i(TAG, "[$type] hwDecoder=${result.hardwareDecoderName} " +
                "10bit=${result.supports10Bit} maxRes=${result.maxWidth}x${result.maxHeight}")

        return result
    }

    /**
     * Quick check: does hardware support this mime type at all?
     */
    fun hasHardwareDecoder(mimeType: String): Boolean {
        return codecList.codecInfos.any { info ->
            !info.isEncoder &&
            info.supportedTypes.contains(mimeType) &&
            isHardwareCodec(info)
        }
    }

    /**
     * Can the hardware decoder handle a specific MediaFormat?
     * (Used by DecoderSelector before committing to hardware path)
     */
    fun canHardwareDecode(format: MediaFormat): Boolean {
        val mimeType = format.getString(MediaFormat.KEY_MIME) ?: return false
        return try {
            codecList.findDecoderForFormat(format) != null &&
            hasHardwareDecoder(mimeType)
        } catch (e: Exception) {
            Log.w(TAG, "canHardwareDecode exception: ${e.message}")
            false
        }
    }

    // ─── Bit-depth profile checks ─────────────────────────────────────────────

    private fun hasHevc10BitProfile(caps: MediaCodecInfo.CodecCapabilities): Boolean {
        return caps.profileLevels.any { pl ->
            pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
            pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
             pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
        }
    }

    private fun hasVp910BitProfile(caps: MediaCodecInfo.CodecCapabilities): Boolean {
        return caps.profileLevels.any { pl ->
            pl.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2 ||
            pl.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile3 ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (
             pl.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10 ||
             pl.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR10))
        }
    }

    private fun hasAv110BitProfile(caps: MediaCodecInfo.CodecCapabilities): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return caps.profileLevels.any { pl ->
            pl.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 ||
            pl.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 ||
            pl.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
        }
    }

    // ─── Hardware codec detection ─────────────────────────────────────────────

    private fun isHardwareCodec(info: MediaCodecInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            // Heuristic for older APIs
            val name = info.name.lowercase()
            !name.startsWith("omx.google") &&
            !name.startsWith("c2.android") &&
            !name.contains("software")
        }
    }
}
