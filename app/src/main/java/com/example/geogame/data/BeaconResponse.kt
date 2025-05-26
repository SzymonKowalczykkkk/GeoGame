package com.example.geogame.data

data class BeaconResponse(
    val items: List<BeaconData>,
    val totalPages: Int,
    val itemsFrom: Int,
    val itemsTo: Int,
    val totalItemsCount: Int
)