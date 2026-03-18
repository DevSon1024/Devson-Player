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

    sealed class PlayerState {
        object Idle : PlayerState()
        object Buffering : PlayerState()
        data class Playing(val positionMs: Long, val durationMs: Long) : PlayerState()
        data class Paused(val positionMs: Long, val durationMs: Long) : PlayerState()
        object Ended : PlayerState()
        data class Error(val message: String) : PlayerState()
    }

    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _decoderType = MutableStateFlow<DecoderType?>(null)
    val decoderType: StateFlow<DecoderType?> = _decoderType.asStateFlow()

    private val _videoInfo = MutableStateFlow<DecoderSelector.VideoInfo?>(null)
    val videoInfo: StateFlow<DecoderSelector.VideoInfo?> = _videoInfo.asStateFlow()

    //  Hardware player (ExoPlayer) 

    private var exoPlayer: ExoPlayer? = null
    private var hardwareSurface: Surface? = null

    // Surface binding 
    private var currentRealPath: String? = null

    //  Software player (FFmpeg / NativePlayer) 

    private val nativePlayer = NativePlayer()
    private var softwareSurface: Surface? = null
    private var currentDurationMs = 0L
    private var currentPositionMs = 0L
    private var isPlayingNative = false
    private var probedDurationMs = 0L
    private var positionJob: kotlinx.coroutines.Job? = null

    //  Current state 

    private var activeDecoder: DecoderType = DecoderType.HARDWARE

    /** Called when ExoPlayer resolves available tracks. Set by PlayerViewModel. */
    var onTracksChanged: ((androidx.media3.common.Tracks) -> Unit)? = null

    //  Surface binding 

    /** Bind the SurfaceView surface (used for hardware decode). */
    fun setHardwareSurface(surface: Surface?) {
        hardwareSurface = surface
        exoPlayer?.setVideoSurface(surface)
    }

    /** Bind the GLSurfaceView surface (used for software decode). */
    fun setSoftwareSurface(surface: Surface?) {
        softwareSurface = surface
    }

    //  Load & play 
    fun load(uri: Uri) {
        _state.value = PlayerState.Buffering
        
        // Launch in the background to prevent blocking the Main Thread
        scope.launch(Dispatchers.IO) {
            val realPath = getRealPathFromUri(context, uri) ?: uri.path ?: run {
                _state.value = PlayerState.Error("Invalid URI: $uri")
                return@launch
            }

            // Probe for video info via MediaMetadataRetriever (Heavy File I/O)
            val videoInfo = probeVideoInfo(realPath, uri) 
            _videoInfo.value = videoInfo
            Log.i(TAG, "VideoInfo: $videoInfo")

            val decoder = DecoderSelector.selectDecoder(videoInfo)
            activeDecoder = decoder
            _decoderType.value = decoder
            Log.i(TAG, "Selected decoder: $decoder")

            // Switch back to the Main thread to configure ExoPlayer / NativePlayer UI
            withContext(Dispatchers.Main) {
                when (decoder) {
                    DecoderType.HARDWARE -> loadWithExoPlayer(uri, realPath) 
                    DecoderType.SOFTWARE -> loadWithFFmpeg(realPath)
                }
            }
        }
    }

    private fun probeVideoInfo(path: String, uri: Uri): DecoderSelector.VideoInfo {
        var mmr: MediaMetadataRetriever? = null
        return try {
            mmr = MediaMetadataRetriever()
            mmr.setDataSource(context, uri)
            val mime  = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/avc"
            val w     = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
            val h     = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            val durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            probedDurationMs = durationStr?.toLongOrNull() ?: 0L

            // Detect 10-bit from filename/extension heuristic when probe is insufficient
            val is10bit = path.lowercase().let {
                it.contains("10bit") || it.contains("10-bit") || it.contains("hdr") || it.contains("uhd")
            }

            DecoderSelector.VideoInfo(
                mimeType = mimeFromCodecName(mime),
                width = w,
                height = h,
                bitDepth = if (is10bit) 10 else 8
            )
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadataRetriever failed, using defaults: ${e.message}")
            DecoderSelector.VideoInfo("video/avc", 1920, 1080)
        } finally {
            try {
                mmr?.release()
            } catch (ignored: Exception) {}
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
                    override fun onPlaybackStateChanged(state: Int) {
                        updateStateFromExo(player)
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updateStateFromExo(player)
                    }
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        onTracksChanged?.invoke(tracks)
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer error: ${error.message}, falling back to SOFTWARE")
                        
                        _state.value = PlayerState.Buffering
                        
                        _decoderType.value = DecoderType.SOFTWARE
                        activeDecoder = DecoderType.SOFTWARE
                        
                        releaseExoPlayer()
                        
                        // Use the real absolute path for the FFmpeg fallback
                        loadWithFFmpeg(realPath)
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
            player.isPlaying  -> PlayerState.Playing(pos, dur)
            else              -> PlayerState.Paused(pos, dur)
        }
    }

    //  Software path 

    private fun loadWithFFmpeg(path: String) {
        activeDecoder = DecoderType.SOFTWARE
        _decoderType.value = DecoderType.SOFTWARE
        
        scope.launch {
            _state.value = PlayerState.Buffering
            
            val surface = softwareSurface ?: run {
                Log.e(TAG, "No software surface available")
                _state.value = PlayerState.Error("Surface not ready")
                return@launch
            }

            // Perform heavy native init on IO thread
            val ok = withContext(Dispatchers.IO) {
                releaseNative()
                nativePlayer.init(path, surface, 1920, 1080)
            }

            if (!ok) {
                _state.value = PlayerState.Error("Failed to init native player for $path")
                return@launch
            }

            // Update UI/State on Main thread
            currentDurationMs = withContext(Dispatchers.IO) { nativePlayer.getDurationUs() / 1000L }
            if (currentDurationMs <= 5000L) {
                currentDurationMs = probedDurationMs
            }
            isPlayingNative = true
            
            withContext(Dispatchers.IO) {
                nativePlayer.startDecoding()
            }
            
            currentPositionMs = 0L
            _state.value = PlayerState.Playing(currentPositionMs, currentDurationMs)

            // Position simulation
            startPositionSimulation()
        }
    }

    private fun startPositionSimulation() {
        positionJob?.cancel()
        positionJob = scope.launch {
            val start = System.currentTimeMillis() - currentPositionMs
            while (isPlayingNative) {
                val elapsedMs = System.currentTimeMillis() - start
                currentPositionMs = if (currentDurationMs > 0L) elapsedMs.coerceAtMost(currentDurationMs) else elapsedMs
                _state.value = PlayerState.Playing(currentPositionMs, currentDurationMs)
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
                startPositionSimulation()
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
                    startPositionSimulation()
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
                        // Hardware supports it! Standard ExoPlayer track switch.
                        val freshGroup = player.currentTracks.groups.find {
                            it.mediaTrackGroup == trackGroup.mediaTrackGroup
                        }?.mediaTrackGroup ?: trackGroup.mediaTrackGroup

                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, false)
                            .setOverrideForType(TrackSelectionOverride(freshGroup, trackIndex))
                            .build()
                    } else {
                        // HARDWARE LIMITATION REACHED -> SEAMLESS FALLBACK TO C++
                        Log.w(TAG, "Hardware unsupported! Seamless fallback to C++ Oboe/FFmpeg player.")
                        val currentPos = player.currentPosition

                        activeDecoder = DecoderType.SOFTWARE
                        _decoderType.value = DecoderType.SOFTWARE
                        releaseExoPlayer()

                        currentRealPath?.let { path ->
                            loadWithFFmpeg(path)       // Open C++ Player
                            seekTo(currentPos)         // Jump to current timestamp
                            nativePlayer.setAudioStream(relativeUiIndex) // Apply the audio track
                        }
                    }
                }
            }
            DecoderType.SOFTWARE -> {
                // Already in software mode, just tell C++ to switch the track
                nativePlayer.setAudioStream(relativeUiIndex)
            }
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

    fun getDurationMs(): Long {
        return when (activeDecoder) {
            DecoderType.HARDWARE -> exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L
            DecoderType.SOFTWARE -> currentDurationMs
        }
    }

    fun getCurrentPositionMs(): Long {
        return when (activeDecoder) {
            DecoderType.HARDWARE -> exoPlayer?.currentPosition ?: 0L
            DecoderType.SOFTWARE -> currentPositionMs
        }
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
    }

    private fun releaseNative() {
        isPlayingNative = false
        positionJob?.cancel()
        nativePlayer.release()
    }

    //  Utility 

    private fun mimeFromCodecName(name: String): String {
        return when {
            // HEVC identifiers: standard mime, codec names, and MP4/MKV container tags
            name.contains("hevc", true) ||
            name.contains("h265", true) ||
            name.contains("hvc1", true) ||   // MP4 container HEVC codec tag
            name.contains("hev1", true) ||   // Alternative MP4 HEVC codec tag
            name == "video/hevc"             -> "video/hevc"

            name.contains("avc",  true) || name.contains("h264", true) -> "video/avc"
            name.contains("vp9",  true)                                 -> "video/x-vnd.on2.vp9"
            name.contains("av1",  true)                                 -> "video/av01"
            name.contains("vp8",  true)                                 -> "video/x-vnd.on2.vp8"
            else                                                         -> "video/avc"
        }
    }
    
    private fun getRealPathFromUri(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            val projection = arrayOf(android.provider.MediaStore.Video.Media.DATA)
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    val columnIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DATA)
                    if (cursor.moveToFirst()) {
                        return cursor.getString(columnIndex)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get real path", e)
            }
        } else if (uri.scheme == "file") {
            return uri.path
        }
        return uri.path // Fallback
    }
}