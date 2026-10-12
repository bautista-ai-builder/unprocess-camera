package com.reilandeubank.unprocess.fragments

import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.math.pow

/** Small Bayer demosaicer for a portable JPEG rendition of a RAW_SENSOR frame. */
internal object RawJpegConverter {
    fun convert(
        image: Image,
        info: CameraCharacteristics,
        result: CaptureResult,
        maxDimension: Int? = null
    ): Bitmap {
        val arrangement = info.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: throw IOException("No se conoce el patrón de color RAW")
        if (arrangement !in 0..3) throw IOException("Patrón RAW no compatible")
        val white = info.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val black = info.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val plane = image.planes.first()
        val buffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val width = image.width
        val height = image.height
        val scale = if (maxDimension == null)
            ceil(sqrt(width.toDouble() * height / 8_000_000.0)).toInt().coerceAtLeast(1)
        else ceil(maxOf(width, height).toDouble() / maxDimension).toInt().coerceAtLeast(1)
        val outputWidth = (width + scale - 1) / scale
        val outputHeight = (height + scale - 1) / scale
        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val row = IntArray(outputWidth)

        fun color(x: Int, y: Int): Int {
            val evenX = (x and 1) == 0
            val evenY = (y and 1) == 0
            return when (arrangement) {
                0 -> if (evenY) { if (evenX) 0 else 1 } else { if (evenX) 1 else 2 } // RGGB
                1 -> if (evenY) { if (evenX) 1 else 0 } else { if (evenX) 2 else 1 } // GRBG
                2 -> if (evenY) { if (evenX) 1 else 2 } else { if (evenX) 0 else 1 } // GBRG
                else -> if (evenY) { if (evenX) 2 else 1 } else { if (evenX) 1 else 0 } // BGGR
            }
        }

        fun linear(x: Int, y: Int, channel: Int): Float {
            val xx = x.coerceIn(0, width - 1)
            val yy = y.coerceIn(0, height - 1)
            val offset = yy * plane.rowStride + xx * plane.pixelStride
            val raw = buffer.getShort(offset).toInt() and 0xffff
            val blackValue = black?.getOffsetForIndex(xx and 1, yy and 1) ?: 0
            val gain = when (channel) {
                0 -> gains?.getComponent(0) ?: 1f
                2 -> gains?.getComponent(3) ?: 1f
                else -> gains?.getComponent(if ((yy and 1) == 0) 1 else 2) ?: 1f
            }
            return ((raw - blackValue).toFloat() / (white - blackValue).coerceAtLeast(1) * gain)
                .coerceIn(0f, 1f)
        }

        fun sample(x: Int, y: Int, channel: Int): Float {
            if (color(x, y) == channel) return linear(x, y, channel)
            var sum = 0f
            var count = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until width && yy in 0 until height && color(xx, yy) == channel) {
                    sum += linear(xx, yy, channel)
                    count++
                }
            }
            return if (count == 0) linear(x, y, color(x, y)) else sum / count
        }

        fun byte(value: Float): Int = (value.coerceIn(0f, 1f).pow(1f / 2.2f) * 255f)
            .toInt().coerceIn(0, 255)

        for (outputY in 0 until outputHeight) {
            val y = (outputY * scale).coerceAtMost(height - 1)
            for (outputX in 0 until outputWidth) {
                val x = (outputX * scale).coerceAtMost(width - 1)
                val r = byte(sample(x, y, 0))
                val g = byte(sample(x, y, 1))
                val b = byte(sample(x, y, 2))
                row[outputX] = -0x1000000 or (r shl 16) or (g shl 8) or b
            }
            output.setPixels(row, 0, outputWidth, 0, outputY, outputWidth, 1)
        }
        return output
    }
}
