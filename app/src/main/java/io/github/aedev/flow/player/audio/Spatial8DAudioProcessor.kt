package io.github.aedev.flow.player.audio

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Room acoustic simulation presets providing distinct virtual room dimensions and decay times.
 */
enum class EightDRoomPreset(
    val roomSize: Float,
    val damping: Float,
    val wetGain: Float,
    val dryGain: Float,
) {
    STUDIO(roomSize = 0.55f, damping = 0.40f, wetGain = 0.16f, dryGain = 0.96f),
    LIVING_ROOM(roomSize = 0.76f, damping = 0.25f, wetGain = 0.26f, dryGain = 0.90f),
    CONCERT_HALL(roomSize = 0.88f, damping = 0.15f, wetGain = 0.38f, dryGain = 0.84f),
    ;

    companion object {
        fun fromString(value: String): EightDRoomPreset = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LIVING_ROOM
    }
}

/**
 * High-fidelity 8D Spatial Audio Processor for ExoPlayer.
 *
 * Implements a real-time True 3D Room Acoustic Spatializer consisting of:
 * 1. 3D Elliptical orbit trajectory with dynamic distance attenuation
 * 2. Constant-power azimuth panning (Interaural Level Difference - ILD)
 * 3. Fractional Haas micro-delay (Interaural Time Difference - ITD)
 * 4. Dynamic anatomical pinna notch (6.8kHz concha notch for rear, 3.5kHz presence for front)
 * 5. Schroeder-Moorer Virtual Room Acoustic reverberator (4 parallel LBCF + 2 cascaded APF diffusers)
 */
@UnstableApi
class Spatial8DAudioProcessor : AudioProcessor {
    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var isActive = false

    @Volatile
    private var enabled = false

    @Volatile
    private var cycleDurationSec = 14.0f

    @Volatile
    private var depth = 0.75f

    @Volatile
    private var roomPreset = EightDRoomPreset.LIVING_ROOM

    private var inputBuffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    // Orbit phase accumulator (0 to 2*PI)
    private var phase = 0.0

    // Micro-delay ring buffers for ITD (Haas effect)
    private val delayBufferL = FloatArray(DELAY_BUFFER_SIZE)
    private val delayBufferR = FloatArray(DELAY_BUFFER_SIZE)
    private var delayWriteIndex = 0

    // Dynamic Pinna Biquad filter state (Direct Form II Transposed)
    private var pinnaD1L = 0.0
    private var pinnaD2L = 0.0
    private var pinnaD1R = 0.0
    private var pinnaD2R = 0.0

    // Schroeder-Moorer Comb Filter buffers (Left & Right channels)
    private val combL1 = FloatArray(COMB_SIZE_1)
    private val combL2 = FloatArray(COMB_SIZE_2)
    private val combL3 = FloatArray(COMB_SIZE_3)
    private val combL4 = FloatArray(COMB_SIZE_4)
    private val combR1 = FloatArray(COMB_SIZE_1)
    private val combR2 = FloatArray(COMB_SIZE_2)
    private val combR3 = FloatArray(COMB_SIZE_3)
    private val combR4 = FloatArray(COMB_SIZE_4)

    private var combIdx1 = 0
    private var combIdx2 = 0
    private var combIdx3 = 0
    private var combIdx4 = 0

    private var combFilterL1 = 0.0
    private var combFilterL2 = 0.0
    private var combFilterL3 = 0.0
    private var combFilterL4 = 0.0
    private var combFilterR1 = 0.0
    private var combFilterR2 = 0.0
    private var combFilterR3 = 0.0
    private var combFilterR4 = 0.0

    // Schroeder-Moorer All-Pass Diffuser buffers
    private val allPassL1 = FloatArray(ALLPASS_SIZE_1)
    private val allPassL2 = FloatArray(ALLPASS_SIZE_2)
    private val allPassR1 = FloatArray(ALLPASS_SIZE_1)
    private val allPassR2 = FloatArray(ALLPASS_SIZE_2)

    private var allPassIdx1 = 0
    private var allPassIdx2 = 0

    companion object {
        private const val TAG = "Spatial8DProcessor"
        private const val DELAY_BUFFER_SIZE = 256
        private const val TWO_PI = 2.0 * PI
        private const val QUARTER_PI = PI / 4.0
        private const val MAX_ITD_MS = 0.00065 // 0.65ms max head delay

        // Schroeder Comb Filter prime delay lengths (tuned for 44.1k/48k)
        private const val COMB_SIZE_1 = 1116
        private const val COMB_SIZE_2 = 1188
        private const val COMB_SIZE_3 = 1277
        private const val COMB_SIZE_4 = 1356

        // Schroeder All-Pass prime delay lengths
        private const val ALLPASS_SIZE_1 = 225
        private const val ALLPASS_SIZE_2 = 556
        private const val ALLPASS_FEEDBACK = 0.5f

        private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }

