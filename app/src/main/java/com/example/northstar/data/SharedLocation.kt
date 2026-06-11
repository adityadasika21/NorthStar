package com.example.northstar.data

data class SharedLocation(
    val name: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val url: String? = null,
    val needsExpansion: Boolean = false,
)
