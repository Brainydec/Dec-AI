
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DEC offline ONNX wake-word detector.
 *
 * Model: assets/wake.onnx
 * Metadata: assets/wake.json
 *
 * ONNX input: mel [1, 40, 101]
 * ONNX output: logit [batch]
 *
 * Preprocessing:
 * High-pass + mains notch
 * Peak normalization to -3 dBFS
 * Log-mel
 * Cepstral mean normalization
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

        // Must match the exported ONNX model.
        private const val FRAMES = 101

        private const val CHUNK = 1600

        private const val THRESHOLD = 0.653
        private const val RMS_THRESHOLD = -55.0
        private const val BAND_THRESHOLD = 0.15
        private const val CONSECUTIVE = 2
        private const val REFRACTORY_MS = 700L

        private const val TARGET_PEAK = 0.707
    }

    private val lock = Any()

    @Volatile
    private var running = false

    private val wakeCallbackActive = AtomicBoolean(false)

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
                return "DEC wake model missing from app assets."
            }

            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return "Microphone permission is required."
            }

            var newSession: OrtSession? = null
            var audio: AudioRecord? = null

            try {

                val env = OrtEnvironment.getEnvironment()

                // Reuse the existing ONNX session when available.
                if (session == null) {

                    val modelBytes = context.assets
                        .open("wake.onnx")
                        .use { it.readBytes() }

                    val options = OrtSession.SessionOptions()

                    try {
                        newSession = env.createSession(
                            modelBytes,
                            options
                        )
                    } finally {
                        runCatching { options.close() }
                    }

                    session = newSession
                }

                val minBuffer = AudioRecord.getMinBufferSize(
                    RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (minBuffer <= 0) {
                    return "Unsupported microphone audio format."
                }

                audio = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuffer, CHUNK * 4)
                )

                if (audio.state != AudioRecord.STATE_INITIALIZED) {
                    audio.release()
                    return "Could not initialize DEC microphone."
                }

                environment = env
                recorder = audio

                preprocessor.reset()
                aboveCount = 0
                lastTrigger = 0L

                wakeCallbackActive.set(false)

                audio.startRecording()

                if (
                    audio.recordingState !=
                    AudioRecord.RECORDSTATE_RECORDING
                ) {
                    throw IllegalStateException(
                        "Microphone did not start recording."
                    )
                }

                running = true

                worker = Thread {
                    listenLoop(audio)
                }.apply {
                    name = "DEC-Wake-Detector"
                    isDaemon = true
                    start()
                }

                return null

            } catch (e: Exception) {

                running = false

                runCatching { audio?.stop() }
                runCatching { audio?.release() }
                runCatching { newSession?.close() }

                if (newSession != null) {
                    session = null
                }

                recorder = null
                worker = null

                return "DEC wake detector error: ${e.message}"
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

        runCatching {
            oldRecorder?.stop()
        }

        if (
            oldWorker != null &&
            oldWorker !== Thread.currentThread()
        ) {
            runCatching {
                oldWorker.join(2000)
            }
        }

        runCatching {
            oldRecorder?.release()
        }

        synchronized(lock) {
            preprocessor.reset()
            aboveCount = 0
            wakeCallbackActive.set(false)
        }
    }

    fun release() {

        stop()

        synchronized(lock) {

            runCatching {
                session?.close()
            }

            session = null

            // OrtEnvironment is process-wide.
            // Do not close it here.
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

                if (!running) break

                if (count < 0) {
                    throw IllegalStateException(
                        "Microphone read failed: $count"
                    )
                }

                if (count == 0) continue

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
                    now - lastTrigger >= REFRACTORY_MS
                ) {

                    lastTrigger = now
                    aboveCount = 0

                    if (
                        wakeCallbackActive.compareAndSet(
                            false,
                            true
                        )
                    ) {

                        try {
                            onWake()
                        } catch (e: Exception) {
                            wakeCallbackActive.set(false)
                            throw e
                        }
                    }
                }
            }

        } catch (e: Exception) {

            if (running) {

                running = false

                Dec.wakeActive.value = false

                Dec.notice.value =
                    "DEC wake detector stopped: " +
                    (e.message ?: "Microphone error")
            }
        }
    }

    private fun predict(features: FloatArray): Double {

        val env = environment ?: return 0.0
        val ort = session ?: return 0.0

        val input = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(features),
            longArrayOf(
                1,
                MEL.toLong(),
                FRAMES.toLong()
            )
        )

        input.use {

            ort.run(
                mapOf("mel" to input)
            ).use { result ->

                val output = result[0].value

                val logit = when (output) {

                    is FloatArray ->
                        output[0].toDouble()

                    is Array<*> -> {
                        when (val first = output[0]) {
                            is FloatArray ->
                                first[0].toDouble()

                            is Number ->
                                first.toDouble()

                            else -> 0.0
                        }
                    }

                    is Number ->
                        output.toDouble()

                    else -> 0.0
                }

                return 1.0 / (1.0 + exp(-logit))
            }
        }
    }

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

    private class Preprocessor {

        val filteredBuffer = FloatArray(WINDOW)

        // Separate peak-normalized audio buffer.
        private val normalizedBuffer = FloatArray(WINDOW)

        private val melBuffer = FloatArray(MEL * FRAMES)
        private val output = FloatArray(MEL * FRAMES)

        private var pendingSamples = 0
        private var initialized = false

        private val cascade = FilterCascade()

        private val real = DoubleArray(FFT)
        private val imag = DoubleArray(FFT)
        private val spectrum = DoubleArray(FFT / 2 + 1)

        private val window = DoubleArray(FFT)

        private val melWeights =
            buildMelWeights()

        init {

            val offset = (FFT - 400) / 2

            for (n in 0 until 400) {

                window[offset + n] =
                    0.5 - 0.5 *
                    cos(2.0 * PI * n / 400.0)
            }
        }

        fun reset() {

            filteredBuffer.fill(0f)
            normalizedBuffer.fill(0f)

            melBuffer.fill(0f)
            output.fill(0f)

            pendingSamples = 0
            initialized = false

            cascade.reset()
        }

        fun ingest(samples: FloatArray) {

            if (samples.isEmpty()) return

            val filtered = DoubleArray(samples.size) {
                samples[it].toDouble()
            }

            // High-pass and mains notch filtering.
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

        /**
         * Peak normalization to -3 dBFS.
         */
        private fun normalizeAudio() {

            var peak = 0.0

            for (sample in filteredBuffer) {

                peak = max(
                    peak,
                    kotlin.math.abs(sample.toDouble())
                )
            }

            val scale = if (peak > 1e-9) {
                TARGET_PEAK / peak
            } else {
                1.0
            }

            for (i in filteredBuffer.indices) {

                normalizedBuffer[i] =
                    (filteredBuffer[i] * scale).toFloat()
            }
        }

        fun computeMel(): FloatArray {

            normalizeAudio()

            val advance = min(
                FRAMES,
                pendingSamples / HOP
            )

            pendingSamples -= advance * HOP

            if (
                !initialized ||
                advance >= FRAMES - 3
            ) {

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
                    index =
                        2 * (WINDOW - 1) - index
                }

                index = index.coerceIn(
                    0,
                    WINDOW - 1
                )

                real[n] =
                    normalizedBuffer[index].toDouble() *
                    window[n]

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

        /**
         * Cepstral mean normalization.
         */
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

        private fun buildMelWeights():
            Array<Array<Pair<Int, Double>>> {

            fun hzToMel(hz: Double): Double =
                2595.0 * log10(1.0 + hz / 700.0)

            fun melToHz(mel: Double): Double =
                700.0 * (
                    Math.pow(10.0, mel / 2595.0) - 1.0
                )

            val minMel = hzToMel(0.0)
            val maxMel = hzToMel(8000.0)

            val points = DoubleArray(MEL + 2) {

                melToHz(
                    minMel +
                    (maxMel - minMel) *
                    it / (MEL + 1)
                )
            }

            return Array(MEL) { m ->

                val left = points[m]
                val center = points[m + 1]
                val right = points[m + 2]

                val pairs =
                    ArrayList<Pair<Int, Double>>()

                for (bin in 0..FFT / 2) {

                    val hz =
                        bin * RATE.toDouble() / FFT

                    val rising =
                        (hz - left) /
                        (center - left + 1e-9)

                    val falling =
                        (right - hz) /
                        (right - center + 1e-9)

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
    }

    /**
     * SOS filter cascade.
     * Direct Form II Transposed.
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

            // 100 Hz high-pass filter.
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
                    
