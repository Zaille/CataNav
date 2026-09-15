package com.catanav.domain

import com.catanav.pdr.HeadingConfidence

/**
 * What the navigation UI consumes: the engine's metric position enriched with the
 * uncertainty model and heading confidence. Built by TripSession; never implies
 * GPS-like accuracy.
 */
data class PositionEstimate(
    val xMeters: Double,
    val yMeters: Double,
    val positionUncertaintyMeters: Double,
    val headingDegrees: Double,
    val headingUncertaintyDegrees: Double,
    val confidence: HeadingConfidence,
)
