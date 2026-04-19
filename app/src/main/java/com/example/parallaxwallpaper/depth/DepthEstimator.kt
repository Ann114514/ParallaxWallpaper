package com.example.parallaxwallpaper.depth

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.nio.FloatBuffer

/**
 * Runs Depth Anything V2 ViT-S (quantized ONNX) from the tiefling project.
 *
 * Model: depthanythingv2-vits-dynamic-quant.onnx  (~26 MB, CPU/NNAPI)
 * Source: https://github.com/combatwombat/tiefling/tree/main/site/public/models
 *
 * Contract:
 *   Input  name  : "image"
 *   Input  shape : [1, 3, INPUT_SIZE, INPUT_SIZE]  float32  values in [0, 1]
 *   Input  layout: NCHW – all-R plane, all-G plane, all-B plane
 *   Output name  : "depth"
 *   Output shape : [1, INPUT_SIZE, INPUT_SIZE]  float32  (larger = farther)
 */
class DepthEstimator(
    context: Context,
    useNnapi: Boolean = false,
) : AutoCloseable {

    companion object {
        const val MODEL_ASSET = "depthanythingv2-vits-dynamic-quant.onnx"
        const val INPUT_SIZE  = 518  // Depth Anything V2 native resolution

        fun isModelPresent(context: Context): Boolean =
            context.assets.list("")?.contains(MODEL_ASSET) == true
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    // Pre-allocated flat float array: [R plane | G plane | B plane]
    private val inputBuf = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)

    // Normalized depth output, same length
    val depthMap = FloatArray(INPUT_SIZE * INPUT_SIZE)

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (useNnapi) {
                try { addNnapi() } catch (_: Exception) { /* not available – fall back to CPU */ }
            }
        }
        val modelBytes = context.assets.open(MODEL_ASSET).readBytes()
        session = env.createSession(modelBytes, opts)
    }

    /**
     * Returns a normalized FloatArray [INPUT_SIZE * INPUT_SIZE] where
     * 0.0 = closest pixel, 1.0 = farthest pixel.
     * NOT thread-safe – call from a single background thread.
     */
    fun estimate(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        fillInputBuffer(scaled)
        if (scaled !== bitmap) scaled.recycle()

        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputBuf),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )

        inputTensor.use {
            val results = session.run(mapOf("image" to inputTensor))
            results.use {
                val depthTensor = results["depth"].get() as OnnxTensor
                depthTensor.use {
                    // Output shape [1, H, W] → batch dimension stripped
                    val raw = depthTensor.value as Array<Array<FloatArray>>
                    var idx = 0
                    for (row in raw[0]) for (v in row) depthMap[idx++] = v
                }
            }
        }

        normalizeInPlace(depthMap)
        return depthMap
    }

    /** Converts depth float array to a greyscale ARGB_8888 Bitmap for debug preview. */
    fun depthToBitmap(): Bitmap {
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE) { i ->
            val v = (depthMap[i] * 255).toInt().coerceIn(0, 255)
            Color.argb(255, v, v, v)
        }
        return Bitmap.createBitmap(pixels, INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    }

    private fun fillInputBuffer(bitmap: Bitmap) {
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        val planeSize = INPUT_SIZE * INPUT_SIZE
        for (i in pixels.indices) {
            val px = pixels[i]
            inputBuf[i]               = ((px shr 16) and 0xFF) / 255f  // R
            inputBuf[planeSize + i]   = ((px shr  8) and 0xFF) / 255f  // G
            inputBuf[2 * planeSize + i] = (px and 0xFF)          / 255f  // B
        }
    }

    private fun normalizeInPlace(arr: FloatArray) {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (v in arr) { if (v < min) min = v; if (v > max) max = v }
        val range = (max - min).coerceAtLeast(1e-5f)
        for (i in arr.indices) arr[i] = (arr[i] - min) / range
    }

    override fun close() {
        session.close()
        env.close()
    }
}
