
package com.dec.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline Heed wake-word detector.
 *
 * Model: assets/wake.onnx
 * Metadata: assets/wake.json
 * Audio: 16 kHz, mono, PCM16
 * Model input: [1, 40, 101]
 */
class WakeWord(
    private val ctx: Context,
    private val onWake: () -> Unit
) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SAMPLES = 16000
        private const val CHUNK_SAMPLES = 1600
        private const val N_FFT = 512
        private const val WIN_LENGTH = 400
        private const val HOP = 160
        private const val N_MELS = 40
        private const val N_FRAMES = 101
        private const val N_BINS = 257
    }

    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    private var threshold = 0.653f
    private var consecutiveFrames = 2
    private var refractorySeconds = 0.7
    private var rmsThresholdDbfs = -55.0
    private var voiceBandMinFraction = 0.15

    private var aboveCount = 0
    private var lastTrigger = 0L
    private var ema = 0.0

    private val audioWindow = FloatArray(WINDOW_SAMPLES)
    private var filledSamples = 0

    private val preprocessor = HeedPreprocessor()

    fun configured(): Boolean {
        return try {
            ctx.assets.open("wake.onnx").use { }
            ctx.assets.open("wake.json").use { }
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun start(): String? {
        if (running) return null

        if (!configured()) {
            return "Wake model missing. Check android/app/src/main/assets/"
        }

        return try {
            loadMetadata()
            loadModel()
            startMicrophone()
            null
        } catch (e: Exception) {
            stop()
            "Wake-word startup failed: ${e.message}"
        }
    }

    private fun loadMetadata() {
        val json = ctx.assets.open("wake.json")
            .bufferedReader()
            .use { it.readText() }

        val meta = JSONObject(json)

        threshold = meta.optDouble("threshold", 0.653).toFloat()

        val trigger = meta.optJSONObject("trigger")
        consecutiveFrames = trigger?.optInt("consecutive_frames", 2) ?: 2
        refractorySeconds =
            trigger?.optDouble("refractory_seconds", 0.7) ?: 0.7

        val gate = meta.optJSONObject("energy_gate")
        rmsThresholdDbfs =
            gate?.optDouble("rms_threshold_dbfs", -55.0) ?: -55.0
        voiceBandMinFraction =
            gate?.optDouble("voice_band_min_fraction", 0.15) ?: 0.15

        if (meta.optInt("sample_rate", 16000) != SAMPLE_RATE ||
            meta.optInt("n_mels", 40) != N_MELS ||
            meta.optInt("n_fft", 512) != N_FFT ||
            meta.optInt("window_frames", 100) != 100
        ) {
            throw IllegalStateException("Unsupported wake.json audio settings")
        }

        if (consecutiveFrames < 1) consecutiveFrames = 2
    }

    private fun loadModel() {
        if (session != null) return

        val bytes = ctx.assets.open("wake.onnx")
            .use { it.readBytes() }

        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions()

        try {
            session = env.createSession(bytes, options)
            environment = env
        } finally {
            options.close()
        }

        val input = session!!.inputInfo["mel"]
            ?: throw IllegalStateException("Model input 'mel' not found")

        val shape = input.info as ai.onnxruntime.TensorInfo

        if (shape.shape.size != 3 ||
            shape.shape[1] != N_MELS.toLong() ||
            shape.shape[2] != N_FRAMES.toLong()
        ) {
            throw IllegalStateException(
                "Unexpected model input shape: ${shape.shape.contentToString()}"
            )
        }
    }

    private fun startMicrophone() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuffer <= 0) {
            throw IllegalStateException("Invalid microphone buffer size")
        }

        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer, CHUNK_SAMPLES * 4)
        )

        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            throw IllegalStateException("Could not initialize microphone")
        }

        audio.startRecording()

        if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            audio.release()
            throw IllegalStateException("Microphone did not start")
        }

        recorder = audio
        running = true
        aboveCount = 0
        lastTrigger = 0L
        ema = 0.0
        filledSamples = 0
        audioWindow.fill(0f)
        preprocessor.reset()

        worker = Thread({
            microphoneLoop(audio)
        }, "DEC-Heed-Wake").also {
            it.start()
        }
    }

    private fun microphoneLoop(audio: AudioRecord) {
        val pcm = ShortArray(CHUNK_SAMPLES)

        try {
            while (running && !Thread.currentThread().isInterrupted) {
                val count = audio.read(
                    pcm,
                    0,
                    pcm.size,
                    AudioRecord.READ_BLOCKING
                )

                if (count <= 0) continue

                val chunk = FloatArray(count)

                for (i in 0 until count) {
                    chunk[i] = pcm[i] / 32768.0f
                }

                processAudio(chunk)
            }
        } catch (_: Exception) {
            if (running) {
                android.util.Log.e(
                    "DEC-WAKE",
                    "Microphone processing stopped unexpectedly"
                )
            }
        }
    }

    private fun processAudio(chunk: FloatArray) {
        preprocessor.ingest(chunk)

        if (chunk.size >= WINDOW_SAMPLES) {
            System.arraycopy(
                chunk,
                chunk.size - WINDOW_SAMPLES,
                audioWindow,
                0,
                WINDOW_SAMPLES
            )
            filledSamples = WINDOW_SAMPLES
        } else {
            val shift = chunk.size
            System.arraycopy(
                audioWindow,
                shift,
                audioWindow,
                0,
                WINDOW_SAMPLES - shift
            )
            System.arraycopy(
                chunk,
                0,
                audioWindow,
                WINDOW_SAMPLES - shift,
                shift
            )
            filledSamples = min(
                WINDOW_SAMPLES,
                filledSamples + shift
            )
        }

        if (filledSamples < WINDOW_SAMPLES) return

        if (!energyGate()) {
            aboveCount = 0
            ema *= 0.5
            return
        }

        val features = preprocessor.computeMel()
        val logit = runModel(features)
        val probability = sigmoid(logit)

        ema = 0.5 * probability + 0.5 * ema

        if (probability > threshold) {
            aboveCount++
        } else {
            aboveCount = 0
        }

        val now = SystemClock.elapsedRealtime()

        if (
            aboveCount >= consecutiveFrames &&
            (lastTrigger == 0L ||
                now - lastTrigger >= (refractorySeconds * 1000).toLong())
        ) {
            aboveCount = 0
            lastTrigger = now

            android.util.Log.i(
                "DEC-WAKE",
                "Wake word detected: probability=$probability"
            )

            onWake()
        }
    }

    private fun energyGate(): Boolean {
        var sumSq = 0.0

        for (sample in audioWindow) {
            sumSq += sample.toDouble() * sample
        }

        val rms = sqrt(sumSq / WINDOW_SAMPLES)

        val dbfs = if (rms < 1e-9) {
            -120.0
        } else {
            20.0 * log10(rms)
        }

        if (dbfs < rmsThresholdDbfs) return false

        var lowPass = 0.0
        var bandSumSq = 0.0

        for (sample in audioWindow) {
            lowPass += 0.936 * (sample - lowPass)
            bandSumSq += lowPass * lowPass
        }

        val fraction = bandSumSq / (sumSq + 1e-12)

        return fraction >= voiceBandMinFraction
    }

    private fun runModel(features: FloatArray): Float {
        val env = environment
            ?: throw IllegalStateException("ONNX environment unavailable")

        val activeSession = session
            ?: throw IllegalStateException("ONNX model unavailable")

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(features),
            longArrayOf(1, N_MELS.toLong(), N_FRAMES.toLong())
        )

        tensor.use {
            activeSession.run(mapOf("mel" to tensor)).use { result ->
                val output = result[0].value as FloatArray
                return output[0]
            }
        }
    }

    private fun sigmoid(value: Float): Double {
        return if (value >= 0f) {
            1.0 / (1.0 + exp(-value.toDouble()))
        } else {
            val e = exp(value.toDouble())
            e / (1.0 + e)
        }
    }

    @Synchronized
    fun stop() {
        running = false

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }

        try {
            worker?.interrupt()
            worker?.join(1000)
        } catch (_: Exception) {
        }

        try {
            recorder?.release()
        } catch (_: Exception) {
        }

        recorder = null
        worker = null
        filledSamples = 0
        aboveCount = 0
        preprocessor.reset()
    }

    @Synchronized
    fun release() {
        stop()

        try {
            session?.close()
        } catch (_: Exception) {
        }

        session = null

        try {
            environment?.close()
        } catch (_: Exception) {
        }

        environment = null
    }

    /**
     * Heed-compatible streaming log-mel preprocessing.
     * Produces bin-major Float32Array[40 * 101].
     */
    private class HeedPreprocessor {

        private data class Section(
            val b0: Double,
            val b1: Double,
            val b2: Double,
            val a1: Double,
            val a2: Double,
            val zi1: Double,
            val zi2: Double
        )

        private class Stage(val sections: List<Section>) {
            val s1 = DoubleArray(sections.size)
            val s2 = DoubleArray(sections.size)
            var initialized = false

            fun reset() {
                s1.fill(0.0)
                s2.fill(0.0)
                initialized = false
            }

            fun process(data: DoubleArray) {
                if (data.isEmpty()) return

                if (!initialized) {
                    val first = data[0]
                    for (i in sections.indices) {
                        s1[i] = sections[i].zi1 * first
                        s2[i] = sections[i].zi2 * first
                    }
                    initialized = true
                }

                for (sectionIndex in sections.indices) {
                    val section = sections[sectionIndex]
                    var state1 = s1[sectionIndex]
                    var state2 = s2[sectionIndex]

                    for (i in data.indices) {
                        val x = data[i]
                        val y = section.b0 * x + state1

                        state1 =
                            section.b1 * x + state2 - section.a1 * y

                        state2 =
                            section.b2 * x - section.a2 * y

                        data[i] = y
                    }

                    s1[sectionIndex] = state1
                    s2[sectionIndex] = state2
                }
            }
        }

        private val stages = listOf(
            Stage(
                listOf(
                    Section(
                        0.9042459858119793,
                        -1.8084919716239587,
                        0.9042459858119793,
                        -1.9243597360235134,
                        0.9258444923331056,
                        -0.9042459858119207,
                        0.9042459858119251
                    ),
                    Section(
                        1.0, -2.0, 1.0,
                        -1.935283933705594,
                        0.9367771186738887,
                        0.0, 0.0
                    ),
                    Section(
                        1.0, -2.0, 1.0,
                        -1.9557989964479017,
                        0.9573080099893462,
                        0.0, 0.0
                    ),
                    Section(
                        1.0, -2.0, 1.0,
                        -1.9832678076605255,
                        0.9847980149995752,
                        0.0, 0.0
                    )
                )
            ),
            Stage(
                listOf(
                    Section(
                        0.9996728578106041,
                        -1.9989603227048798,
                        0.9996728578106046,
                        -1.9989603227048796,
                        0.9993457156212082,
                        0.00032714218982763185,
                        0.0003271421889648693
                    )
                )
            ),
            Stage(
                listOf(
                    Section(
                        0.9996074550501642,
                        -1.9986599884535616,
                        0.9996074550501639,
                        -1.9986599884535616,
                        0.999214910100328,
                        0.0003925449500836464,
                        0.0003925449495881815
                    )
                )
            )
        )

        private val filtered = FloatArray(16000)
        private val hann = DoubleArray(512)
        private val melWeights = Array(40) { DoubleArray(257) }
        private val melBuffer = Array(40) { DoubleArray(101) }

        private val real = DoubleArray(512)
        private val imaginary = DoubleArray(512)
        private val bitReverse = IntArray(512)

        private val output = FloatArray(40 * 101)

        init {
            buildHann()
            buildMelFilterbank()
            buildFftTables()
        }

        fun reset() {
            stages.forEach { it.reset() }
            filtered.fill(0f)
            melBuffer.forEach { it.fill(0.0) }
        }

        fun ingest(chunk: FloatArray) {
            if (chunk.isEmpty()) return

            val data = DoubleArray(chunk.size) {
                chunk[it].toDouble()
            }

            stages.forEach { it.process(data) }

            if (data.size >= 16000) {
                val start = data.size - 16000
                for (i in 0 until 16000) {
                    filtered[i] = data[start + i].toFloat()
                }
            } else {
                val shift = data.size

                System.arraycopy(
                    filtered,
                    shift,
                    filtered,
                    0,
                    16000 - shift
                )

                for (i in data.indices) {
                    filtered[16000 - shift + i] = data[i].toFloat()
                }
            }
        }

        fun computeMel(): FloatArray {
            for (frame in 0 until 101) {
                computeFrame(frame)
            }

            for (mel in 0 until 40) {
                var sum = 0.0

                for (frame in 0 until 101) {
                    sum += melBuffer[mel][frame]
                }

                val mean = sum / 101.0
                val base = mel * 101

                for (frame in 0 until 101) {
                    output[base + frame] =
                        (melBuffer[mel][frame] - mean).toFloat()
                }
            }

            return output
        }

        private fun buildHann() {
            for (i in 0 until 400) {
                hann[56 + i] =
                    0.5 - 0.5 * cos(2.0 * PI * i / 400.0)
            }
        }

        private fun hzToMel(hz: Double): Double {
            return 2595.0 * log10(1.0 + hz / 700.0)
        }

        private fun melToHz(mel: Double): Double {
            return 700.0 * (10.0.pow10(mel / 2595.0) - 1.0)
        }

        private fun buildMelFilterbank() {
            val melMin = hzToMel(0.0)
            val melMax = hzToMel(8000.0)

            val points = DoubleArray(42) { i ->
                melToHz(
                    melMin + (melMax - melMin) * i / 41.0
                )
            }

            for (mel in 0 until 40) {
                val left = points[mel]
                val center = points[mel + 1]
                val right = points[mel + 2]

                for (bin in 0 until 257) {
                    val frequency = bin * 8000.0 / 256.0

                    val rising =
                        (frequency - left) / (center - left + 1e-9)

                    val falling =
                        (right - frequency) / (right - center + 1e-9)

                    melWeights[mel][bin] =
                        max(0.0, min(rising, falling))
                }
            }
        }

        private fun Double.pow10(power: Double): Double {
            return 10.0.pow(power)
        }

        private fun buildFftTables() {
            for (i in 0 until 512) {
                var x = i
                var reversed = 0

                repeat(9) {
                    reversed = (reversed shl 1) or (x and 1)
                    x = x shr 1
                }

                bitReverse[i] = reversed
            }
        }

        private fun fft() {
            for (i in 0 until 512) {
                val j = bitReverse[i]

                if (j > i) {
                    val r = real[i]
                    real[i] = real[j]
                    real[j] = r

                    val im = imaginary[i]
                    imaginary[i] = imaginary[j]
                    imaginary[j] = im
                }
            }

            var length = 2

            while (length <= 512) {
                val half = length / 2
                val step = 512 / length

                var start = 0

                while (start < 512) {
                    for (k in 0 until half) {
                        val angle = -2.0 * PI * k / length
                        val wr = cos(angle)
                        val wi = sin(angle)

                        val a = start + k
                        val b = a + half

                        val xr =
                            real[b] * wr - imaginary[b] * wi

                        val xi =
                            real[b] * wi + imaginary[b] * wr

                        real[b] = real[a] - xr
                        imaginary[b] = imaginary[a] - xi

                        real[a] += xr
                        imaginary[a] += xi
                    }

                  
