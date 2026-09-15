package com.catanav.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.OutputStream

/**
 * Generates a full-size placeholder plate (grid + labeled scale bar matching
 * [MapCalibration]) when assets/catacombs_map.jpg is absent, so development and tests
 * proceed; the real image is a drop-in replacement with no code change.
 *
 * The image is rendered in horizontal tile strips (Canvas draws each strip; strips are
 * streamed row-by-row into [PngStreamWriter]) so peak memory stays at one strip
 * (~7000 x 256 px), never the full plate — see hard-constraint #3.
 */
object PlaceholderMapGenerator {

    private const val TILE_HEIGHT = 256

    fun generate(out: OutputStream, widthPx: Int, heightPx: Int, metersPerPixel: Double) {
        val w = widthPx
        val h = heightPx
        val writer = PngStreamWriter(out, w, h)
        val tile = Bitmap.createBitmap(w, TILE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        val row = IntArray(w)

        val gridStepPx = (100.0 / metersPerPixel) // 100 m grid

        val bg = Paint().apply { color = Color.rgb(235, 228, 210) } // aged-paper beige
        val gridPaint = Paint().apply {
            color = Color.rgb(180, 170, 150)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        val majorGridPaint = Paint().apply {
            color = Color.rgb(140, 128, 105)
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        val textPaint = Paint().apply {
            color = Color.rgb(90, 80, 60)
            textSize = 48f
            isAntiAlias = true
        }
        val bigTextPaint = Paint().apply {
            color = Color.rgb(150, 60, 50)
            textSize = 160f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val barPaint = Paint().apply { color = Color.BLACK }

        var top = 0
        while (top < h) {
            val stripH = minOf(TILE_HEIGHT, h - top)
            canvas.save()
            canvas.translate(0f, -top.toFloat())
            // Background
            canvas.drawRect(0f, top.toFloat(), w.toFloat(), (top + stripH).toFloat(), bg)
            // Grid: one line per 100 m, heavier every 500 m, labels at majors
            var i = 0
            var x = 0.0
            while (x <= w) {
                val p = if (i % 5 == 0) majorGridPaint else gridPaint
                canvas.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), p)
                x += gridStepPx; i++
            }
            i = 0
            var y = 0.0
            while (y <= h) {
                val p = if (i % 5 == 0) majorGridPaint else gridPaint
                canvas.drawLine(0f, y.toFloat(), w.toFloat(), y.toFloat(), p)
                if (i % 5 == 0 && y > 0) {
                    canvas.drawText("${(i * 100)} m", 20f, y.toFloat() - 12f, textPaint)
                }
                y += gridStepPx; i++
            }
            // Center label
            canvas.drawText("CATANAV PLACEHOLDER PLATE", w / 2f, h / 2f, bigTextPaint)
            canvas.drawText(
                "drop assets/catacombs_map.jpg to replace",
                w / 2f, h / 2f + 180f,
                Paint(bigTextPaint).apply { textSize = 90f },
            )
            // Scale bar bottom-left: exactly 100 m at calibration scale
            val barY = h - 300f
            val barLen = gridStepPx.toFloat()
            canvas.drawRect(200f, barY, 200f + barLen, barY + 24f, barPaint)
            canvas.drawRect(200f, barY - 20f, 208f, barY + 44f, barPaint)
            canvas.drawRect(192f + barLen, barY - 20f, 200f + barLen, barY + 44f, barPaint)
            canvas.drawText(
                "100 m  (${"%.0f".format(barLen)} px)", 200f, barY - 40f,
                Paint(textPaint).apply { textSize = 64f },
            )
            canvas.restore()

            for (r in 0 until stripH) {
                tile.getPixels(row, 0, w, 0, r, w, 1)
                writer.writeRow(row)
            }
            top += stripH
        }
        writer.finish()
        tile.recycle()
    }
}
