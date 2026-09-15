package com.catanav.map

import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Minimal streaming PNG encoder (8-bit truecolor RGB, no interlace).
 *
 * Exists because Android has no streaming image encoder, and hard-constraint #3 forbids
 * ever materializing the full 7000x7000 map in memory — including while GENERATING the
 * dev placeholder. Rows are fed top-to-bottom and deflated incrementally; memory use is
 * O(row), not O(image). Pure JVM (java.util.zip), so it is unit-testable off-device.
 */
class PngStreamWriter(
    out: OutputStream,
    private val width: Int,
    private val height: Int,
) {
    private val dos = DataOutputStream(out)
    private val deflater = Deflater(Deflater.BEST_SPEED)
    private val deflateBuf = ByteArray(64 * 1024)
    private var pendingIdat = java.io.ByteArrayOutputStream(128 * 1024)
    private val rowBuf = ByteArray(1 + width * 3)
    private var rowsWritten = 0

    init {
        require(width > 0 && height > 0)
        dos.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val ihdr = ByteArray(13)
        writeIntBE(ihdr, 0, width)
        writeIntBE(ihdr, 4, height)
        ihdr[8] = 8   // bit depth
        ihdr[9] = 2   // color type: truecolor RGB
        ihdr[10] = 0  // compression
        ihdr[11] = 0  // filter
        ihdr[12] = 0  // interlace
        writeChunk("IHDR", ihdr)
    }

    /** [argbRow] must hold exactly [width] packed ARGB ints (alpha ignored). */
    fun writeRow(argbRow: IntArray) {
        require(argbRow.size == width) { "row size ${argbRow.size} != width $width" }
        check(rowsWritten < height) { "too many rows" }
        rowBuf[0] = 0 // filter: None
        var o = 1
        for (px in argbRow) {
            rowBuf[o++] = ((px shr 16) and 0xFF).toByte()
            rowBuf[o++] = ((px shr 8) and 0xFF).toByte()
            rowBuf[o++] = (px and 0xFF).toByte()
        }
        deflater.setInput(rowBuf.copyOf())
        drainDeflater(finish = false)
        rowsWritten++
    }

    fun finish() {
        check(rowsWritten == height) { "wrote $rowsWritten of $height rows" }
        deflater.finish()
        drainDeflater(finish = true)
        if (pendingIdat.size() > 0) flushIdat()
        writeChunk("IEND", ByteArray(0))
        dos.flush()
        deflater.end()
    }

    private fun drainDeflater(finish: Boolean) {
        while (true) {
            val n = deflater.deflate(deflateBuf)
            if (n <= 0) {
                if (!finish || deflater.finished()) break
                continue
            }
            pendingIdat.write(deflateBuf, 0, n)
            if (pendingIdat.size() >= 256 * 1024) flushIdat()
        }
    }

    private fun flushIdat() {
        writeChunk("IDAT", pendingIdat.toByteArray())
        pendingIdat = java.io.ByteArrayOutputStream(128 * 1024)
    }

    private fun writeChunk(type: String, data: ByteArray) {
        dos.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        dos.write(typeBytes)
        dos.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        dos.writeInt(crc.value.toInt())
    }

    private fun writeIntBE(buf: ByteArray, at: Int, v: Int) {
        buf[at] = (v ushr 24).toByte()
        buf[at + 1] = (v ushr 16).toByte()
        buf[at + 2] = (v ushr 8).toByte()
        buf[at + 3] = v.toByte()
    }
}
