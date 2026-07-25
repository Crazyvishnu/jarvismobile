package com.vishnu.jarvis

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.flex.FlexDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

// Runs the openWakeWord (github.com/dscripka/openWakeWord) three-model
// pipeline entirely on-device using TFLite - fully open-source and free.
class OpenWakeWordDetector(context: Context) {

    companion object {
        private const val TAG = "OpenWakeWord"
        const val CHUNK_SAMPLES = 1280 // 80ms @ 16kHz - matches openWakeWord's own examples
    }

    private val melInterpreter: Interpreter
    private val embInterpreter: Interpreter
    private val wwInterpreter: Interpreter
    private val flexDelegate = FlexDelegate()

    private var melBinCount = 32
    private var embWindow = 76
    private var embDim = 96
    private var wwWindow = 16

    private val melBuffer = ArrayDeque<FloatArray>()
    private val embBuffer = ArrayDeque<FloatArray>()

    init {
        try {
            // CRITICAL: Disable XNNPACK. The melspectrogram model has dynamic
            // input shapes (-1), and XNNPACK tries to allocate memory during
            // Interpreter creation, which causes a "BytesRequired overflowed"
            // crash. By disabling it, we defer allocation until AFTER we
            // explicitly call resizeInput().
            // ADDED: FlexDelegate is REQUIRED for openWakeWord models as they 
            // use TF operations (like RFFT) not available in standard TFLite.
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                setUseXNNPACK(false)
                addDelegate(flexDelegate)
            }

            Log.d(TAG, "Loading mel model...")
            melInterpreter = Interpreter(loadModelFile(context, "melspectrogram.tflite"), options)
            
            Log.d(TAG, "Mel input: ${melInterpreter.getInputTensor(0).shape().contentToString()} type=${melInterpreter.getInputTensor(0).dataType()}")

            // The melspectrogram model takes a variable-length audio clip, so
            // its input MUST be resized to a concrete shape before any
            // tensors are allocated.
            Log.d(TAG, "Resizing mel input to [1, $CHUNK_SAMPLES]...")
            melInterpreter.resizeInput(0, intArrayOf(1, CHUNK_SAMPLES))
            
            Log.d(TAG, "Allocating mel tensors...")
            melInterpreter.allocateTensors()
            
            Log.d(TAG, "Loading embedding model...")
            embInterpreter = Interpreter(loadModelFile(context, "embedding_model.tflite"), options)
            embInterpreter.allocateTensors()

            Log.d(TAG, "Loading wake word model...")
            wwInterpreter = Interpreter(loadModelFile(context, "hey_jarvis_v0.1.tflite"), options)
            wwInterpreter.allocateTensors()

            // Fetch dynamic shapes after allocation
            val melOutShape = melInterpreter.getOutputTensor(0).shape()
            melBinCount = melOutShape.last()
            
            val embInShape = embInterpreter.getInputTensor(0).shape()
            embWindow = embInShape[1]
            val embOutShape = embInterpreter.getOutputTensor(0).shape()
            embDim = embOutShape.last()
            
            val wwInShape = wwInterpreter.getInputTensor(0).shape()
            wwWindow = wwInShape[1]

            Log.d(TAG, "Pipeline initialized: melOut=$melBinCount, embWin=$embWindow, embDim=$embDim, wwWin=$wwWindow")

        } catch (e: Exception) {
            Log.e(TAG, "OpenWakeWord initialization failed: ${e.message}", e)
            close() // Clean up on failure
            throw e
        }
    }

    fun processChunk(samples: ShortArray): Float? {
        val melFrames = runMelspectrogram(samples)
        for (frame in melFrames) {
            melBuffer.addLast(frame)
            while (melBuffer.size > embWindow) melBuffer.removeFirst()
        }

        if (melBuffer.size < embWindow) return null

        val embedding = runEmbedding(melBuffer)
        embBuffer.addLast(embedding)
        while (embBuffer.size > wwWindow) embBuffer.removeFirst()

        if (embBuffer.size < wwWindow) return null

        return runWakeWordClassifier(embBuffer)
    }

    private fun runMelspectrogram(samples: ShortArray): List<FloatArray> {
        val input = ByteBuffer.allocateDirect(4 * CHUNK_SAMPLES).order(ByteOrder.nativeOrder())
        for (i in 0 until CHUNK_SAMPLES) {
            val s = if (i < samples.size) samples[i] else 0
            input.putFloat(s.toFloat())
        }
        input.rewind()

        val outputTensor = melInterpreter.getOutputTensor(0)
        val outFloats = outputTensor.numElements()
        val output = ByteBuffer.allocateDirect(4 * outFloats).order(ByteOrder.nativeOrder())

        melInterpreter.run(input, output)
        output.rewind()

        val frameCount = outFloats / melBinCount
        val frames = ArrayList<FloatArray>(frameCount)
        for (f in 0 until frameCount) {
            val frame = FloatArray(melBinCount)
            for (b in 0 until melBinCount) {
                frame[b] = output.float / 10f + 2f
            }
            frames.add(frame)
        }
        return frames
    }

    private fun runEmbedding(window: ArrayDeque<FloatArray>): FloatArray {
        val input = ByteBuffer.allocateDirect(4 * embWindow * melBinCount).order(ByteOrder.nativeOrder())
        for (frame in window) {
            for (v in frame) input.putFloat(v)
        }
        input.rewind()

        val output = ByteBuffer.allocateDirect(4 * embDim).order(ByteOrder.nativeOrder())
        embInterpreter.run(input, output)
        output.rewind()

        val embedding = FloatArray(embDim)
        for (i in 0 until embDim) embedding[i] = output.float
        return embedding
    }

    private fun runWakeWordClassifier(window: ArrayDeque<FloatArray>): Float {
        val input = ByteBuffer.allocateDirect(4 * wwWindow * embDim).order(ByteOrder.nativeOrder())
        for (embedding in window) {
            for (v in embedding) input.putFloat(v)
        }
        input.rewind()

        val output = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        wwInterpreter.run(input, output)
        output.rewind()
        return output.float
    }

    fun close() {
        try {
            melInterpreter.close()
            embInterpreter.close()
            wwInterpreter.close()
            flexDelegate.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing interpreters: ${e.message}")
        }
    }

    private fun loadModelFile(context: Context, assetName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(assetName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val channel = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }
}