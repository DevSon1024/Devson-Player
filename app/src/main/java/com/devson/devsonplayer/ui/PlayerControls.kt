package com.devson.devsonplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PlayerControls.kt
 *
 * Retained as a utility module. The main controls overlay has been replaced
 * by PlayerControlsV2. These helpers remain available for opt-in reuse.
 */

@Composable
fun SubtitleOverlay(text: String, modifier: Modifier = Modifier) {
    Text(
        text      = text,
        color     = Color.White,
        fontSize  = 18.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier  = modifier
            .fillMaxWidth()
            .background(Color(0x88000000))
            .padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
fun DecoderBadge(label: String) {
    val color = if (label == "HARDWARE") Color(0xFF4CAF50) else Color(0xFFFF9800)
    Text(
        text      = label,
        color     = Color.White,
        fontSize  = 11.sp,
        modifier  = Modifier
            .background(color, shape = MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

