package com.devson.devsonplayer.ui.viewsettings

enum class ViewMode { EXPLORER, FLAT }

enum class LayoutStyle { LIST, GRID }

enum class SortBy(val label: String) {
    NAME_AZ("Name (A-Z)"),
    NAME_ZA("Name (Z-A)"),
    DATE_NEWEST("Date Added (Newest)"),
    DATE_OLDEST("Date Added (Oldest)"),
    SIZE_LARGEST("Size (Largest)"),
    SIZE_SMALLEST("Size (Smallest)"),
    DURATION("Duration (Longest)")
}

data class ViewSettings(
    // Changed defaults for Task 1
    val viewMode: ViewMode = ViewMode.EXPLORER,
    val layoutStyle: LayoutStyle = LayoutStyle.LIST,
    val gridColumnCount: Int = 2,
    val sortBy: SortBy = SortBy.NAME_AZ,
    val showDuration: Boolean = true,
    val showFileSize: Boolean = true,
    val showResolution: Boolean = false
)