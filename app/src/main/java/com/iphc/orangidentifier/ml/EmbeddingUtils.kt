package com.iphc.orangidentifier.ml

import kotlin.math.sqrt

/**
 * Math helpers shared across the ML and data layers.
 * All operations assume L2-normalised input vectors (unit vectors), so dot product
 * equals cosine similarity directly.
 */
object EmbeddingUtils {

    /**
     * Returns a L2-normalised copy of [v].
     * If the norm is effectively zero (all-zero vector), returns [v] unchanged.
     */
    fun l2Normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        return if (norm < 1e-8f) v else FloatArray(v.size) { v[it] / norm }
    }

    /**
     * Averages a list of embeddings and L2-normalises the result.
     * Returns a prototype (centroid) that can be used directly for cosine similarity.
     */
    fun averageEmbeddings(embeddings: List<FloatArray>): FloatArray {
        require(embeddings.isNotEmpty()) { "Cannot average an empty list" }
        val dim   = embeddings[0].size
        val proto = FloatArray(dim)
        for (emb in embeddings) for (i in 0 until dim) proto[i] += emb[i]
        for (i in 0 until dim) proto[i] /= embeddings.size.toFloat()
        return l2Normalize(proto)
    }

    /**
     * Dot product of two float vectors (= cosine similarity when both are L2-normalised).
     * Safe for vectors of different lengths — uses the shorter one.
     */
    fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) sum += a[i] * b[i]
        return sum
    }
}
