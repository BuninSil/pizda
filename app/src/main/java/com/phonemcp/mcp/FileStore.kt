package com.phonemcp.mcp

import android.content.Context
import java.io.File

/**
 * Holds short-lived files (e.g. screenshots) that the MCP server can serve over
 * the tunnel at /<token>/file/<name>, so tools can return a link instead of a
 * big inline base64 blob.
 */
object FileStore {
    private const val DIR = "served"
    private const val MAX_FILES = 20

    fun dir(context: Context): File = File(context.cacheDir, DIR).apply { mkdirs() }

    /** Writes bytes under a unique name and returns the file name. */
    fun put(context: Context, prefix: String, ext: String, bytes: ByteArray): String {
        val d = dir(context)
        // Trim old files so the cache doesn't grow without bound.
        d.listFiles()?.sortedBy { it.lastModified() }?.let { files ->
            if (files.size >= MAX_FILES) files.take(files.size - MAX_FILES + 1).forEach { it.delete() }
        }
        val name = "$prefix-${System.currentTimeMillis()}.$ext"
        File(d, name).writeBytes(bytes)
        return name
    }

    /** Resolves a served file by name, guarding against path traversal. */
    fun get(context: Context, name: String): File? {
        if (name.contains("/") || name.contains("..")) return null
        val f = File(dir(context), name)
        return if (f.exists() && f.isFile) f else null
    }

    fun contentType(name: String): String = when {
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".png") -> "image/png"
        else -> "application/octet-stream"
    }
}
