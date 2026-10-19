package com.phonemcp.core

import android.content.Context
import android.content.Intent
import com.phonemcp.ui.ConfirmationActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Blocks the calling (MCP worker) thread until the user taps Yes/No on an
 * on-device dialog, or 60s passes (then it's a refusal). Used to gate
 * sensitive actions: sending messages, calls, purchases, deletion, system
 * settings changes.
 */
object ConfirmationManager {
    private const val TIMEOUT_SECONDS = 60L

    private class Pending {
        val latch = CountDownLatch(1)
        val allowed = AtomicBoolean(false)
    }

    private val pending = ConcurrentHashMap<Int, Pending>()
    private val ids = AtomicInteger(1)

    /** @return true if the user explicitly allowed within the timeout. */
    fun confirm(context: Context, message: String): Boolean {
        val id = ids.getAndIncrement()
        val p = Pending()
        pending[id] = p

        val intent = Intent(context, ConfirmationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ConfirmationActivity.EXTRA_ID, id)
            putExtra(ConfirmationActivity.EXTRA_MESSAGE, message)
            putExtra(ConfirmationActivity.EXTRA_TIMEOUT, TIMEOUT_SECONDS)
        }
        context.startActivity(intent)

        return try {
            val answered = p.latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            answered && p.allowed.get()
        } catch (e: InterruptedException) {
            false
        } finally {
            pending.remove(id)
        }
    }

    fun resolve(id: Int, allowed: Boolean) {
        pending[id]?.let {
            it.allowed.set(allowed)
            it.latch.countDown()
        }
    }
}
