package com.phonemcp.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single source of truth for the running state and the current connector URL.
 * The service writes to it; the UI and notification read from it and get
 * callbacks on the main thread.
 */
object AppState {
    interface Listener {
        fun onStateChanged()
    }

    @Volatile var running: Boolean = false
        private set

    /** Full connector URL incl. token, e.g. https://<worker>/<token>/mcp */
    @Volatile var connectorUrl: String? = null
        private set

    /** Whether the phone's relay WebSocket to the Worker is currently connected. */
    @Volatile var relayConnected: Boolean = false

    /** Human-readable status line for the UI/notification. */
    @Volatile var status: String = "Остановлено"
        private set

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    fun setRunning(value: Boolean) {
        running = value
        if (!value) connectorUrl = null
        notifyChanged()
    }

    fun setStatus(value: String) {
        status = value
        notifyChanged()
    }

    fun setConnectorUrl(url: String?) {
        connectorUrl = url
        notifyChanged()
    }

    fun notifyLogChanged() = notifyChanged()

    private fun notifyChanged() {
        main.post { listeners.forEach { it.onStateChanged() } }
    }
}
