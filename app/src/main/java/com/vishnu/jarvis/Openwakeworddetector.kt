package com.vishnu.jarvis

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer

/**
 * Runs the openWakeWord (github.com/dscripka/openWakeWord) three-model
 * pipeline entirely on-device using ONNX Runtime.
 *
 * Pipeline: raw 16 kHz PCM → melspectrogram → embedding → wake-word score
 */
class OpenWakeWordDetector(context: Context) {

    companion object {
        private const val TAG = "OpenWakeWord"
        const val CHUNK_SAMPLES = 1280 // 80 ms @ 16 kHz
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val melSession: OrtSession
    private val embSession: OrtSession
    private val wwSession: OrtSession

    private var melBinCount = 32
    private var embWindow = 76
    private var embDim = 96
    private var wwWindow = 16

    // Dynamic shapes determined at runtime from ONNX metadata
    private var melInputShape = longArrayOf(1, CHUNK_SAMPLES.toLong())
    private var embInputShape = longArrayOf(1, embWindow.toLong(), melBinCount.toLong())
    private var wwInputShape = longArrayOf(1, wwWindow.toLong(), embDim.toLong())

    private val melBuffer = ArrayDeque<FloatArray>()
    private val embBuffer = ArrayDeque<FloatArray>()

    private var chunkCount = 0

    init {
        try {
            Log.i(TAG, "Loading ONNX models...")

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }

            melSession = env.createSession(loadAsset(context, "melspectrogram.onnx"), sessionOptions)
            Log.i(TAG, "Mel model loaded ✓")
            logSessionInfo("mel", melSession)

            embSession = env.createSession(loadAsset(context, "embedding_model.onnx"), sessionOptions)
            Log.i(TAG, "Embedding model loaded ✓")
            logSessionInfo("emb", embSession)

            wwSession = env.createSession(loadAsset(context, "hey_jarvis_v0.1.onnx"), sessionOptions)
            Log.i(TAG, "Wake word model loaded ✓")
            logSessionInfo("ww", wwSession)

            // 1. Determine Mel input shape from metadata
            // Expected shape is [-1, -1] corresponding to [batch_size, samples].
            // We set batch_size = 1 and samples = CHUNK_SAMPLES.
            melSession.inputInfo.values.firstOrNull()?.info?.let { info ->
                val shape = (info as? ai.onnxruntime.TensorInfo)?.shape
                if (shape != null) {
                    melInputShape = if (shape.size == 2) {
                        longArrayOf(1, CHUNK_SAMPLES.toLong())
                    } else {
                        shape.map { dim ->
                            if (dim <= 0) CHUNK_SAMPLES.toLong() else dim
                        }.toLongArray()
                    }
                }
            }

            // 2. Determine Embedding input shape from metadata
            // Expected shape is [-1, 76, 32, 1] corresponding to [batch_size, window, bins, channels].
            embSession.inputInfo.values.firstOrNull()?.info?.let { info ->
                val shape = (info as? ai.onnxruntime.TensorInfo)?.shape
                Log.i(TAG, "Embedding input shape from metadata: ${shape?.contentToString()}")
                if (shape != null) {
                    embInputShape = shape.map { dim ->
                        when {
                            dim == -1L || dim == 0L -> 1L // batch size default
                            else -> dim
                        }
                    }.toLongArray()

                    // Extract dimensions if matching standard layouts
                    if (embInputShape.size >= 3) {
                        embWindow = embInputShape[1].toInt()
                        melBinCount = embInputShape[2].toInt()
                    }
                }
            }

            // 3. Determine Wake word input shape from metadata
            // Expected shape is [1, 16, 96] corresponding to [batch_size, window, features].
            wwSession.inputInfo.values.firstOrNull()?.info?.let { info ->
                val shape = (info as? ai.onnxruntime.TensorInfo)?.shape
                Log.i(TAG, "WW input shape from metadata: ${shape?.contentToString()}")
                if (shape != null) {
                    wwInputShape = shape.map { dim ->
                        when {
                            dim == -1L || dim == 0L -> 1L // batch size default
                            else -> dim
                        }
                    }.toLongArray()

                    // Extract dimensions if matching standard layouts
                    if (wwInputShape.size >= 3) {
                        wwWindow = wwInputShape[1].toInt()
                        embDim = wwInputShape[2].toInt()
                    }
                }
            }

            Log.i(TAG, "Pipeline config: melBins=$melBinCount, embWin=$embWindow, embDim=$embDim, wwWin=$wwWindow")
            Log.i(TAG, "Mel input shape to use: ${melInputShape.contentToString()}")
            Log.i(TAG, "Emb input shape to use: ${embInputShape.contentToString()}")
            Log.i(TAG, "WW input shape to use: ${wwInputShape.contentToString()}")
            Log.i(TAG, "Pipeline initialized (ONNX Runtime) ✓")
        } catch (e: Exception) {
            Log.e(TAG, "OpenWakeWord initialization failed: ${e.message}", e)
            close()
            throw e
        }
    }

