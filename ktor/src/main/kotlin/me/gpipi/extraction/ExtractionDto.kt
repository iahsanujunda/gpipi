package me.gpipi.extraction

import kotlinx.serialization.Serializable

@Serializable
data class Extraction(
    val amount: Long,
    val currency: String = "JPY",   // single-valued enum in phase 1; models sometimes drop it, so default rather than reject
    val merchant: String? = null,
    val category: String,
    val confidence: Double,
    val note: String? = null,
)