package com.phonemcp.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import kotlin.math.abs

/**
 * A small draggable floating button ("Позвать Claude") that stays over other
 * apps. Tapping it asks the service to start the Claude voice call.
 */
class OverlayManager(private val context: Context) {

    private var wm: WindowManager? = null
    private var view: Button? = null

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun show(onClick: () -> Unit) {
        if (!canDraw() || view != null) return
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = windowManager

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 300
        }

        val button = Button(context).apply {
            text = "Claude"
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            alpha = 0.9f
        }

        // Drag + click handling.
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var dragging = false
        button.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) dragging = true
                    params.x = startX + dx
                    params.y = startY + dy
                    windowManager.updateViewLayout(button, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) onClick()
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(button, params)
            view = button
        } catch (e: Exception) {
            view = null
        }
    }

    fun hide() {
        try { view?.let { wm?.removeView(it) } } catch (_: Exception) {}
        view = null
    }
}
