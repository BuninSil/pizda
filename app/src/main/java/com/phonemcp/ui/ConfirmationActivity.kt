package com.phonemcp.ui

import android.app.Activity
import android.os.Bundle
import android.os.CountDownTimer
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.phonemcp.R
import com.phonemcp.core.ConfirmationManager

/**
 * Shown over whatever is on screen to ask the owner Yes/No before a sensitive
 * action. If not answered, the ConfirmationManager's own 60s timeout denies it.
 */
class ConfirmationActivity : Activity() {

    private var id: Int = -1
    private var answered = false
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        setContentView(R.layout.activity_confirmation)

        id = intent.getIntExtra(EXTRA_ID, -1)
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Разрешить действие?"
        val timeout = intent.getLongExtra(EXTRA_TIMEOUT, 60L)

        findViewById<TextView>(R.id.confirmMessage).text = message
        val countdown = findViewById<TextView>(R.id.confirmCountdown)

        findViewById<Button>(R.id.allowButton).setOnClickListener { finishWith(true) }
        findViewById<Button>(R.id.denyButton).setOnClickListener { finishWith(false) }

        timer = object : CountDownTimer(timeout * 1000, 1000) {
            override fun onTick(ms: Long) {
                countdown.text = "Автоотказ через ${ms / 1000} сек"
            }
            override fun onFinish() { finishWith(false) }
        }.start()
    }

    private fun finishWith(allowed: Boolean) {
        if (answered) return
        answered = true
        timer?.cancel()
        ConfirmationManager.resolve(id, allowed)
        finish()
    }

    override fun onDestroy() {
        // If dismissed some other way, treat as denial.
        if (!answered) ConfirmationManager.resolve(id, false)
        timer?.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_TIMEOUT = "timeout"
    }
}
