package com.grmemby.app.watchparty

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidOpusCodecInstrumentedTest {
    @Test
    fun opusMediaCodecRoundTripProducesCompressedCorrelatedPcm() {
        val encoder = AndroidOpusCodec.createEncoder()
        val decoder = AndroidOpusCodec.createDecoder()
        assertNotNull("Device must expose an Android Opus encoder", encoder)
        assertNotNull("Device must expose an Android Opus decoder", decoder)

        encoder!!
        decoder!!
        try {
            val frameSamples = AndroidOpusCodec.SampleRate * 20 / 1_000
            val frameBytes = frameSamples * 2
            val frameCount = 80
            val source = ByteArray(frameBytes * frameCount)
            var sampleIndex = 0
            for (frame in 0 until frameCount) {
                for (i in 0 until frameSamples) {
                    val sample = (sin(2.0 * PI * 440.0 * sampleIndex / AndroidOpusCodec.SampleRate) * 12_000.0).toInt()
                    val offset = (frame * frameBytes) + (i * 2)
                    source[offset] = (sample and 0xFF).toByte()
                    source[offset + 1] = ((sample ushr 8) and 0xFF).toByte()
                    sampleIndex++
                }
            }

            var encodedBytes = 0
            val decoded = ArrayList<Byte>()
            for (frame in 0 until frameCount) {
                val pcmFrame = source.copyOfRange(frame * frameBytes, (frame + 1) * frameBytes)
                val packet = encoder.encode(pcmFrame)
                if (packet.isNotEmpty()) {
                    encodedBytes += packet.size
                    val decodedFrame = decoder.decode(packet)
                    decodedFrame.forEach { decoded.add(it) }
                }
            }

            val decodedBytes = decoded.toByteArray()
            assertTrue("Opus encoder returned no packets", encodedBytes > 0)
            assertTrue("Opus should compress below 35% of 48 kHz PCM size, got $encodedBytes / ${source.size}", encodedBytes < source.size * 0.35)
            assertTrue("Opus decoder returned no PCM", decodedBytes.size > frameBytes * 10)
            assertTrue("Decoded PCM must be 16-bit aligned", decodedBytes.size % 2 == 0)

            val sourceSamples = source.toShortArrayLe()
            val decodedSamples = decodedBytes.toShortArrayLe()
            val rms = decodedSamples.rms()
            assertTrue("Decoded PCM RMS too low: $rms", rms > 500.0)
            val correlation = maxCorrelation(sourceSamples, decodedSamples)
            assertTrue("Decoded PCM correlation too low: $correlation", correlation > 0.45)
        } finally {
            encoder.release()
            decoder.release()
        }
    }

    private fun ByteArray.toShortArrayLe(): ShortArray {
        val result = ShortArray(size / 2)
        for (i in result.indices) {
            val lo = this[i * 2].toInt() and 0xFF
            val hi = this[i * 2 + 1].toInt()
            result[i] = ((hi shl 8) or lo).toShort()
        }
        return result
    }

    private fun ShortArray.rms(): Double {
        var sum = 0.0
        for (sample in this) sum += sample.toDouble() * sample.toDouble()
        return sqrt(sum / size.coerceAtLeast(1))
    }

    private fun maxCorrelation(source: ShortArray, decoded: ShortArray): Double {
        if (source.isEmpty() || decoded.isEmpty()) return 0.0
        val maxShift = minOf(5_000, source.size - 1)
        var best = 0.0
        for (shift in 0..maxShift step 20) {
            val n = minOf(12_000, decoded.size, source.size - shift)
            if (n <= 100) continue
            var dot = 0.0
            var sourceEnergy = 0.0
            var decodedEnergy = 0.0
            for (i in 0 until n) {
                val a = source[i + shift].toDouble()
                val b = decoded[i].toDouble()
                dot += a * b
                sourceEnergy += a * a
                decodedEnergy += b * b
            }
            val denom = sqrt(sourceEnergy * decodedEnergy)
            if (denom > 0.0) best = maxOf(best, kotlin.math.abs(dot / denom))
        }
        return best
    }
}
