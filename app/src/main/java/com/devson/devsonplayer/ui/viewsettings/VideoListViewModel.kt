package com.devson.devsonplayer.ui.viewsettings

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class VideoItem(
    val id: Long,
    val title: String,
    val uri: Uri,
    val durationMs: Long,
    val sizeMb: Float,
    val dateAdded: Long,
    val folderName: String,
    val folderPath: String
)

data class FolderItem(
    val name: String,
    val path: String,
    val videoCount: Int,
    val totalSizeMb: Float
)

sealed class VideoListUiState {
    object Loading : VideoListUiState()
    
    data class ExplorerMode(
        val currentPath: String?,
        val folders: List<FolderItem>,
        val videos: List<VideoItem>
    ) : VideoListUiState()
    
    data class FlatMode(
        val videos: List<VideoItem>
    ) : VideoListUiState()
}

class VideoListViewModel(application: Application) : AndroidViewModel(application) {

    private val _viewSettings = MutableStateFlow(ViewSettings())
    val viewSettings: StateFlow<ViewSettings> = _viewSettings.asStateFlow()

    private val _allVideos = MutableStateFlow<List<VideoItem>>(emptyList())
    
    // Tracks the currently opened folder for EXPLORER mode (null = root showing all folders)
    private val _currentFolderPath = MutableStateFlow<String?>(null)
    val currentFolderPath: StateFlow<String?> = _currentFolderPath.asStateFlow()

    val uiState: StateFlow<VideoListUiState> = combine(
        _allVideos, _viewSettings, _currentFolderPath
    ) { allVideos, settings, currentPath ->
        if (allVideos.isEmpty()) return@combine VideoListUiState.Loading

        val sortedVideos = sortVideos(allVideos, settings.sortBy)

        when (settings.viewMode) {
            ViewMode.FLAT -> {
                VideoListUiState.FlatMode(videos = sortedVideos)
            }
            ViewMode.EXPLORER -> {
                if (currentPath == null) {
                    // Show grouping folders
                    val groups = sortedVideos.groupBy { it.folderPath }
                    val folders = groups.map { (path, vids) ->
                        FolderItem(
                            name = vids.firstOrNull()?.folderName ?: "Unknown",
                            path = path,
                            videoCount = vids.size,
                            totalSizeMb = vids.sumOf { it.sizeMb.toDouble() }.toFloat()
                        )
                    }.sortedBy { it.name.lowercase() }
                    
                    VideoListUiState.ExplorerMode(
                        currentPath = null,
                        folders = folders,
                        videos = emptyList()
                    )
                } else {
                    // Show videos within a specific folder
                    val folderVideos = sortedVideos.filter { it.folderPath == currentPath }
                    VideoListUiState.ExplorerMode(
                        currentPath = currentPath,
                        folders = emptyList(),
                        videos = folderVideos
                    )
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, VideoListUiState.Loading)

    init {
        loadVideos()
    }

    fun reloadVideos() {
        loadVideos()
    }

    private fun loadVideos() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val result = mutableListOf<VideoItem>()
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.DATE_ADDED
            )

            context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Unknown"
                    val duration = cursor.getLong(durCol)
                    val size = cursor.getLong(sizeCol)
                    val dateAdded = cursor.getLong(dateCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val path = cursor.getString(dataCol)
                    
                    val file = File(path)
                    val folderName = file.parentFile?.name ?: "Internal Storage"
                    val folderPath = file.parentFile?.absolutePath ?: "/"

                    result.add(
                        VideoItem(
                            id = id,
                            title = name,
                            uri = uri,
                            durationMs = duration,
                            sizeMb = size / 1_048_576f,
                            dateAdded = dateAdded,
                            folderName = folderName,
                            folderPath = folderPath
                        )
                    )
                }
            }
            _allVideos.value = result
        }
    }

    private fun sortVideos(videos: List<VideoItem>, sortBy: SortBy): List<VideoItem> {
        return when (sortBy) {
            SortBy.NAME_AZ -> videos.sortedBy { it.title.lowercase() }
            SortBy.NAME_ZA -> videos.sortedByDescending { it.title.lowercase() }
            SortBy.DATE_NEWEST -> videos.sortedByDescending { it.dateAdded }
            SortBy.DATE_OLDEST -> videos.sortedBy { it.dateAdded }
            SortBy.SIZE_LARGEST -> videos.sortedByDescending { it.sizeMb }
            SortBy.SIZE_SMALLEST -> videos.sortedBy { it.sizeMb }
            SortBy.DURATION -> videos.sortedByDescending { it.durationMs }
        }
    }

    // --- Actions ---

    fun setViewMode(mode: ViewMode) {
        _viewSettings.update { it.copy(viewMode = mode) }
        _currentFolderPath.value = null // reset navigation when mode switches
    }

    fun setLayoutStyle(style: LayoutStyle) {
        _viewSettings.update { it.copy(layoutStyle = style) }
        // If grid style is selected but columns count is low, implicitly jump it out of safety
        if (style == LayoutStyle.GRID && _viewSettings.value.gridColumnCount < 2) {
            _viewSettings.update { it.copy(gridColumnCount = 2) }
        }
    }

    fun setGridColumnCount(count: Int) {
        _viewSettings.update { it.copy(gridColumnCount = count.coerceIn(2, 5)) }
    }

    fun setSortBy(sort: SortBy) {
        _viewSettings.update { it.copy(sortBy = sort) }
    }

    fun toggleShowDuration(show: Boolean) {
        _viewSettings.update { it.copy(showDuration = show) }
    }

    fun toggleShowFileSize(show: Boolean) {
        _viewSettings.update { it.copy(showFileSize = show) }
    }

    fun toggleShowResolution(show: Boolean) {
        _viewSettings.update { it.copy(showResolution = show) }
    }

    fun navigateToFolder(path: String?) {
        _currentFolderPath.value = path
    }

    fun navigateBack(): Boolean {
        if (_viewSettings.value.viewMode == ViewMode.EXPLORER && _currentFolderPath.value != null) {
            _currentFolderPath.value = null
            return true
        }
        return false
    }

    private val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    fun saveScrollPosition(path: String?, index: Int, offset: Int) {
        val key = path ?: "ROOT"
        scrollPositions[key] = Pair(index, offset)
    }

    fun getScrollPosition(path: String?): Pair<Int, Int>? {
        val key = path ?: "ROOT"
        return scrollPositions[key]
    }
}
