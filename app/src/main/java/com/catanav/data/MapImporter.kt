package com.catanav.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * SAF import: copies the picked image into app-private storage (SAF grants don't
 * survive reliably, and navigation must work fully offline forever after) and records
 * its pixel dimensions WITHOUT decoding the bitmap (bounds-only decode —
 * hard-constraint #3 applies to arbitrarily large imports too).
 */
object MapImporter {

    data class Imported(val path: String, val widthPx: Int, val heightPx: Int)

    /** Blocking — call on IO. Throws on unreadable/non-image input. */
    fun import(context: Context, uri: Uri): Imported {
        val dir = File(context.filesDir, "maps").apply { mkdirs() }
        val dest = File(dir, "imported_${System.currentTimeMillis()}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { input.copyTo(it) }
        } ?: error("cannot open picked document")

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(dest.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            dest.delete()
            error("not a decodable image")
        }
        return Imported(dest.absolutePath, opts.outWidth, opts.outHeight)
    }
}