    fun processChunk(samples: ShortArray): Float? {
        chunkCount++
        val melFrames = runMelspectrogram(samples)

        if (chunkCount <= 3 || chunkCount % 100 == 0) {
            Log.d(TAG, "Chunk #$chunkCount: mel produced ${melFrames.size} frames, melBuffer=${melBuffer.size}/$embWindow, embBuffer=${embBuffer.size}/$wwWindow")
        }

        for (frame in melFrames) {
            melBuffer.addLast(frame)
            while (melBuffer.size > embWindow) melBuffer.removeFirst()
        }

        if (melBuffer.size < embWindow) return null

        val embedding = runEmbedding(melBuffer)
        embBuffer.addLast(embedding)
        while (embBuffer.size > wwWindow) embBuffer.removeFirst()

        if (embBuffer.size < wwWindow) return null

        val score = runWakeWordClassifier(embBuffer)

        // Log score when positive or periodically
        if (score > 0.05f || chunkCount % 200 == 0) {
            Log.d(TAG, "Chunk #$chunkCount: wake word score = $score")
        }

        return score
    }

    /**
     * Step 1: Raw PCM audio → mel spectrogram frames
     */
    private fun runMelspectrogram(samples: ShortArray): List<FloatArray> {
        val floatSamples = FloatArray(CHUNK_SAMPLES) { i ->
            if (i < samples.size) samples[i].toFloat() else 0f
        }

        val inputName = melSession.inputNames.first()
        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(floatSamples),
            melInputShape
        )

        val result = melSession.run(mapOf(inputName to inputTensor))
        inputTensor.close()

        val outputTensor = result[0]
        val outputInfo = outputTensor.info as? ai.onnxruntime.TensorInfo
        val outputShape = outputInfo?.shape

