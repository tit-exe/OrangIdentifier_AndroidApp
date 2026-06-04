package com.iphc.orangidentifier.ml.classifier

import com.iphc.orangidentifier.domain.model.TopPrediction

data class ClassificationResult(
    val topPredictions: List<TopPrediction>,
    val isUnknown: Boolean,
    val rawLogits: FloatArray  // kept for debugging; not shown in UI
)
