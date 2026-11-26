package com.aegis.vision

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.SharedMemory
import android.system.OsConstants
import kotlinx.coroutines.delay
import java.nio.ByteBuffer

/**
 * Memory-frugal pixel capture & Ashmem converter.
 * - Prefers active MediaProjection VirtualDisplay ("live screen share") with zero Accessibility tree calls.
 * - Reuses a single preallocated 768x768 RGB scratch buffer to prevent ~4 MB per-frame GC churn on 4 GB RAM.
 */
object ScreenVisionCapture {
    const val FRAME_DIM = 768
    const val FRAME_RGB_BYTES = FRAME_DIM * FRAME_DIM * 3

    private val scratchLock = Any()
    private val pixelScratch = IntArray(FRAME_DIM * FRAME_DIM)
    private val rgbScratch = ByteArray(FRAME_RGB_BYTES)

    @Volatile
    private var mediaProjection: MediaProjection? = null

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var imageReader: ImageReader? = null

    fun hasActiveProjection(): Boolean = mediaProjection != null && imageReader != null

    fun startProjection(context: Context, resultCode: Int, data: Intent): Boolean {
        stopProjection()
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return false
        val proj = runCatching { mgr.getMediaProjection(resultCode, data) }.getOrNull() ?: return false
        val dm = context.resources.displayMetrics
        val capW = 768
        val capH = 768
        val reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        val vDisplay = runCatching {
            proj.createVirtualDisplay(
                "AegisVisionShare",
                capW,
                capH,
                dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
        }.getOrNull()
        if (vDisplay == null) {
            reader.close()
            proj.stop()
            return false
        }
        mediaProjection = proj
        imageReader = reader
        virtualDisplay = vDisplay
        return true
    }

    fun stopProjection() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { mediaProjection?.stop() }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    suspend fun captureProjectionAshmem(): SharedMemory? {
        val reader = imageReader ?: return null
        var img = reader.acquireLatestImage()
        if (img == null) {
            delay(45L)
            img = reader.acquireLatestImage()
        }
        img ?: return null
        return try {
            val plane = img.planes[0]
            val buf: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = img.width
            val height = img.height
            val rowPadPx = (rowStride - pixelStride * width) / pixelStride
            val bmp = Bitmap.createBitmap(width + rowPadPx.coerceAtLeast(0), height, Bitmap.Config.ARGB_8888)
            buf.rewind()
            bmp.copyPixelsFromBuffer(buf)
            val cropped = if (bmp.width == FRAME_DIM && bmp.height == FRAME_DIM) {
                bmp
            } else {
                Bitmap.createBitmap(bmp, 0, 0, FRAME_DIM.coerceAtMost(bmp.width), FRAME_DIM.coerceAtMost(bmp.height)).also {
                    bmp.recycle()
                }
            }
            bitmapToSharedMemory768(cropped).also { cropped.recycle() }
        } catch (_: Throwable) {
            null
        } finally {
            img.close()
        }
    }

    /**
     * Converts any ARGB/RGB software Bitmap into a 768x768 RGB888 read-only SharedMemory region
     * using the preallocated scratch buffers (zero large per-call heap allocations).
     */
    fun bitmapToSharedMemory768(source: Bitmap): SharedMemory {
        val scaled = if (source.width == FRAME_DIM && source.height == FRAME_DIM) {
            source
        } else {
            Bitmap.createScaledBitmap(source, FRAME_DIM, FRAME_DIM, true)
        }
        return synchronized(scratchLock) {
            scaled.getPixels(pixelScratch, 0, FRAME_DIM, 0, 0, FRAME_DIM, FRAME_DIM)
            if (scaled !== source) scaled.recycle()

            var pIdx = 0
            var outIdx = 0
            var nonZeroPx = 0
            while (pIdx < pixelScratch.size) {
                val px = pixelScratch[pIdx++]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                if ((r or g or b) != 0) nonZeroPx++
                rgbScratch[outIdx++] = r.toByte()
                rgbScratch[outIdx++] = g.toByte()
                rgbScratch[outIdx++] = b.toByte()
            }
            val centerPx = pixelScratch[(FRAME_DIM / 2) * FRAME_DIM + (FRAME_DIM / 2)]
            android.util.Log.i("AegisVLM", "RGB768 packed: nonZeroPx=$nonZeroPx/${pixelScratch.size} center=0x${Integer.toHexString(centerPx)}")

            val mem = SharedMemory.create("aegis_vlm_frame", FRAME_RGB_BYTES)
            val mapped = mem.mapReadWrite()
            try {
                mapped.put(rgbScratch)
            } finally {
                SharedMemory.unmap(mapped)
            }
            mem.setProtect(OsConstants.PROT_READ)
            mem
        }
    }
}
