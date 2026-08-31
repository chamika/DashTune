package com.chamika.dashtune.fake

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.PI
import kotlin.math.sin

/**
 * Media bytes for the simulated server.
 *
 * The audio is a WAV generated in code rather than a checked-in binary: ExoPlayer's
 * `WavExtractor` decodes it natively, `DefaultExtractorsFactory` runs with ID3 parsing
 * disabled in [com.chamika.dashtune.DashTuneMusicService], so sniffing is unambiguous, and
 * the repo stays free of an opaque fixture blob.
 */
object FakeAudio {

    const val DURATION_MS = 3_000L
    private const val SAMPLE_RATE = 44_100
    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16

    /** A 440 Hz tone. Built once — every track streams the same bytes. */
    val wav: ByteArray by lazy { buildWav() }

    private fun buildWav(): ByteArray {
        val frames = (SAMPLE_RATE * DURATION_MS / 1000).toInt()
        val dataSize = frames * CHANNELS * (BITS_PER_SAMPLE / 8)
        val out = ByteArrayOutputStream(44 + dataSize)

        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) = out.write(
            byteArrayOf(
                (v and 0xFF).toByte(),
                ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(),
                ((v shr 24) and 0xFF).toByte(),
            )
        )
        fun le16(v: Int) = out.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))

        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        ascii("RIFF"); le32(36 + dataSize); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(CHANNELS)
        le32(SAMPLE_RATE); le32(byteRate)
        le16(CHANNELS * BITS_PER_SAMPLE / 8); le16(BITS_PER_SAMPLE)
        ascii("data"); le32(dataSize)

        for (i in 0 until frames) {
            val sample = (sin(2.0 * PI * 440.0 * i / SAMPLE_RATE) * 12_000).toInt()
            le16(sample)
        }
        return out.toByteArray()
    }

    /** A 2x2 opaque PNG, enough for AlbumArtContentProvider to return real bytes. */
    val png: ByteArray by lazy { buildPng() }

    private fun buildPng(): ByteArray {
        val width = 2
        val height = 2
        // One filter byte per scanline, then RGB triples.
        val raw = ByteArrayOutputStream()
        repeat(height) {
            raw.write(0)
            repeat(width) { raw.write(byteArrayOf(0x30, 0x5A, 0x8C.toByte())) }
        }
        val compressed = deflate(raw.toByteArray())

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        val ihdr = ByteArrayOutputStream().apply {
            write(intBytes(width)); write(intBytes(height))
            write(8)    // bit depth
            write(2)    // colour type: truecolour
            write(0); write(0); write(0)
        }.toByteArray()

        out.write(chunk("IHDR", ihdr))
        out.write(chunk("IDAT", compressed))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun intBytes(v: Int) = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(typeBytes); update(data) }.value.toInt()
        return ByteArrayOutputStream().apply {
            write(intBytes(data.size)); write(typeBytes); write(data); write(intBytes(crc))
        }.toByteArray()
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater().apply { setInput(input); finish() }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return out.toByteArray()
    }
}
