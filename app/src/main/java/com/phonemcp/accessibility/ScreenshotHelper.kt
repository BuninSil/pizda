package com.phonemcp.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RequiresApi(Build.VERSION_CODES.R)
object ScreenshotHelper {

    /** Captures the screen as a downscaled JPEG. Returns raw bytes, or null. */
    fun captureJpeg(service: AccessibilityService, maxWidth: Int = 720, quality: Int = 70): ByteArray? {
        val bmp = captureBitmap(service) ?: return null
        return try {
            encodeJpeg(bmp, maxWidth, quality)
        } finally {
            bmp.recycle()
        }
    }

    /** Full-resolution bitmap (caller must recycle). Used for OCR so boxes map to screen coords. */
    fun captureBitmapFull(service: AccessibilityService): Bitmap? = captureBitmap(service)

    private fun captureBitmap(service: AccessibilityService): Bitmap? {
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        val executor = Executors.newSingleThreadExecutor()
        try {
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val hb = screenshot.hardwareBuffer
                            val bmp = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                            result = bmp?.copy(Bitmap.Config.ARGB_8888, false)
                            bmp?.recycle()
                            hb.close()
                        } catch (e: Exception) {
                            // leave null
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                }
            )
            latch.await(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return null
        } finally {
            executor.shutdown()
        }
        return result
    }

    private fun encodeJpeg(src: Bitmap, maxW: Int, quality: Int): ByteArray {
        val scaled = if (src.width > maxW) {
            val ratio = maxW.toFloat() / src.width
            Bitmap.createScaledBitmap(src, maxW, (src.height * ratio).toInt(), true)
        } else src
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), baos)
        if (scaled !== src) scaled.recycle()
        return baos.toByteArray()
    }
}
