Kotlin

package com.dec.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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
 * Heed ONNX offline wake-word detector.
 *
 * Model: assets/wake.onnx
 * Metadata: assets/wake.json
 * Input: [1, 40, 101]
 * Output: logit, converted to probability using sigmoid.
 */
class WakeWord(
    private val context: Context,
    private val onWake: () -> Unit
) {

    companion object {
        private const val RATE = 16000
        private const val WINDOW = 16000
        private const val FFT = 512
        private const val HOP = 160
        private const val MEL = 40
        private const val FRAMES = 101
        private const val CHUNK = 1600

        private const val THRESHOLD = 0.653
        private const val RMS_THRESHOLD = -55.0
        private const val BAND_THRESHOLD = 0.15
        private const val CONSECUTIVE = 2
        private const val REFRACTORY_MS = 700L
    }

    private val lock = Any()

    @Volatile
    private var running = false

    private var worker: Thread? = null
    private var recorder: AudioRecord? = null

    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null

    private val preprocessor = Preprocessor()

    private var aboveCount = 0
    private var lastTrigger = 0L

    fun configured(): Boolean {
        return try {
            context.assets.open("wake.onnx").use { }
            context.assets.open("wake.json").use { }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun start(): String? {
        synchronized(lock) {
            if (running) return null

            if (!configured()) {
                return "Heed wake model missing from app assets."
            }

            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return "Microphone permission is required for wake detection."
            }

            try {
                val env = OrtEnvironment.getEnvironment()

                val modelBytes = context.assets
                    .open("wake.onnx")
                    .use { it.readBytes() }

                val options = OrtSession.SessionOptions()
                val newSession = env.createSession(
                    modelBytes,
                    options
                )

                val minBuffer = AudioRecord.getMinBufferSize(
                    RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (minBuffer <= 0) {
                    newSession.close()
                    return "This phone does not support the required microphone format."
                }

                val audio = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuffer, CHUNK * 4)
                )

                if (audio.state != AudioRecord.STATE_INITIALIZED) {
                    audio.release()
                    newSession.close()
                    return "Could not initialize the microphone."
                }

                environment = env
                session = newSession
                recorder = audio

                preprocessor.reset()
                aboveCount = 0
                lastTrigger = 0L

                running = true

                audio.startRecording()

                worker = Thread {
                    listenLoop(audio)
                }.apply {
                    name = "DEC-Heed-Wake"
                    isDaemon = true
                    start()
                }

                return null

            } catch (e: Exception) {
                running = false
                runCatching { recorder?.release() }
                recorder = null
                runCatching { session?.close() }
                session = null

                return "Wake detector could not start: ${e.message}"
            }
        }
    }

    fun stop() {
        val oldWorker: Thread?
        val oldRecorder: AudioRecord?

        synchronized(lock) {
            running = false
            oldWorker = worker
            oldRecorder = recorder
            worker = null
            recorder = null
        }

        runCatching { oldRecorder?.stop() }

        if (oldWorker != null && oldWorker !== Thread.currentThread()) {
            runCatching {
                oldWorker.join(1200)
            }
        }

        runCatching { oldRecorder?.release() }

        synchronized(lock) {
            preprocessor.reset()
            aboveCount = 0
        }
    }

    fun release() {
        stop()

        synchronized(lock) {
            runCatching { session?.close() }
            session = null

            runCatching { environment?.close() }
            environment = null
        }
    }

    private fun listenLoop(audio: AudioRecord) {
        val pcm = ShortArray(CHUNK)

        try {
            while (running) {
                val count = audio.read(
                    pcm,
                    0,
                    pcm.size,
                    AudioRecord.READ_BLOCKING
                )

                if (count <= 0) continue

                val samples = FloatArray(count) { i ->
                    pcm[i] / 32768.0f
                }

                preprocessor.ingest(samples)

                if (!energyGate()) {
                    aboveCount = 0
                    continue
                }

                val features = preprocessor.computeMel()
                val probability = predict(features)

                if (probability > THRESHOLD) {
                    aboveCount++
                } else {
                    aboveCount = 0
                }

                val now = System.currentTimeMillis()

                if (
                    aboveCount >= CONSECUTIVE &&
                    now - lastTrigger > REFRACTORY_MS
                ) {
                    lastTrigger = now
                    aboveCount = 0
                    onWake()
                }
            }
        } catch (_: Exception) {
            if (running) {
                running = false
            }
        }
    }

    private fun predict(features: FloatArray): Double {
        val env = environment ?: return 0.0
        val ort = session ?: return 0.0

        val input = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(features),
            longArrayOf(1, MEL.toLong(), FRAMES.toLong())
        )

        input.use {
            ort.run(mapOf("mel" to input)).use { result ->
                val output = result[0].value
                val logit = when (output) {
                    is FloatArray -> output[0].toDouble()
                    is Array<*> -> {
                        val first = output[0]
                        when (first) {
                            is FloatArray -> first[0].toDouble()
                            is Number -> first.toDouble()
                            else -> 0.0
                        }
                    }
                    is Number -> output.toDouble()
                    else -> 0.0
                }

                return 1.0 / (1.0 + exp(-logit))
            }
        }
    }

    /**
     * RMS and voice-band power gate.
     * Operates on the filtered one-second audio buffer.
     */
    private fun energyGate(): Boolean {
        val audio = preprocessor.filteredBuffer

        var sum = 0.0

        for (sample in audio) {
            sum += sample * sample
        }

        val rms = sqrt(sum / audio.size)

        val db = if (rms < 1e-9) {
            Double.NEGATIVE_INFINITY
        } else {
            20.0 * log10(rms)
        }

        if (db < RMS_THRESHOLD) return false

        var lowPass = 0.0
        var bandPower = 0.0

        val alpha = 0.936

        for (sample in audio) {
            lowPass += alpha * (sample - lowPass)
            bandPower += lowPass * lowPass
        }

        val fraction = bandPower / (sum + 1e-12)

        return fraction >= BAND_THRESHOLD
    }

    /**
     * Heed streaming preprocessing:
     * causal HPF + 50/60 Hz notch,
     * 1-second filtered ring buffer,
     * STFT + mel + CMN.
     */
    private class Preprocessor {

        val filteredBuffer = FloatArray(WINDOW)

        private val melBuffer =
            FloatArray(MEL * FRAMES)

        private var pendingSamples = 0
        private var initialized = false

        private val cascade = FilterCascade()

        private val real = DoubleArray(FFT)
        private val imag = DoubleArray(FFT)
        private val spectrum = DoubleArray(FFT / 2 + 1)

        private val output =
            FloatArray(MEL * FRAMES)

        private val window = DoubleArray(FFT)

        private val melWeights: Array<Array<Pair<Int, Double>>> =
            buildMelWeights()

        init {
            val offset = (FFT - 400) / 2

            for (n in 0 until 400) {
                window[offset + n] =
                    0.5 - 0.5 * cos(2.0 * PI * n / 400.0)
            }
        }

        fun reset() {
            filteredBuffer.fill(0f)
            melBuffer.fill(0f)
            pendingSamples = 0
            initialized = false
            cascade.reset()
        }

        fun ingest(samples: FloatArray) {
            if (samples.isEmpty()) return

            val filtered = DoubleArray(samples.size) {
                samples[it].toDouble()
            }

            cascade.process(filtered)

            val n = filtered.size

            if (n >= WINDOW) {
                val start = n - WINDOW

                for (i in 0 until WINDOW) {
                    filteredBuffer[i] =
                        filtered[start + i].toFloat()
                }

                pendingSamples = WINDOW
            } else {
                filteredBuffer.copyInto(
                    filteredBuffer,
                    0,
                    n,
                    WINDOW
                )

                for (i in 0 until n) {
                    filteredBuffer[WINDOW - n + i] =
                        filtered[i].toFloat()
                }

                pendingSamples =
                    min(WINDOW, pendingSamples + n)
            }
        }

        fun computeMel(): FloatArray {
            val advance = min(
                FRAMES,
                pendingSamples / HOP
            )

            pendingSamples -= advance * HOP

            if (!initialized || advance >= FRAMES - 3) {
                for (i in 0 until FRAMES) {
                    computeFrame(i)
                }

                initialized = true

            } else if (advance > 0) {
                shiftAndRecompute(advance)
            }

            return cmn()
        }

        private fun shiftAndRecompute(advance: Int) {
            for (m in 0 until MEL) {
                val base = m * FRAMES

                for (f in 0 until FRAMES - advance) {
                    melBuffer[base + f] =
                        melBuffer[base + f + advance]
                }
            }

            computeFrame(0)
            computeFrame(1)

            val start = max(
                2,
                FRAMES - advance - 2
            )

            for (i in start until FRAMES) {
                computeFrame(i)
            }
        }

        private fun computeFrame(frame: Int) {
            val low = frame * HOP - FFT / 2

            for (n in 0 until FFT) {
                var index = low + n

                if (index < 0) {
                    index = -index
                } else if (index >= WINDOW) {
                    index = 2 * (WINDOW - 1) - index
                }

                index = index.coerceIn(0, WINDOW - 1)

                real[n] =
                    filteredBuffer[index].toDouble() * window[n]

                imag[n] = 0.0
            }

            fft()

            for (k in spectrum.indices) {
                val r = real[k]
                val im = imag[k]

                spectrum[k] = r * r + im * im
            }

            for (m in 0 until MEL) {
                var sum = 0.0

                for ((bin, weight) in melWeights[m]) {
                    sum += weight * spectrum[bin]
                }

                melBuffer[m * FRAMES + frame] =
                    ln(max(sum, 1e-9)).toFloat()
            }
        }

        private fun cmn(): FloatArray {
            for (m in 0 until MEL) {
                val base = m * FRAMES

                var sum = 0.0

                for (f in 0 until FRAMES) {
                    sum += melBuffer[base + f]
                }

                val mean = sum / FRAMES

                for (f in 0 until FRAMES) {
                    output[base + f] =
                        (melBuffer[base + f] - mean).toFloat()
                }
            }

            return output
        }

        private fun fft() {
            var j = 0

            for (i in 1 until FFT) {
                var bit = FFT shr 1

                while ((j and bit) != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }

                j = j xor bit

                if (i < j) {
                    val tr = real[i]
                    real[i] = real[j]
                    real[j] = tr

                    val ti = imag[i]
                    imag[i] = imag[j]
                    imag[j] = ti
                }
            }

            var length = 2

            while (length <= FFT) {
                val half = length / 2
                val step = FFT / length

                for (start in 0 until FFT step length) {
                    for (k in 0 until half) {
                        val angle =
                            -2.0 * PI * k * step / FFT

                        val wr = cos(angle)
                        val wi = sin(angle)

                        val a = start + k
                        val b = a + half

                        val xr =
                            real[b] * wr - imag[b] * wi

                        val xi =
                            real[b] * wi + imag[b] * wr

                        real[b] = real[a] - xr
                        imag[b] = imag[a] - xi

                        real[a] += xr
                        imag[a] += xi
                    }
                }

                length *= 2
            }
        }

        /**
         * Same HTK mel filterbank construction as heed/audio.py.
         */
        private fun buildMelWeights():
            Array<Array<Pair<Int, Double>>> {

            fun hzToMel(hz: Double): Double =
                2595.0 * log10(1.0 + hz / 700.0)

            fun melToHz(mel: Double): Double =
                700.0 * (10.0.pow10(mel / 2595.0) - 1.0)

            val minMel = hzToMel(0.0)
            val maxMel = hzToMel(8000.0)

            val points = DoubleArray(MEL + 2) {
                melToHz(
                    minMel +
                        (maxMel - minMel) * it / (MEL + 1)
                )
            }

            return Array(MEL) { m ->
                val left = points[m]
                val center = points[m + 1]
                val right = points[m + 2]

                val pairs = ArrayList<Pair<Int, Double>>()

                for (bin in 0..FFT / 2) {
                    val hz = bin * RATE.toDouble() / FFT

                    val rising =
                        (hz - left) / (center - left + 1e-9)

                    val falling =
                        (right - hz) / (right - center + 1e-9)

                    val weight = max(
                        0.0,
                        min(rising, falling)
                    )

                    if (weight > 0.0) {
                        pairs.add(bin to weight)
                    }
                }

                pairs.toTypedArray()
            }
        }

        private fun Double.pow10(power: Double): Double =
            kotlin.math.pow(10.0, power)
    }

    /**
     * Exact Heed SOS coefficients.
     * Direct Form II Transposed, with retained streaming state.
     */
    private class FilterCascade {

        private data class Section(
            val b0: Double,
            val b1: Double,
            val b2: Double,
            val a1: Double,
            val a2: Double,
            val zi1: Double,
            val zi2: Double
        )

        private class Stage(
            val sections: List<Section>
        ) {
            val s1 = DoubleArray(sections.size)
            val s2 = DoubleArray(sections.size)
            var initialized = false
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

        fun reset() {
            for (stage in stages) {
                stage.s1.fill(0.0)
                stage.s2.fill(0.0)
                stage.initialized = false
            }
        }

        fun process(samples: DoubleArray) {
            if (samples.isEmpty()) return

            for (stage in stages) {

                if (!stage.initialized) {
                    val first = samples[0]

                    for (s in stage.sections.indices) {
                        val section = stage.sections[s]

                        stage.s1[s] = state1
                        stage.s2[s] = state2
                }
            }
        }
    }
    }
    
