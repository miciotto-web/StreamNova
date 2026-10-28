package com.example.ui.screens.player

data class TrackInfo(
    val index: Int,
    val name: String,
    val language: String?,
    val trackId: String? = null,
    val codec: String? = null,
    val channelCount: Int? = null,
    val isForced: Boolean = false,
    val isSelected: Boolean = false,
    val sampleRate: Int? = null
)