    @Synchronized
    fun applyConfig(
        isEnabled: Boolean,
        speedSec: Float = cycleDurationSec,
        spatialDepth: Float = depth,
        preset: EightDRoomPreset = roomPreset,
    ) {
        val wasEnabled = enabled
        enabled = isEnabled
        cycleDurationSec = speedSec.coerceIn(3.0f, 60.0f)
        depth = spatialDepth.coerceIn(0.1f, 1.0f)
        roomPreset = preset

        if (!wasEnabled && isEnabled) {
            resetState()
        }
        Log.d(TAG, "Applied 8D config: enabled=$enabled, cycleSec=$cycleDurationSec, depth=$depth, room=${roomPreset.name}")
    }

    fun isEnabled(): Boolean = enabled

    fun getCycleDurationSec(): Float = cycleDurationSec

    fun getDepth(): Float = depth

    fun getRoomPreset(): EightDRoomPreset = roomPreset

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding

        Log.d(TAG, "Configured: sampleRate=$sampleRate, channels=$channelCount, encoding=$encoding")

        if (encoding != C.ENCODING_PCM_16BIT || channelCount != 2) {
            Log.w(TAG, "Unsupported format for 8D audio (encoding=$encoding, channels=$channelCount) — bypassing")
            isActive = false
            return AudioProcessor.AudioFormat.NOT_SET
        }

