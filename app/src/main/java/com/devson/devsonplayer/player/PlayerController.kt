package com.devson.devsonplayer.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.devson.devsonplayer.player.DecoderSelector.DecoderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class PlayerController(private val context: Context) {

    private val TAG = "PlayerController"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    //  State 

    sealed class PlayerState {
        object Idle      : PlayerState()
        object Buffering : PlayerState()
        data class Playing(val positionMs: Long, val durationMs: Long) : PlayerState()
        data class Paused (val positionMs: Long, val durationMs: Long) : PlayerState()
        object Ended     : PlayerState()
        data class Error (val message: String) : PlayerState()
    }

    private val _state       = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _decoderType = MutableStateFlow<DecoderType?>(null)
    val decoderType: StateFlow<DecoderType?> = _decoderType.asStateFlow()

    private val _videoInfo   = MutableStateFlow<DecoderSelector.VideoInfo?>(null)
    val videoInfo: StateFlow<DecoderSelector.VideoInfo?> = _videoInfo.asStateFlow()

    //  Hardware player (ExoPlayer) 

    private var exoPlayer:        ExoPlayer? = null
    private var trackProbePlayer: ExoPlayer? = null
    private var hardwareSurface:  Surface?   = null

    //  Software player (FFmpeg / NativePlayer) 

    private val nativePlayer    = NativePlayer()
    private var softwareSurface: Surface? = null
    private var currentDurationMs = 0L
    private var currentPositionMs = 0L
    private var isPlayingNative   = false
    private var probedDurationMs  = 0L
    private var positionJob: Job? = null

    //  Misc 

    private var currentRealPath: String? = null
    private var currentUri:      Uri?    = null
    private var activeDecoder: DecoderType = DecoderType.HARDWARE

    /** Called when ExoPlayer resolves available tracks. Set by PlayerViewModel. */
    var onTracksChanged: ((Tracks) -> Unit)? = null

    //  Surface binding 

    fun setHardwareSurface(surface: Surface?) {
        hardwareSurface = surface
        exoPlayer?.setVideoSurface(surface)
    }

    fun setSoftwareSurface(surface: Surface?) {
        softwareSurface = surface
    }

    //  Activity lifecycle 

    /** Call from Activity/Fragment onPause. Pauses renderer to prevent EGL issues. */
    fun onActivityPause() {
        if (activeDecoder == DecoderType.SOFTWARE) {
            nativePlayer.pauseRenderer()
        }
    }

    /** Call from Activity/Fragment onResume. Resumes renderer. */
    fun onActivityResume() {
        if (activeDecoder == DecoderType.SOFTWARE) {
            nativePlayer.resumeRenderer()
        }
    }

    //  Load & play 

    fun load(uri: Uri) {
        _state.value = PlayerState.Buffering

        scope.launch(Dispatchers.IO) {
            val realPath = getRealPathFromUri(context, uri) ?: uri.path ?: run {
                _state.value = PlayerState.Error("Invalid URI: $uri")
                return@launch
            }

            currentRealPath = realPath
            currentUri      = uri

            val videoInfo = probeVideoInfo(realPath, uri)
            _videoInfo.value = videoInfo

            val decoder = DecoderSelector.selectDecoder(videoInfo)
            activeDecoder      = decoder
            _decoderType.value = decoder

            withContext(Dispatchers.Main) {
                when (decoder) {
                    DecoderType.HARDWARE -> loadWithExoPlayer(uri, realPath)
                    DecoderType.SOFTWARE -> loadWithFFmpeg(uri, realPath)
                }
            }
        }
    }

    //  Track probe via silent ExoPlayer 

    private fun probeTracksWithExo(uri: Uri) {
        trackProbePlayer?.release()

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true))
        }

        trackProbePlayer = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        onTracksChanged?.invoke(tracks)
                    }
                })
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
            }
    }

    //  Video probe 

    private fun probeVideoInfo(path: String, uri: Uri): DecoderSelector.VideoInfo {
        var mmr: MediaMetadataRetriever? = null
        return try {
            mmr = MediaMetadataRetriever()
            mmr.setDataSource(context, uri)

            var mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/avc"

            val lowerPath = path.lowercase()
            if (lowerPath.endsWith(".mkv") ||
                lowerPath.contains("hevc") ||
                lowerPath.contains("x265") ||
                lowerPath.contains("h265")) {
                mime = "video/hevc"
            }

            val w           = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()  ?: 1920
            val h           = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            val durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            probedDurationMs = durationStr?.toLongOrNull() ?: 0L

            val is10bit = lowerPath.let {
                it.contains("10bit") || it.contains("10-bit") ||
                it.contains("hdr")   || it.contains("uhd")
            }

            DecoderSelector.VideoInfo(
                mimeType = mimeFromCodecName(mime),
                width    = w,
                height   = h,
                bitDepth = if (is10bit) 10 else 8
            )
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadataRetriever failed: ${e.message}")
            DecoderSelector.VideoInfo("video/avc", 1920, 1080)
        } finally {
            try { mmr?.release() } catch (_: Exception) {}
        }
    }

    //  Hardware path 

    private fun loadWithExoPlayer(uri: Uri, realPath: String) {
        releaseExoPlayer()

        val trackSelector = DefaultTrackSelector(context)
        exoPlayer = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .build()
            .also { player ->
                hardwareSurface?.let { player.setVideoSurface(it) }

                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { updateStateFromExo(player) }
                    override fun onIsPlayingChanged(isPlaying: Boolean) { updateStateFromExo(player) }
                    override fun onTracksChanged(tracks: Tracks) { onTracksChanged?.invoke(tracks) }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer error: ${error.message} — falling back to SOFTWARE")
                        scope.launch {
                            _state.value       = PlayerState.Buffering
                            _decoderType.value = DecoderType.SOFTWARE
                            activeDecoder      = DecoderType.SOFTWARE
                            releaseExoPlayer()
                            loadWithFFmpeg(uri, realPath)
                        }
                    }
                })

                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
                player.play()
            }

        // Position polling coroutine
        scope.launch {
            while (exoPlayer != null) {
                updateStateFromExo(exoPlayer ?: break)
                delay(250)
            }
        }
    }

    private fun updateStateFromExo(player: ExoPlayer) {
        val pos = player.currentPosition
        val dur = player.duration.coerceAtLeast(0L)
        _state.value = when {
            player.playbackState == Player.STATE_BUFFERING -> PlayerState.Buffering
            player.playbackState == Player.STATE_ENDED     -> PlayerState.Ended
            player.isPlaying                               -> PlayerState.Playing(pos, dur)
            else                                           -> PlayerState.Paused(pos, dur)
        }
    }

    //  Software path 

    private fun loadWithFFmpeg(uri: Uri, path: String) {
        activeDecoder      = DecoderType.SOFTWARE
        _decoderType.value = DecoderType.SOFTWARE

        scope.launch {
            _state.value = PlayerState.Buffering

            probeTracksWithExo(uri)

            val surface = softwareSurface ?: run {
                Log.e(TAG, "No software surface available")
                _state.value = PlayerState.Error("Surface not ready")
                return@launch
            }

            // Heavy native init on IO thread
            val ok = withContext(Dispatchers.IO) {
                releaseNative()
                nativePlayer.init(path, surface, 1920, 1080)
            }

            if (!ok) {
                Log.e(TAG, "Native player init failed for $path")
                _state.value = PlayerState.Error("Failed to init native player")
                return@launch
            }

            // Fetch duration
            currentDurationMs = withContext(Dispatchers.IO) {
                nativePlayer.getDurationUs() / 1000L
            }
            if (currentDurationMs <= 5000L) {
                currentDurationMs = probedDurationMs
            }

            isPlayingNative   = true
            currentPositionMs = 0L

            withContext(Dispatchers.IO) {
                nativePlayer.startDecoding()
            }

            _state.value = PlayerState.Playing(currentPositionMs, currentDurationMs)

            // Poll real PTS from master clock instead of wall-clock simulation
            startPositionPolling()
        }
    }

    /**
     * Polls the native master clock every 250ms for the real playback position.
     * Replaces the wall-clock approximation used previously.
     */
    private fun startPositionPolling() {
        positionJob?.cancel()
        positionJob = scope.launch(Dispatchers.IO) {
            while (isPlayingNative) {
                val rawUs = nativePlayer.getCurrentPositionUs()
                // Convert µs → ms; clamp to duration
                val posMs = if (rawUs > 0L) {
                    (rawUs / 1000L).coerceAtMost(currentDurationMs)
                } else {
                    // Clock not started yet (pre-first audio frame) — keep last value
                    currentPositionMs
                }
                currentPositionMs = posMs
                withContext(Dispatchers.Main) {
                    _state.value = PlayerState.Playing(currentPositionMs, currentDurationMs)
                }
                delay(250)
            }
        }
    }

    //  Unified control API 

    fun play() {
        when (activeDecoder) {
            DecoderType.HARDWARE -> exoPlayer?.play()
            DecoderType.SOFTWARE -> {
                nativePlayer.resume()
                isPlayingNative = true
                startPositionPolling()
            }
        }
    }

    fun pause() {
        when (activeDecoder) {
            DecoderType.HARDWARE -> exoPlayer?.pause()
            DecoderType.SOFTWARE -> {
                nativePlayer.pause()
                isPlayingNative = false
                positionJob?.cancel()
                _state.value = PlayerState.Paused(currentPositionMs, currentDurationMs)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        when (activeDecoder) {
            DecoderType.HARDWARE -> exoPlayer?.seekTo(positionMs)
            DecoderType.SOFTWARE -> {
                nativePlayer.seekTo(positionMs * 1000L)
                currentPositionMs = positionMs
                if (isPlayingNative) {
                    startPositionPolling()
                } else {
                    _state.value = PlayerState.Paused(currentPositionMs, currentDurationMs)
                }
            }
        }
    }

    fun setSpeed(speed: Float) {
        when (activeDecoder) {
            DecoderType.HARDWARE -> exoPlayer?.playbackParameters = PlaybackParameters(speed)
            DecoderType.SOFTWARE -> nativePlayer.setSpeed(speed)
        }
    }

    fun selectAudioTrack(trackGroup: Tracks.Group, trackIndex: Int, relativeUiIndex: Int) {
        when (activeDecoder) {
            DecoderType.HARDWARE -> {
                exoPlayer?.let { player ->
                    if (trackGroup.isTrackSupported(trackIndex)) {
                        val freshGroup = player.currentTracks.groups
                            .find { it.mediaTrackGroup == trackGroup.mediaTrackGroup }
                            ?.mediaTrackGroup ?: trackGroup.mediaTrackGroup

                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, false)
                            .setOverrideForType(TrackSelectionOverride(freshGroup, trackIndex))
                            .build()
                    } else {
                        Log.w(TAG, "Hardware unsupported — seamless fallback to FFmpeg")
                        val currentPos = player.currentPosition
                        activeDecoder      = DecoderType.SOFTWARE
                        _decoderType.value = DecoderType.SOFTWARE
                        releaseExoPlayer()

                        val safeUri  = currentUri
                        val safePath = currentRealPath
                        if (safeUri != null && safePath != null) {
                            loadWithFFmpeg(safeUri, safePath)
                            seekTo(currentPos)
                            nativePlayer.setAudioStream(relativeUiIndex)
                        }
                    }
                }
            }
            DecoderType.SOFTWARE -> nativePlayer.setAudioStream(relativeUiIndex)
        }
    }

    fun clearAudioTrackSelection() {
        exoPlayer?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                .build()
        }
    }

    fun getDurationMs(): Long = when (activeDecoder) {
        DecoderType.HARDWARE -> exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L
        DecoderType.SOFTWARE -> currentDurationMs
    }

    fun getCurrentPositionMs(): Long = when (activeDecoder) {
        DecoderType.HARDWARE -> exoPlayer?.currentPosition ?: 0L
        DecoderType.SOFTWARE -> currentPositionMs
    }

    //  Release 

    fun release() {
        releaseExoPlayer()
        releaseNative()
        _state.value = PlayerState.Idle
    }

    private fun releaseExoPlayer() {
        exoPlayer?.release()
        exoPlayer = null
        trackProbePlayer?.release()
        trackProbePlayer = null
    }

    private fun releaseNative() {
        isPlayingNative = false
        positionJob?.cancel()
        nativePlayer.release()
    }

    //  Utility 

    private fun mimeFromCodecName(name: String): String = when {
        name.contains("hevc",     true) ||
        name.contains("h265",     true) ||
        name.contains("x265",     true) ||
        name.contains("matroska", true) ||
        name.contains("hvc1",     true) ||
        name.contains("hev1",     true) ||
        name == "video/hevc"            -> "video/hevc"

        name.contains("avc",  true) || name.contains("h264", true) -> "video/avc"
        name.contains("vp9",  true)                                 -> "video/x-vnd.on2.vp9"
        name.contains("av1",  true)                                 -> "video/av01"
        name.contains("vp8",  true)                                 -> "video/x-vnd.on2.vp8"
        else                                                         -> "video/avc"
    }

    private fun getRealPathFromUri(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            val projection = arrayOf(android.provider.MediaStore.Video.Media.DATA)
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    val col = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DATA)
                    if (cursor.moveToFirst()) return cursor.getString(col)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get real path", e)
            }
        } else if (uri.scheme == "file") {
            return uri.path
        }
        return uri.path
    }
}