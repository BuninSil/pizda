package com.phonemcp.service

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.phonemcp.core.AppState

/** Routes the notification action buttons (Stop / Copy). */
class ServiceActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP -> {
                context.stopService(Intent(context, McpForegroundService::class.java))
            }
            ACTION_COPY -> {
                val url = AppState.connectorUrl
                if (url != null) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("MCP connector URL", url))
                    Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.phonemcp.STOP"
        const val ACTION_COPY = "com.phonemcp.COPY"
    }
}