        val frames = parseMelOutput(outputTensor.value, outputShape)
        result.close()
        return frames
    }

    private fun parseMelOutput(value: Any?, shape: LongArray?): List<FloatArray> {
        val frames = mutableListOf<FloatArray>()

        if (value == null) {
            Log.w(TAG, "Mel output is null!")
            return frames
        }

        try {
            // Try 4D: [1, 1, num_frames, mel_bins] → Array<Array<Array<FloatArray>>>
            @Suppress("UNCHECKED_CAST")
            val batch4d = value as? Array<Array<Array<FloatArray>>>
            if (batch4d != null && batch4d.isNotEmpty() && batch4d[0].isNotEmpty() && batch4d[0][0].isNotEmpty()) {
                val melFrames = batch4d[0][0]
                melBinCount = melFrames[0].size
                for (frame in melFrames) {
                    val normalized = FloatArray(frame.size) { i -> frame[i] / 10f + 2f }
                    frames.add(normalized)
                }
                return frames
            }
        } catch (_: Exception) {}

        try {
            // Try 3D: [1, num_frames, mel_bins] → Array<Array<FloatArray>>
            @Suppress("UNCHECKED_CAST")
            val batch3d = value as? Array<Array<FloatArray>>
            if (batch3d != null && batch3d.isNotEmpty() && batch3d[0].isNotEmpty()) {
                val melFrames = batch3d[0]
                melBinCount = melFrames[0].size
                for (frame in melFrames) {
                    val normalized = FloatArray(frame.size) { i -> frame[i] / 10f + 2f }
                    frames.add(normalized)
                }
                return frames
            }
        } catch (_: Exception) {}

        try {
            // Try 2D: [num_frames, mel_bins] → Array<FloatArray>
            @Suppress("UNCHECKED_CAST")
            val batch2d = value as? Array<FloatArray>
            if (batch2d != null && batch2d.isNotEmpty()) {
                melBinCount = batch2d[0].size
                for (frame in batch2d) {
                    val normalized = FloatArray(frame.size) { i -> frame[i] / 10f + 2f }
                    frames.add(normalized)
                }
                return frames
            }
        } catch (_: Exception) {}

        Log.w(TAG, "Mel output format unknown: ${value.javaClass.name}")
        return frames
    }

    /**
     * Step 2: mel spectrogram window → embedding vector
     */
    private fun runEmbedding(window: ArrayDeque<FloatArray>): FloatArray {
        val flatSize = embWindow * melBinCount
        val flatData = FloatArray(flatSize)
        var idx = 0
        for (frame in window) {
            for (v in frame) {
                if (idx < flatSize) flatData[idx++] = v
            }
        }

        val inputName = embSession.inputNames.first()
        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(flatData),
            embInputShape
        )

        val result = embSession.run(mapOf(inputName to inputTensor))
        inputTensor.close()

        val embedding = parseEmbeddingOutput(result[0].value)
        result.close()
        return embedding
    }

    private fun parseEmbeddingOutput(value: Any?): FloatArray {
        if (value == null) return FloatArray(embDim)

        try {
            // [1, 1, 1, embDim] → Array<Array<Array<FloatArray>>>
            @Suppress("UNCHECKED_CAST")
            val batch4d = value as? Array<Array<Array<FloatArray>>>
            if (batch4d != null && batch4d.isNotEmpty() && batch4d[0].isNotEmpty() && batch4d[0][0].isNotEmpty() && batch4d[0][0][0].isNotEmpty()) {
                val flat = batch4d[0][0][0]
                embDim = flat.size
                return flat
            }
        } catch (_: Exception) {}

        try {
            // [1, 1, embDim] → Array<Array<FloatArray>>
            @Suppress("UNCHECKED_CAST")
            val batch3d = value as? Array<Array<FloatArray>>
            if (batch3d != null && batch3d.isNotEmpty() && batch3d[0].isNotEmpty()) {
                val flat = batch3d[0][0]
                embDim = flat.size
                return flat
            }
        } catch (_: Exception) {}

        try {
            // [1, embDim] → Array<FloatArray>
            @Suppress("UNCHECKED_CAST")
            val batch2d = value as? Array<FloatArray>
            if (batch2d != null && batch2d.isNotEmpty()) {
                embDim = batch2d[0].size
                return batch2d[0]
            }
        } catch (_: Exception) {}

        try {
            // [embDim] → FloatArray
            val flat = value as? FloatArray
            if (flat != null) {
                embDim = flat.size
                return flat
            }
        } catch (_: Exception) {}

        Log.w(TAG, "Emb output format unknown: ${value.javaClass.name}")
        return FloatArray(embDim)
    }

    /**
     * Step 3: embedding window → wake-word confidence score [0, 1]
     */
    private fun runWakeWordClassifier(window: ArrayDeque<FloatArray>): Float {
        val flatSize = wwWindow * embDim
        val flatData = FloatArray(flatSize)
        var idx = 0
        for (embedding in window) {
            for (v in embedding) {
                if (idx < flatSize) flatData[idx++] = v
            }
        }

        val inputName = wwSession.inputNames.first()
        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(flatData),
            wwInputShape
        )

        val result = wwSession.run(mapOf(inputName to inputTensor))
        inputTensor.close()

        val score = parseWakeWordOutput(result[0].value)
        result.close()
        return score
    }

    private fun parseWakeWordOutput(value: Any?): Float {
        if (value == null) return 0f

        try {
            // [1, 1] → Array<FloatArray>
            @Suppress("UNCHECKED_CAST")
            val batch = value as? Array<FloatArray>
            if (batch != null && batch.isNotEmpty() && batch[0].isNotEmpty()) {
                return batch[0][0]
            }
        } catch (_: Exception) {}

        try {
            // [1] → FloatArray
            val flat = value as? FloatArray
            if (flat != null && flat.isNotEmpty()) {
                return flat[0]
            }
        } catch (_: Exception) {}

        try {
            // scalar
            val scalar = value as? Float
            if (scalar != null) return scalar
        } catch (_: Exception) {}

        Log.w(TAG, "WW output format unknown: ${value.javaClass.name}")
        return 0f
    }

    /**
     * Clear all internal ring buffers after detection to prevent ghost triggers.
     */
    fun reset() {
        melBuffer.clear()
        embBuffer.clear()
        chunkCount = 0
    }

    fun close() {
        try {
            melSession.close()
            embSession.close()
            wwSession.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing sessions: ${e.message}")
        }
    }

    private fun loadAsset(context: Context, name: String): ByteArray {
        return context.assets.open(name).use { it.readBytes() }
    }

    private fun logSessionInfo(label: String, session: OrtSession) {
        val inputs = session.inputInfo.entries.joinToString { (name, info) ->
            val tInfo = info.info as? ai.onnxruntime.TensorInfo
            "$name: shape=${tInfo?.shape?.contentToString()}, type=${tInfo?.type}"
        }
        val outputs = session.outputInfo.entries.joinToString { (name, info) ->
            val tInfo = info.info as? ai.onnxruntime.TensorInfo
            "$name: shape=${tInfo?.shape?.contentToString()}, type=${tInfo?.type}"
        }
        Log.i(TAG, "[$label] inputs: [$inputs]")
        Log.i(TAG, "[$label] outputs: [$outputs]")
    }
}