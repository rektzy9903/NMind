package com.claudecodesetup.services

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class DeviceControlService : AccessibilityService() {

    companion object {
        private const val TAG = "DeviceControlService"
        var instance: DeviceControlService? = null

        fun isAvailable() = instance != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "DeviceControlService connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * Silently captures the screen — no casting dialog, no user interaction.
     * Requires Android 11+ (API 30) and the accessibility service to be enabled.
     * Saves to filesDir/overlay_screenshot.jpg and broadcasts ACTION_SCREENSHOT_READY.
     */
    fun takeScreenshot(onDone: (path: String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onDone(null)
            return
        }
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            Executors.newSingleThreadExecutor(),
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(
                        result.hardwareBuffer, result.colorSpace
                    )
                    result.hardwareBuffer.close()

                    if (bitmap == null) { onDone(null); return }

                    try {
                        val out  = File(filesDir, "overlay_screenshot.jpg")
                        FileOutputStream(out).use { fos ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                        }
                        bitmap.recycle()
                        Handler(Looper.getMainLooper()).post { onDone(out.absolutePath) }
                    } catch (e: Exception) {
                        Log.e(TAG, "screenshot save failed", e)
                        Handler(Looper.getMainLooper()).post { onDone(null) }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "takeScreenshot failed, errorCode=$errorCode")
                    Handler(Looper.getMainLooper()).post { onDone(null) }
                }
            }
        )
    }
}
