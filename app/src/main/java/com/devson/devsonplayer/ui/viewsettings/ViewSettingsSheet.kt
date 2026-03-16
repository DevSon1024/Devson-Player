package com.devson.devsonplayer.ui.viewsettings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewSettingsSheet(
    settings: ViewSettings,
    onViewModeChange: (ViewMode) -> Unit,
    onLayoutStyleChange: (LayoutStyle) -> Unit,
    onGridColumnCountChange: (Int) -> Unit,
    onSortByChange: (SortBy) -> Unit,
    onToggleShowDuration: (Boolean) -> Unit,
    onToggleShowFileSize: (Boolean) -> Unit,
    onToggleShowResolution: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Display Options",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Divider()
            Spacer(Modifier.height(16.dp))

            // 1. View Mode (Explorer vs Flat)
            SectionLabel("View Mode")
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                SegmentedButton(
                    selected = settings.viewMode == ViewMode.EXPLORER,
                    onClick = { onViewModeChange(ViewMode.EXPLORER) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("Explorer (Folders)")
                }
                SegmentedButton(
                    selected = settings.viewMode == ViewMode.FLAT,
                    onClick = { onViewModeChange(ViewMode.FLAT) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text("Flat List")
                }
            }

            // 2. Layout Style (List vs Grid)
            SectionLabel("Layout Style")
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                SegmentedButton(
                    selected = settings.layoutStyle == LayoutStyle.LIST,
                    onClick = { onLayoutStyleChange(LayoutStyle.LIST) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("List View")
                }
                SegmentedButton(
                    selected = settings.layoutStyle == LayoutStyle.GRID,
                    onClick = { onLayoutStyleChange(LayoutStyle.GRID) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text("Grid View")
                }
            }

            // Grid Column Count Slider (only if GRID)
            if (settings.layoutStyle == LayoutStyle.GRID) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Columns: ${settings.gridColumnCount}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(16.dp))
                    Slider(
                        value = settings.gridColumnCount.toFloat(),
                        onValueChange = { onGridColumnCountChange(it.toInt()) },
                        valueRange = 2f..5f,
                        steps = 2,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(16.dp))

            // 3. Sort By
            SectionLabel("Sort By")
            var expanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(settings.sortBy.label)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    SortBy.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                onSortByChange(option)
                                expanded = false
                            }
                        )
                    }
                }
            }

            Divider()
            Spacer(Modifier.height(16.dp))

            // 4. Meta Toggles
            SectionLabel("Visible Metadata")
            SettingsSwitchRow("Show Duration", settings.showDuration) { onToggleShowDuration(it) }
            SettingsSwitchRow("Show File Size", settings.showFileSize) { onToggleShowFileSize(it) }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, 
            style = MaterialTheme.typography.bodyLarge, 
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
