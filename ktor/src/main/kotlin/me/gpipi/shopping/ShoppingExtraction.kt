package me.gpipi.shopping

import kotlinx.serialization.Serializable

@Serializable
data class ShoppingExtraction(
    val items: List<ShoppingExtractedItem>,
)

@Serializable
data class ShoppingExtractedItem(
    val item: String,
    val quantity: String? = null,
    val note: String? = null,
)
