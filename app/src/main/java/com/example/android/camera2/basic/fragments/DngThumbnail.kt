package com.reilandeubank.unprocess.fragments

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/** Reads the small preview embedded in a DNG and applies its EXIF orientation. */
internal fun loadDngThumbnail(resolver: ContentResolver, uri: Uri): Bitmap? {
    return resolver.openInputStream(uri)?.use { stream ->
        val exif = ExifInterface(stream)
        val original = exif.thumbnailBitmap ?: return@use null
        val rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) original else {
            val matrix = Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
                .also { if (it !== original) original.recycle() }
        }
    }
}