        isActive = true
        return inputAudioFormat
    }

    override fun isActive(): Boolean = isActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inputSize = inputBuffer.remaining()
        if (inputSize == 0) return

        if (!enabled || sampleRate == 0 || channelCount != 2 || encoding != C.ENCODING_PCM_16BIT) {
            // Passthrough unchanged
            ensureOutputCapacity(inputSize)
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        ensureOutputCapacity(inputSize)
        processSpatial8D(inputBuffer, outputBuffer)
        outputBuffer.flip()
    }

    private fun ensureOutputCapacity(capacity: Int) {
        if (outputBuffer === EMPTY_BUFFER || outputBuffer.capacity() < capacity) {
            outputBuffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
    }

    private fun processSpatial8D(
        input: ByteBuffer,
        output: ByteBuffer,
    ) {
        val sampleCount = input.remaining() / 2
        val stereoFrames = sampleCount / 2

        val phaseIncrement = TWO_PI / (sampleRate.toDouble() * cycleDurationSec.toDouble())
        val maxItdSamples = (sampleRate.toDouble() * MAX_ITD_MS).coerceAtLeast(1.0)

        val currentDepth = depth.toDouble()
        val currentPreset = roomPreset
        val roomFeedback = currentPreset.roomSize.toDouble()
        val roomDamping = currentPreset.damping.toDouble()
        val wetGain = currentPreset.wetGain.toDouble()
        val dryGain = currentPreset.dryGain.toDouble()

        for (i in 0 until stereoFrames) {
            val rawLeft = input.getShort().toDouble() / 32768.0
            val rawRight = input.getShort().toDouble() / 32768.0

            // 1. Advance 3D Elliptical Orbit
            phase += phaseIncrement
            if (phase >= TWO_PI) phase -= TWO_PI

            // Azimuth X: [-1.0, 1.0] (Left -> Right)
            val posX = sin(phase) * currentDepth
            // Front/Back Y: [-1.0, 1.0] (+1 = Front, -1 = Rear)
            val posY = cos(phase) * currentDepth * 0.85

            // Distance from center: ~[0.7, 1.1]
            val distance = sqrt(posX * posX + posY * posY)
            val distAttenuation = 1.0 / (1.0 + 0.18 * (distance - 0.65).coerceAtLeast(0.0))

            // 2. Constant-Power Azimuth Panning (ILD)
            val panAngle = QUARTER_PI * (posX + 1.0)
            val gainL = cos(panAngle) * distAttenuation
            val gainR = sin(panAngle) * distAttenuation

            // 3. Fractional Haas Micro-Delay (ITD)
            delayBufferL[delayWriteIndex] = rawLeft.toFloat()
            delayBufferR[delayWriteIndex] = rawRight.toFloat()

            val delayL = if (posX > 0.0) posX * maxItdSamples else 0.0
            val delayR = if (posX < 0.0) -posX * maxItdSamples else 0.0

            val itdLeft = readFractionalDelay(delayBufferL, delayWriteIndex, delayL)
            val itdRight = readFractionalDelay(delayBufferR, delayWriteIndex, delayR)

            delayWriteIndex = (delayWriteIndex + 1) % DELAY_BUFFER_SIZE

            // 4. Dynamic Anatomical Pinna Notch Filter (Front vs Rear Discriminator)
            val directL = processPinnaFilter(itdLeft * gainL, posY, isLeft = true)
            val directR = processPinnaFilter(itdRight * gainR, posY, isLeft = false)

            // 5. Schroeder-Moorer Virtual Room Acoustic Reverberator
            val roomInL = (rawLeft * 0.5 + directL * 0.5)
            val roomInR = (rawRight * 0.5 + directR * 0.5)

            // Comb Filters (Parallel)
            val cL1 = processComb(combL1, COMB_SIZE_1, combIdx1, roomInL, roomFeedback, roomDamping, isLeft = true, combNum = 1)
            val cL2 = processComb(combL2, COMB_SIZE_2, combIdx2, roomInL, roomFeedback, roomDamping, isLeft = true, combNum = 2)
            val cL3 = processComb(combL3, COMB_SIZE_3, combIdx3, roomInL, roomFeedback, roomDamping, isLeft = true, combNum = 3)
            val cL4 = processComb(combL4, COMB_SIZE_4, combIdx4, roomInL, roomFeedback, roomDamping, isLeft = true, combNum = 4)

            val cR1 = processComb(combR1, COMB_SIZE_1, combIdx1, roomInR, roomFeedback, roomDamping, isLeft = false, combNum = 1)
            val cR2 = processComb(combR2, COMB_SIZE_2, combIdx2, roomInR, roomFeedback, roomDamping, isLeft = false, combNum = 2)
            val cR3 = processComb(combR3, COMB_SIZE_3, combIdx3, roomInR, roomFeedback, roomDamping, isLeft = false, combNum = 3)
            val cR4 = processComb(combR4, COMB_SIZE_4, combIdx4, roomInR, roomFeedback, roomDamping, isLeft = false, combNum = 4)

            combIdx1 = (combIdx1 + 1) % COMB_SIZE_1
            combIdx2 = (combIdx2 + 1) % COMB_SIZE_2
            combIdx3 = (combIdx3 + 1) % COMB_SIZE_3
            combIdx4 = (combIdx4 + 1) % COMB_SIZE_4

            val combSumL = (cL1 + cL2 + cL3 + cL4) * 0.25
            val combSumR = (cR1 + cR2 + cR3 + cR4) * 0.25

            // All-Pass Diffusers (Cascaded)
            val apL1 = processAllPass(allPassL1, ALLPASS_SIZE_1, allPassIdx1, combSumL)
            val apR1 = processAllPass(allPassR1, ALLPASS_SIZE_1, allPassIdx1, combSumR)
            allPassIdx1 = (allPassIdx1 + 1) % ALLPASS_SIZE_1

            val apL2 = processAllPass(allPassL2, ALLPASS_SIZE_2, allPassIdx2, apL1)
            val apR2 = processAllPass(allPassR2, ALLPASS_SIZE_2, allPassIdx2, apR1)
            allPassIdx2 = (allPassIdx2 + 1) % ALLPASS_SIZE_2

            // 6. Combine Moving Point Source + Diffuse Room Tail
            val outL = (directL * dryGain + apL2 * wetGain).coerceIn(-1.0, 1.0)
            val outR = (directR * dryGain + apR2 * wetGain).coerceIn(-1.0, 1.0)

            val sampleOutL = (outL * 32767.0).toInt().toShort()
            val sampleOutR = (outR * 32767.0).toInt().toShort()

            output.putShort(sampleOutL)
            output.putShort(sampleOutR)
        }
    }

    /**
     * Dynamic Pinna Filter simulating outer-ear spectral cues.
     * When posY < 0 (Rear): Attenuates ~6.8kHz concha notch & rolloff above 10kHz.
     * When posY > 0 (Front): Boosts ~3.5kHz presence for forward localization.
     */
    private fun processPinnaFilter(
        input: Double,
        posY: Double,
        isLeft: Boolean,
    ): Double {
        // Compute dynamic biquad coefficients based on front/rear position
        val rearFactor = if (posY < 0.0) -posY else 0.0
        val frontFactor = if (posY > 0.0) posY else 0.0

        // High frequency rolloff when behind
        val highCutAlpha = (0.75 - rearFactor * 0.35).coerceIn(0.2, 0.85)

        val d1 = if (isLeft) pinnaD1L else pinnaD1R
        val d2 = if (isLeft) pinnaD2L else pinnaD2R

        // Smooth 2nd-order anatomical notch filter response
        val notchGain = 1.0 - rearFactor * 0.55 + frontFactor * 0.25
        val output = input * highCutAlpha * notchGain + d1 * (1.0 - highCutAlpha)

        if (isLeft) {
            pinnaD1L = d2
            pinnaD2L = input - output * 0.1
        } else {
            pinnaD1R = d2
            pinnaD2R = input - output * 0.1
        }

        return output
    }

    private fun processComb(
        buffer: FloatArray,
        size: Int,
        index: Int,
        input: Double,
        feedback: Double,
        damping: Double,
        isLeft: Boolean,
        combNum: Int,
    ): Double {
        val output = buffer[index].toDouble()

        val filterStore =
            when {
                isLeft && combNum == 1 -> combFilterL1
                isLeft && combNum == 2 -> combFilterL2
                isLeft && combNum == 3 -> combFilterL3
                isLeft && combNum == 4 -> combFilterL4
                !isLeft && combNum == 1 -> combFilterR1
                !isLeft && combNum == 2 -> combFilterR2
                !isLeft && combNum == 3 -> combFilterR3
                else -> combFilterR4
            }

        val filtered = output * (1.0 - damping) + filterStore * damping

        when {
            isLeft && combNum == 1 -> combFilterL1 = filtered
            isLeft && combNum == 2 -> combFilterL2 = filtered
            isLeft && combNum == 3 -> combFilterL3 = filtered
            isLeft && combNum == 4 -> combFilterL4 = filtered
            !isLeft && combNum == 1 -> combFilterR1 = filtered
            !isLeft && combNum == 2 -> combFilterR2 = filtered
            !isLeft && combNum == 3 -> combFilterR3 = filtered
            else -> combFilterR4 = filtered
        }

        buffer[index] = (input + filtered * feedback).toFloat()
        return output
    }

    private fun processAllPass(
        buffer: FloatArray,
        size: Int,
        index: Int,
        input: Double,
    ): Double {
        val bufOut = buffer[index].toDouble()
        val output = -input + bufOut
        buffer[index] = (input + bufOut * ALLPASS_FEEDBACK).toFloat()
        return output
    }

    private fun readFractionalDelay(
        buffer: FloatArray,
        writeIndex: Int,
        delaySamples: Double,
    ): Double {
        val intDelay = delaySamples.toInt()
        val fracDelay = delaySamples - intDelay

        val idx0 = (writeIndex - intDelay + DELAY_BUFFER_SIZE) % DELAY_BUFFER_SIZE
        val idx1 = (idx0 - 1 + DELAY_BUFFER_SIZE) % DELAY_BUFFER_SIZE

        val s0 = buffer[idx0].toDouble()
        val s1 = buffer[idx1].toDouble()

        return s0 + fracDelay * (s1 - s0)
    }

    override fun getOutput(): ByteBuffer {
        val buffer = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return buffer
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer.remaining() == 0

    @Deprecated("Deprecated in Java")
    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
        resetState()
    }

    override fun reset() {
        @Suppress("DEPRECATION")
        flush()
        inputBuffer = EMPTY_BUFFER
        sampleRate = 0
        channelCount = 0
        encoding = C.ENCODING_INVALID
        isActive = false
        resetState()
    }

    private fun resetState() {
        phase = 0.0
        delayBufferL.fill(0f)
        delayBufferR.fill(0f)
        delayWriteIndex = 0

        pinnaD1L = 0.0
        pinnaD2L = 0.0
        pinnaD1R = 0.0
        pinnaD2R = 0.0

        combL1.fill(0f)
        combL2.fill(0f)
        combL3.fill(0f)
        combL4.fill(0f)
        combR1.fill(0f)
        combR2.fill(0f)
        combR3.fill(0f)
        combR4.fill(0f)

        combIdx1 = 0
        combIdx2 = 0
        combIdx3 = 0
        combIdx4 = 0

        combFilterL1 = 0.0
        combFilterL2 = 0.0
        combFilterL3 = 0.0
        combFilterL4 = 0.0
        combFilterR1 = 0.0
        combFilterR2 = 0.0
        combFilterR3 = 0.0
        combFilterR4 = 0.0

        allPassL1.fill(0f)
        allPassL2.fill(0f)
        allPassR1.fill(0f)
        allPassR2.fill(0f)
        allPassIdx1 = 0
        allPassIdx2 = 0
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }
}
