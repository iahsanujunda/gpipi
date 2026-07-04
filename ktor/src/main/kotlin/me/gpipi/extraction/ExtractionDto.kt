package me.gpipi.extraction

import kotlinx.serialization.Serializable

@Serializable
data class Extraction(
    val amount: Long,
    val currency: String,
    val merchant: String? = null,
    val category: String,
    val confidence: Double,
    val note: String? = null,
)