package com.iphc.orangidentifier.ml

import android.os.Build
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.MappedByteBuffer

/**
 * Creates a TFLite Interpreter with the best available hardware accelerator.
 *
 * Priority chain:
 *   1. NNAPI (NPU/DSP on capable devices — fastest)
 *   2. GPU Delegate
 *   3. CPU with 4 threads + XNNPACK (safe fallback for any device)
 */
object TfliteInterpreterFactory {

    private const val TAG = "TfliteInterpreterFactory"
    private const val CPU_THREAD_COUNT = 4

    fun build(modelBuffer: MappedByteBuffer): Interpreter {
        // Attempt 1: NNAPI delegate (NPU/DSP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val options = Interpreter.Options().apply {
                    addDelegate(NnApiDelegate())
                    setUseXNNPACK(true)
                }
                val interpreter = Interpreter(modelBuffer, options)
                Log.i(TAG, "Using NNAPI delegate")
                return interpreter
            }.onFailure { Log.w(TAG, "NNAPI not available: ${it.message}") }
        }

        // Attempt 2: GPU delegate
        runCatching {
            val options = Interpreter.Options().apply {
                addDelegate(GpuDelegate())
                setUseXNNPACK(true)
            }
            val interpreter = Interpreter(modelBuffer, options)
            Log.i(TAG, "Using GPU delegate")
            return interpreter
        }.onFailure { Log.w(TAG, "GPU delegate not available: ${it.message}") }

        // Fallback: CPU with XNNPACK (works on any Android device)
        val options = Interpreter.Options().apply {
            numThreads = CPU_THREAD_COUNT
            setUseXNNPACK(true)
        }
        Log.i(TAG, "Using CPU with $CPU_THREAD_COUNT threads + XNNPACK")
        return Interpreter(modelBuffer, options)
    }
}
