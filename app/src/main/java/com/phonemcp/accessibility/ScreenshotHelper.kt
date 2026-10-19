package com.phonemcp.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RequiresApi(Build.VERSION_CODES.R)
object ScreenshotHelper {

    /** Returns a PNG, base64-encoded (no wrapping), downscaled to keep it small. */
    fun capture(service: AccessibilityService): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
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
                            val software = bmp?.copy(Bitmap.Config.ARGB_8888, false)
                            bmp?.recycle()
                            hb.close()
                            if (software != null) {
                                result = encode(software)
                                software.recycle()
                            }
                        } catch (e: Exception) {
                            // leave result null
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

    private fun encode(src: Bitmap): String {
        val maxW = 800
        val scaled = if (src.width > maxW) {
            val ratio = maxW.toFloat() / src.width
            Bitmap.createScaledBitmap(src, maxW, (src.height * ratio).toInt(), true)
        } else src
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 90, baos)
        if (scaled !== src) scaled.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }
}
