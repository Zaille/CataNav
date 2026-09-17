package com.catanav.map

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * Validates the streaming PNG encoder at the format level: signature, IHDR fields,
 * per-chunk CRCs, and a full inflate of the IDAT stream back to the exact RGB rows
 * that were written (filter byte 0 per row).
 */
class PngStreamWriterTest {

    private data class Chunk(val type: String, val data: ByteArray)

    private fun parseChunks(png: ByteArray): List<Chunk> {
        val sig = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        assertArrayEquals("PNG signature", sig, png.copyOfRange(0, 8))
        val chunks = mutableListOf<Chunk>()
        val buf = ByteBuffer.wrap(png, 8, png.size - 8)
        while (buf.remaining() >= 12) {
            val len = buf.int
            val typeBytes = ByteArray(4).also { buf.get(it) }
            val data = ByteArray(len).also { buf.get(it) }
            val crc = buf.int
            val check = CRC32().apply { update(typeBytes); update(data) }
            assertEquals("CRC of ${String(typeBytes)}", check.value.toInt(), crc)
            chunks.add(Chunk(String(typeBytes, Charsets.US_ASCII), data))
        }
        return chunks
    }

    @Test
    fun roundTrip_smallImage_pixelPerfect() {
        val w = 33
        val h = 17
        val out = ByteArrayOutputStream()
        val writer = PngStreamWriter(out, w, h)
        val rows = Array(h) { y ->
            IntArray(w) { x ->
                val r = (x * 7) and 0xFF
                val g = (y * 13) and 0xFF
                val b = (x * y) and 0xFF
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        rows.forEach(writer::writeRow)
        writer.finish()

        val chunks = parseChunks(out.toByteArray())
        assertEquals("IHDR", chunks.first().type)
        assertEquals("IEND", chunks.last().type)

        val ihdr = ByteBuffer.wrap(chunks.first().data)
        assertEquals(w, ihdr.int)
        assertEquals(h, ihdr.int)
        assertEquals(8, ihdr.get().toInt())   // bit depth
        assertEquals(2, ihdr.get().toInt())   // color type: truecolor RGB
        assertEquals(0, ihdr.get().toInt())   // compression
        assertEquals(0, ihdr.get().toInt())   // filter method
        assertEquals(0, ihdr.get().toInt())   // no interlace

        // Inflate all IDAT data and verify every row byte-for-byte.
        val idat = ByteArrayOutputStream()
        chunks.filter { it.type == "IDAT" }.forEach { idat.write(it.data) }
        assertTrue("has IDAT data", idat.size() > 0)
        val inflater = Inflater()
        inflater.setInput(idat.toByteArray())
        val raw = ByteArray(h * (1 + w * 3))
        var off = 0
        while (!inflater.finished() && off < raw.size) {
            off += inflater.inflate(raw, off, raw.size - off)
        }
        assertEquals("decompressed size", raw.size, off)

        for (y in 0 until h) {
            val rowStart = y * (1 + w * 3)
            assertEquals("filter byte row $y", 0, raw[rowStart].toInt())
            for (x in 0 until w) {
                val px = rows[y][x]
                val o = rowStart + 1 + x * 3
                assertEquals("R($x,$y)", (px shr 16) and 0xFF, raw[o].toInt() and 0xFF)
                assertEquals("G($x,$y)", (px shr 8) and 0xFF, raw[o + 1].toInt() and 0xFF)
                assertEquals("B($x,$y)", px and 0xFF, raw[o + 2].toInt() and 0xFF)
            }
        }
    }

    @Test(expected = IllegalStateException::class)
    fun finish_beforeAllRows_throws() {
        val writer = PngStreamWriter(ByteArrayOutputStream(), 4, 4)
        writer.writeRow(IntArray(4))
        writer.finish()
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongRowWidth_throws() {
        PngStreamWriter(ByteArrayOutputStream(), 4, 4).writeRow(IntArray(5))
    }
}
