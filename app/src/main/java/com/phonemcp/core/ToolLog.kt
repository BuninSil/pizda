package com.phonemcp.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory ring buffer of tool invocations, shown in the UI. */
object ToolLog {
    data class Entry(val time: Long, val tool: String, val detail: String, val ok: Boolean)

    private const val MAX = 300
    private val entries = CopyOnWriteArrayList<Entry>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(tool: String, detail: String, ok: Boolean) {
        entries.add(0, Entry(System.currentTimeMillis(), tool, detail, ok))
        while (entries.size > MAX) entries.removeAt(entries.size - 1)
        AppState.notifyLogChanged()
    }

    fun snapshot(): List<Entry> = entries.toList()

    fun formatted(limit: Int = 40): String {
        val list = entries.take(limit)
        if (list.isEmpty()) return "(пусто)"
        return list.joinToString("\n") { e ->
            val mark = if (e.ok) "✓" else "✗"
            "${fmt.format(Date(e.time))} $mark ${e.tool} ${e.detail}"
        }
    }
}
