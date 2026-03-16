package com.devson.devsonplayer.ui

/**
 * ScalingMode
 *
 * Defines how the video should be scaled within the player view.
 */
enum class ScalingMode(val label: String) {
    FIT("Fit to Screen"),
    ORIGINAL("100%"),
    CROP("Cropped")
}
