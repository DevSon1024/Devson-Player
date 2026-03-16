package com.devson.devsonplayer.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.devson.devsonplayer.ui.viewsettings.FolderGridItem
import com.devson.devsonplayer.ui.viewsettings.FolderListItem
import com.devson.devsonplayer.ui.viewsettings.LayoutStyle
import com.devson.devsonplayer.ui.viewsettings.VideoGridItem
import com.devson.devsonplayer.ui.viewsettings.VideoListItem
import com.devson.devsonplayer.ui.viewsettings.VideoItem
import com.devson.devsonplayer.ui.viewsettings.VideoListUiState
import com.devson.devsonplayer.ui.viewsettings.VideoListViewModel
import com.devson.devsonplayer.ui.viewsettings.ViewSettingsSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoListScreen(
    onVideoSelected: (List<VideoItem>, Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VideoListViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.viewSettings.collectAsStateWithLifecycle()
    val currentFolderPath by viewModel.currentFolderPath.collectAsStateWithLifecycle()
    
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }

    // Handle system back button for folder navigation
    BackHandler(enabled = currentFolderPath != null) {
        viewModel.navigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (currentFolderPath != null) currentFolderPath!!.substringAfterLast('/') else "Devson Player",
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                },
                navigationIcon = {
                    if (currentFolderPath != null) {
                        IconButton(onClick = { viewModel.navigateBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "View Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = uiState) {
                is VideoListUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is VideoListUiState.ExplorerMode -> {
                    if (state.folders.isEmpty() && state.videos.isEmpty()) {
                        EmptyState()
                    } else {
                        AnimatedContent(targetState = settings.layoutStyle, label = "layout_anim") { layout ->
                            when (layout) {
                                LayoutStyle.LIST -> {
                                    LazyColumn(
                                        contentPadding = PaddingValues(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(state.folders, key = { it.path }) { folder ->
                                            FolderListItem(folder, onClick = { viewModel.navigateToFolder(folder.path) })
                                        }
                                        itemsIndexed(
                                            items = state.videos,
                                            key = { _: Int, video: VideoItem -> video.id }
                                        ) { index: Int, video: VideoItem ->
                                            VideoListItem(video, settings, onClick = { onVideoSelected(state.videos, index) })
                                        }
                                    }
                                }
                                LayoutStyle.GRID -> {
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(settings.gridColumnCount),
                                        contentPadding = PaddingValues(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(state.folders, key = { it.path }) { folder ->
                                            FolderGridItem(folder, onClick = { viewModel.navigateToFolder(folder.path) })
                                        }
                                        itemsIndexed(
                                            items = state.videos,
                                            key = { _: Int, video: VideoItem -> video.id }
                                        ) { index: Int, video: VideoItem ->
                                            VideoGridItem(video, settings, onClick = { onVideoSelected(state.videos, index) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                is VideoListUiState.FlatMode -> {
                    if (state.videos.isEmpty()) {
                        EmptyState()
                    } else {
                        AnimatedContent(targetState = settings.layoutStyle, label = "layout_anim") { layout ->
                            when (layout) {
                                LayoutStyle.LIST -> {
                                    LazyColumn(
                                        contentPadding = PaddingValues(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        itemsIndexed(
                                            items = state.videos,
                                            key = { _: Int, video: VideoItem -> video.id }
                                        ) { index: Int, video: VideoItem ->
                                            VideoListItem(video, settings, onClick = { onVideoSelected(state.videos, index) })
                                        }
                                    }
                                }
                                LayoutStyle.GRID -> {
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(settings.gridColumnCount),
                                        contentPadding = PaddingValues(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        itemsIndexed(
                                            items = state.videos,
                                            key = { _: Int, video: VideoItem -> video.id }
                                        ) { index: Int, video: VideoItem ->
                                            VideoGridItem(video, settings, onClick = { onVideoSelected(state.videos, index) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showSettingsSheet) {
            ViewSettingsSheet(
                settings = settings,
                onViewModeChange = { viewModel.setViewMode(it) },
                onLayoutStyleChange = { viewModel.setLayoutStyle(it) },
                onGridColumnCountChange = { viewModel.setGridColumnCount(it) },
                onSortByChange = { viewModel.setSortBy(it) },
                onToggleShowDuration = { viewModel.toggleShowDuration(it) },
                onToggleShowFileSize = { viewModel.toggleShowFileSize(it) },
                onToggleShowResolution = { viewModel.toggleShowResolution(it) },
                onDismiss = { showSettingsSheet = false }
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                modifier = Modifier.size(80.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "No videos found",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontSize = 18.sp
            )
        }
    }
}
