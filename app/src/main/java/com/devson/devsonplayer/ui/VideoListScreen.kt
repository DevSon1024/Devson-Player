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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background

import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.collectAsState
import com.devson.devsonplayer.MainViewModel
import com.devson.devsonplayer.ThemeMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.unit.dp
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
    viewModel: VideoListViewModel = viewModel(),
    mainViewModel: MainViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.viewSettings.collectAsStateWithLifecycle()
    val currentFolderPath by viewModel.currentFolderPath.collectAsStateWithLifecycle()
    val themeMode by mainViewModel.themeMode.collectAsState()
    
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var showThemeMenu by remember { mutableStateOf(false) }

    // Scroll states for Explorer Mode
    val explorerRootListState = rememberLazyListState()
    val explorerRootGridState = rememberLazyGridState()
    val explorerFolderListState = rememberLazyListState()
    val explorerFolderGridState = rememberLazyGridState()

    // Scroll states for Flat Mode
    val flatListState = rememberLazyListState()
    val flatGridState = rememberLazyGridState()

    // Reset folder scroll position when entering a new folder
    LaunchedEffect(currentFolderPath) {
        if (currentFolderPath != null) {
            explorerFolderListState.scrollToItem(0)
            explorerFolderGridState.scrollToItem(0)
        }
    }

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
                    Box {
                        IconButton(onClick = { showThemeMenu = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Theme Settings")
                        }
                        DropdownMenu(
                            expanded = showThemeMenu,
                            onDismissRequest = { showThemeMenu = false }
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }.replace("_", " ")) },
                                    onClick = {
                                        mainViewModel.setThemeMode(mode)
                                        showThemeMenu = false
                                    },
                                    trailingIcon = {
                                        if (themeMode == mode) {
                                            Icon(Icons.Default.PlayCircle, contentDescription = "Selected", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                )
                            }
                        }
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
                                    if (currentFolderPath == null) {
                                        LazyColumn(
                                            state = explorerRootListState,
                                            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            items(state.folders, key = { it.path }) { folder ->
                                                FolderListItem(folder, onClick = { viewModel.navigateToFolder(folder.path) })
                                            }
                                        }
                                    } else {
                                        LazyColumn(
                                            state = explorerFolderListState,
                                            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
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
                                }
                                LayoutStyle.GRID -> {
                                    if (currentFolderPath == null) {
                                        LazyVerticalGrid(
                                            state = explorerRootGridState,
                                            columns = GridCells.Fixed(settings.gridColumnCount),
                                            contentPadding = PaddingValues(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            items(state.folders, key = { it.path }) { folder ->
                                                FolderGridItem(folder, onClick = { viewModel.navigateToFolder(folder.path) })
                                            }
                                        }
                                    } else {
                                        LazyVerticalGrid(
                                            state = explorerFolderGridState,
                                            columns = GridCells.Fixed(settings.gridColumnCount),
                                            contentPadding = PaddingValues(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(16.dp),
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
                is VideoListUiState.FlatMode -> {
                    if (state.videos.isEmpty()) {
                        EmptyState()
                    } else {
                        AnimatedContent(targetState = settings.layoutStyle, label = "layout_anim") { layout ->
                            when (layout) {
                                LayoutStyle.LIST -> {
                                    LazyColumn(
                                        state = flatListState,
                                        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
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
                                        state = flatGridState,
                                        columns = GridCells.Fixed(settings.gridColumnCount),
                                        contentPadding = PaddingValues(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
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
