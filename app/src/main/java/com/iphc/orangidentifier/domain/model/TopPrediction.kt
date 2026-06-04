package com.iphc.orangidentifier.domain.model

/**
 * One entry in the Top-3 classification result for a single detected face.
 */
data class TopPrediction(
    val rank: Int,
    val individualName: String,
    val confidence: Float,
    val classIndex: Int
)
