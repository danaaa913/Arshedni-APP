package com.arshadi.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class YoloDetector(private val context: Context) {

    companion object {
        private const val TAG        = "YoloDetector"
        private const val INPUT_SIZE = 320     // ✅ 320 بدل 640 — 4x أسرع
        private const val MAX_DETS   = 20
    }

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()

    // ── Output shape info ──
    private var numAnchors  = 0
    private var numClasses  = 0
    private var rowSize     = 0
    private var isTransposed= false

    // ── Quantization ──
    private var outScale    = 1f
    private var outZeroPoint= 0
    private var isOutFloat  = true

    // ── Bbox format ──
    private var bboxDivFactor = 1f
    private var formatDetected= false

    // ── Adaptive threshold ──
    private var confThresh     = 0.25f
    private val confThreshMin  = 0.12f
    private val confThreshMax  = 0.40f
    private var consecutiveNoDet = 0

    // ── Input buffer (reused every frame) ──
    private lateinit var inputBuffer: ByteBuffer
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null

    // ── Real heights for distance estimation ──
    private val classHeights = mapOf(
        "car" to 1.50f, "person" to 1.70f, "stairs" to 0.30f,
        "chair" to 0.90f, "trash" to 1.10f, "tree" to 4.00f,
        "sidewalk" to 0.15f, "sill" to 0.12f, "hole" to 0.15f, "obstacle" to 1.00f
    )

    // ── Metrics ──
    var lastInferenceMs = 0L
        private set

    // ─────────────────────────────────────────────────────────────────────────
    fun initialize(): Boolean {
        return try {
            // Load labels
            labels = context.assets.open(Constants.LABELS_PATH)
                .bufferedReader().readLines()
                .filter { it.isNotBlank() }
            Log.d(TAG, "Labels: ${labels.size} — ${labels.joinToString()}")

            // Load model
            val model = loadModelFile()

            // Build interpreter options
            val options = Interpreter.Options().apply {
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

                // ✅ Try GPU delegate first (fastest)
                val compatList = CompatibilityList()
                if (compatList.isDelegateSupportedOnThisDevice) {
                    gpuDelegate = GpuDelegate(compatList.bestOptionsForThisDevice)
                    addDelegate(gpuDelegate!!)
                    Log.d(TAG, "⚡ GPU delegate active")
                } else {
                    // ✅ Fallback: NNAPI
                    try {
                        nnApiDelegate = NnApiDelegate()
                        addDelegate(nnApiDelegate!!)
                        Log.d(TAG, "⚡ NNAPI delegate active")
                    } catch (e: Exception) {
                        Log.w(TAG, "NNAPI failed, CPU only: $e")
                    }
                }
            }

            interpreter = Interpreter(model, options)

            // Analyze output shape
            val outTensor  = interpreter!!.getOutputTensor(0)
            val outShape   = outTensor.shape()
            isTransposed   = outShape.size == 3 && outShape[1] < outShape[2]
            numAnchors     = if (isTransposed) outShape[2] else outShape[1]
            rowSize        = if (isTransposed) outShape[1] else outShape[2]
            numClasses     = rowSize - 4  // no mask coefficients for detection model
            isOutFloat     = outTensor.dataType().toString().contains("FLOAT")

            if (!isOutFloat) {
                outScale     = outTensor.quantizationParams().scale
                outZeroPoint = outTensor.quantizationParams().zeroPoint
                if (outScale <= 0f || outScale.isNaN()) outScale = 1f / 255f
            }

            Log.d(TAG, "Model: transposed=$isTransposed anchors=$numAnchors " +
                    "classes=$numClasses float=$isOutFloat scale=$outScale")

            // Allocate input buffer (reused every frame — no GC pressure)
            val inTensor  = interpreter!!.getInputTensor(0)
            val isInInt   = inTensor.dataType().toString().let {
                it.contains("INT8") || it.contains("UINT8")
            }
            val bytesPerElem = if (isInInt) 1 else 4
            inputBuffer = ByteBuffer.allocateDirect(
                1 * INPUT_SIZE * INPUT_SIZE * 3 * bytesPerElem
            ).apply { order(ByteOrder.nativeOrder()) }

            Log.d(TAG, "✅ YoloDetector ready — inputSize=$INPUT_SIZE")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Init failed: $e")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ✅ MAIN DETECT — الدالة الرئيسية — تستقبل Bitmap مباشرة من CameraX
    // CameraX بيحوّل YUV→Bitmap بـ native JNI في ~5ms بدل ~150ms في Dart
    // ─────────────────────────────────────────────────────────────────────────
    fun detect(bitmap: Bitmap): List<DetectionResult> {
        val interp = interpreter ?: return emptyList()

        // ── 1. Resize to 320×320 (native Bitmap scaling ~2ms) ──
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

        // ── 2. Fill input buffer from Bitmap pixels ──
        inputBuffer.rewind()
        val inTensor = interp.getInputTensor(0)
        val isInInt  = inTensor.dataType().toString().let {
            it.contains("INT8") || it.contains("UINT8")
        }
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8)  and 0xFF
            val b =  px         and 0xFF
            if (isInInt) {
                inputBuffer.put(r.toByte())
                inputBuffer.put(g.toByte())
                inputBuffer.put(b.toByte())
            } else {
                inputBuffer.putFloat(r / 255f)
                inputBuffer.putFloat(g / 255f)
                inputBuffer.putFloat(b / 255f)
            }
        }
        scaled.recycle()
        inputBuffer.rewind()

        // ── 3. Allocate output buffer ──
        val outTensor = interp.getOutputTensor(0)
        val outBuf    = ByteBuffer.allocateDirect(
            outTensor.numBytes()
        ).apply { order(ByteOrder.nativeOrder()) }

        // ── 4. Run inference ──
        val t0 = System.currentTimeMillis()
        interp.run(inputBuffer, outBuf)
        lastInferenceMs = System.currentTimeMillis() - t0
        Log.d(TAG, "Inference: ${lastInferenceMs}ms")

        outBuf.rewind()

        // ── 5. Parse output ──
        return parseOutput(outBuf)
    }

    // ─────────────────────────────────────────────────────────────────────────
    private fun parseOutput(outBuf: ByteBuffer): List<DetectionResult> {
        // Read all values into flat array for fast access
        val totalVals = numAnchors * rowSize
        val vals = FloatArray(totalVals)

        if (isOutFloat) {
            outBuf.asFloatBuffer().get(vals)
        } else {
            // INT8 dequantize
            val raw = ByteArray(totalVals)
            outBuf.get(raw)
            for (i in vals.indices) {
                vals[i] = outScale * ((raw[i].toInt() and 0xFF) - outZeroPoint)
            }
        }

        // ── Helper: read value at (anchor, col) respecting transposed flag ──
        fun readVal(anchor: Int, col: Int): Float {
            return if (isTransposed) vals[col * numAnchors + anchor]
            else              vals[anchor * rowSize + col]
        }

        // ── Auto-detect bbox coordinate format on first frame ──
        if (!formatDetected) {
            var bboxMax = 0f
            for (a in 0 until minOf(200, numAnchors)) {
                for (c in 0..3) {
                    val v = readVal(a, c)
                    if (v > bboxMax) bboxMax = v
                }
            }
            bboxDivFactor = if (bboxMax > 2f) INPUT_SIZE.toFloat() else 1f
            formatDetected = true
            Log.d(TAG, "BBox format: ${if (bboxMax > 2f) "PIXEL" else "NORMALIZED"} " +
                    "bboxMax=$bboxMax divFactor=$bboxDivFactor")
        }

        // ── Parse anchors ──
        val raw = mutableListOf<FloatArray>() // [id, conf, x1, y1, x2, y2]
        var frameMaxConf = 0f

        for (a in 0 until numAnchors) {
            var maxConf = 0f; var bestCls = 0
            for (c in 4 until 4 + numClasses) {
                val s = readVal(a, c)
                if (s > maxConf) { maxConf = s; bestCls = c - 4 }
            }
            if (maxConf > frameMaxConf) frameMaxConf = maxConf
            if (maxConf < confThresh) continue

            val cx = readVal(a, 0) / bboxDivFactor
            val cy = readVal(a, 1) / bboxDivFactor
            val bw = readVal(a, 2) / bboxDivFactor
            val bh = readVal(a, 3) / bboxDivFactor

            val x1 = (cx - bw / 2f).coerceIn(0f, 1f)
            val y1 = (cy - bh / 2f).coerceIn(0f, 1f)
            val x2 = (cx + bw / 2f).coerceIn(0f, 1f)
            val y2 = (cy + bh / 2f).coerceIn(0f, 1f)

            // Reject tiny boxes
            if ((x2 - x1) < 0.015f || (y2 - y1) < 0.015f) continue

            raw.add(floatArrayOf(bestCls.toFloat(), maxConf, x1, y1, x2, y2))
        }

        // ── NMS ──
        val kept = applyNms(raw)

        // ── Adaptive threshold ──
        if (kept.isEmpty()) {
            consecutiveNoDet++
            if (consecutiveNoDet >= 20 && confThresh > confThreshMin) {
                confThresh = (confThresh - 0.02f).coerceIn(confThreshMin, confThreshMax)
                consecutiveNoDet = 0
                Log.d(TAG, "ADAPTIVE: thresh ↓ $confThresh")
            }
        } else {
            consecutiveNoDet = 0
            if (kept.size > 8 && confThresh < confThreshMax) {
                confThresh = (confThresh + 0.02f).coerceIn(confThreshMin, confThreshMax)
                Log.d(TAG, "ADAPTIVE: thresh ↑ $confThresh")
            }
        }

        // ── Build results ──
        return kept.take(MAX_DETS).map { d ->
            val label  = labels.getOrElse(d[0].toInt()) { "عائق" }
            val x1 = d[2]; val y1 = d[3]; val x2 = d[4]; val y2 = d[5]
            val hNorm  = (y2 - y1).coerceAtLeast(0.001f)
            val realH  = classHeights[label.lowercase()] ?: 1f
            val dist   = (realH * 1.2f) / hNorm
            val cx     = (x1 + x2) / 2f
            val cy     = (y1 + y2) / 2f
            val hPos   = when {
                cx < 0.35f -> "على يسارك"
                cx > 0.65f -> "على يمينك"
                else       -> "أمامك مباشرة"
            }
            val vPos = when {
                cy < 0.33f -> "أعلى"
                cy > 0.66f -> "أسفل"
                else       -> "وسط"
            }
            DetectionResult(label, d[0].toInt(), d[1], x1, y1, x2, y2, dist, hPos, vPos)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    private fun applyNms(dets: MutableList<FloatArray>): List<FloatArray> {
        if (dets.isEmpty()) return emptyList()
        dets.sortByDescending { it[1] } // sort by conf desc
        val keep = mutableListOf<FloatArray>()
        val suppressed = BooleanArray(dets.size)
        for (i in dets.indices) {
            if (suppressed[i]) continue
            keep.add(dets[i])
            for (j in i + 1 until dets.size) {
                if (!suppressed[j] && iou(dets[i], dets[j]) > 0.45f) {
                    suppressed[j] = true
                }
            }
        }
        return keep
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ix1 = max(a[2], b[2]); val iy1 = max(a[3], b[3])
        val ix2 = min(a[4], b[4]); val iy2 = min(a[5], b[5])
        if (ix2 <= ix1 || iy2 <= iy1) return 0f
        val inter = (ix2 - ix1) * (iy2 - iy1)
        val aArea = (a[4] - a[2]) * (a[5] - a[3])
        val bArea = (b[4] - b[2]) * (b[5] - b[3])
        return inter / (aArea + bArea - inter + 1e-9f)
    }

    private fun loadModelFile(): MappedByteBuffer {
        val afd  = context.assets.openFd(Constants.MODEL_PATH)
        val fis  = FileInputStream(afd.fileDescriptor)
        val chan = fis.channel
        return chan.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }

    // ─────────────────────────────────────────────────────────────────────────
    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
        nnApiDelegate?.close()
    }
}
