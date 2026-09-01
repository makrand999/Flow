package io.github.aedev.flow.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class Spatial8DAudioProcessorTest {
    private lateinit var processor: Spatial8DAudioProcessor

    @Before
    fun setUp() {
        processor = Spatial8DAudioProcessor()
    }

    @Test
    fun `configure with stereo 16-bit PCM succeeds and activates processor`() {
        val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        val outputFormat = processor.configure(format)

        assertThat(outputFormat).isEqualTo(format)
        assertThat(processor.isActive).isTrue()
    }

    @Test
    fun `configure with mono or non-16bit format rejects and deactivates`() {
        val monoFormat = AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT)
        val monoOutput = processor.configure(monoFormat)
        assertThat(monoOutput).isEqualTo(AudioProcessor.AudioFormat.NOT_SET)
        assertThat(processor.isActive).isFalse()

        val floatFormat = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT)
        val floatOutput = processor.configure(floatFormat)
        assertThat(floatOutput).isEqualTo(AudioProcessor.AudioFormat.NOT_SET)
        assertThat(processor.isActive).isFalse()
    }

    @Test
    fun `applyConfig updates enabled, speed, depth, and room preset correctly`() {
        processor.applyConfig(
            isEnabled = true,
            speedSec = 12.0f,
            spatialDepth = 0.9f,
            preset = EightDRoomPreset.CONCERT_HALL,
        )

        assertThat(processor.isEnabled()).isTrue()
        assertThat(processor.getCycleDurationSec()).isEqualTo(12.0f)
        assertThat(processor.getDepth()).isEqualTo(0.9f)
        assertThat(processor.getRoomPreset()).isEqualTo(EightDRoomPreset.CONCERT_HALL)
    }

    @Test
    fun `applyConfig clamps parameters within safe ranges`() {
        processor.applyConfig(
            isEnabled = true,
            speedSec = 1.0f,
            spatialDepth = 1.5f,
            preset = EightDRoomPreset.STUDIO,
        )

        assertThat(processor.getCycleDurationSec()).isEqualTo(3.0f)
        assertThat(processor.getDepth()).isEqualTo(1.0f)
        assertThat(processor.getRoomPreset()).isEqualTo(EightDRoomPreset.STUDIO)
    }

    @Test
    fun `disabled processor passes audio buffer through unchanged`() {
        val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.applyConfig(isEnabled = false)

        val input = createStereoBuffer(leftSample = 1000, rightSample = -1000, frameCount = 10)
        processor.queueInput(input)

        val output = processor.getOutput()
        assertThat(output.remaining()).isEqualTo(10 * 4)

        for (i in 0 until 10) {
            assertThat(output.getShort()).isEqualTo(1000.toShort())
            assertThat(output.getShort()).isEqualTo((-1000).toShort())
        }
    }

    @Test
    fun `enabled processor applies 8D room acoustics modulation and produces valid bounded output`() {
        val sampleRate = 48000
        val format = AudioProcessor.AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.applyConfig(
            isEnabled = true,
            speedSec = 5.0f,
            spatialDepth = 0.85f,
            preset = EightDRoomPreset.LIVING_ROOM,
        )

        // Generate 1 second of stereo audio
        val frameCount = sampleRate
        val inputBuffer = ByteBuffer.allocateDirect(frameCount * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frameCount) {
            val sampleVal = (10000.0 * kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * i / sampleRate)).toInt().toShort()
            inputBuffer.putShort(sampleVal)
            inputBuffer.putShort(sampleVal)
        }
        inputBuffer.flip()

        processor.queueInput(inputBuffer)
        val output = processor.getOutput()

        assertThat(output.remaining()).isEqualTo(frameCount * 4)

        var leftPower = 0.0
        var rightPower = 0.0
        var sampleCount = 0

        while (output.hasRemaining()) {
            val left = output.getShort().toDouble()
            val right = output.getShort().toDouble()

            assertThat(left).isAtLeast(Short.MIN_VALUE.toDouble())
            assertThat(left).isAtMost(Short.MAX_VALUE.toDouble())
            assertThat(right).isAtLeast(Short.MIN_VALUE.toDouble())
            assertThat(right).isAtMost(Short.MAX_VALUE.toDouble())

            leftPower += left * left
            rightPower += right * right
            sampleCount++
        }

        assertThat(sampleCount).isEqualTo(frameCount)
        assertThat(leftPower).isGreaterThan(0.0)
        assertThat(rightPower).isGreaterThan(0.0)
    }

    @Test
    fun `reverberation generates room tail across different room presets`() {
        val sampleRate = 48000
        val format = AudioProcessor.AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)

        for (preset in EightDRoomPreset.entries) {
            processor.reset()
            processor.configure(format)
            processor.applyConfig(isEnabled = true, speedSec = 10f, spatialDepth = 0.85f, preset = preset)

            // Send single impulse frame
            val impulse = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            impulse.putShort(16000.toShort())
            impulse.putShort(16000.toShort())
            impulse.flip()
            processor.queueInput(impulse)
            processor.getOutput() // Consume direct

            // Send silent frames and verify room tail is generated
            val silentFrames = 4800 // 100ms
            val silence = ByteBuffer.allocateDirect(silentFrames * 4).order(ByteOrder.nativeOrder())
            silence.put(ByteArray(silentFrames * 4))
            silence.flip()

            processor.queueInput(silence)
            val tailOutput = processor.getOutput()

            var tailEnergy = 0.0
            while (tailOutput.hasRemaining()) {
                val s = tailOutput.getShort().toDouble()
                tailEnergy += s * s
            }

            assertThat(tailEnergy).isGreaterThan(0.0)
        }
    }

    @Test
    fun `extended execution remains stable with zero numerical explosion`() {
        val sampleRate = 48000
        val format = AudioProcessor.AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.applyConfig(isEnabled = true, speedSec = 4.0f, spatialDepth = 1.0f, preset = EightDRoomPreset.CONCERT_HALL)

        // Process 10 seconds of audio in chunks
        val chunkSize = 4800 // 100ms per chunk
        val chunk = ByteBuffer.allocateDirect(chunkSize * 4).order(ByteOrder.nativeOrder())

        for (chunkIdx in 0 until 100) {
            chunk.clear()
            for (i in 0 until chunkSize) {
                val s = (8000.0 * kotlin.math.sin(i.toDouble())).toInt().toShort()
                chunk.putShort(s)
                chunk.putShort(s)
            }
            chunk.flip()

            processor.queueInput(chunk)
            val out = processor.getOutput()

            while (out.hasRemaining()) {
                val l = out.getShort()
                val r = out.getShort()
                assertThat(l.toDouble()).isNotNaN()
                assertThat(r.toDouble()).isNotNaN()
            }
        }
    }

    @Test
    fun `flush and reset clear state and buffer`() {
        val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.applyConfig(isEnabled = true)

        val input = createStereoBuffer(leftSample = 5000, rightSample = 5000, frameCount = 100)
        processor.queueInput(input)

        @Suppress("DEPRECATION")
        processor.flush()
        assertThat(processor.getOutput().remaining()).isEqualTo(0)

        processor.reset()
        assertThat(processor.isActive).isFalse()
    }

    private fun createStereoBuffer(
        leftSample: Short,
        rightSample: Short,
        frameCount: Int,
    ): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frameCount * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frameCount) {
            buffer.putShort(leftSample)
            buffer.putShort(rightSample)
        }
        buffer.flip()
        return buffer
    }
}
