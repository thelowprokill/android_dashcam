package com.jon_is_awesome.android_dashcam

data class TelemetryData(
    val speedKmh: Float = 0f,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis()
)
