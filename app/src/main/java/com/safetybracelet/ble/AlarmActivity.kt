package com.safetybracelet.ble

import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class AlarmActivity : Activity() {

    private var player: MediaPlayer? = null
    private var oldVolume = -1
    private val handler = Handler(Looper.getMainLooper())
    private var secondsLeft = (MonitorService.CALL_DELAY_MS / 1000).toInt()
    private var isTest = false
    private lateinit var countdown: TextView
    private var callName = ""
    private var callNumber = ""

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            stopSound()
            countdown.text = "Calling $callName..."
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(MonitorService.NOTIF_ALARM)

        isTest = intent.getBooleanExtra("test", false)
        val message = intent.getStringExtra("message") ?: "Emergency alert"
        Store.contacts(this).firstOrNull()?.let { callName = it.first; callNumber = it.second }

        buildUi(message)
        startSound()
        startVibration()

        val filter = IntentFilter(AlarmState.ACTION_STOP_ALARM)
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(stopReceiver, filter)

        if (!isTest && callNumber.isNotEmpty()) handler.post(tick)
        else countdown.text = if (isTest) "Test mode: no call will be made" else "No emergency contact saved"
    }

    @Suppress("DEPRECATION")
    private fun buildUi(message: String) {
        fun dp(v: Int) = Ui.dp(this, v)
        val white = Color.WHITE
        val soft = 0xCCFFFFFF.toInt()
        window.statusBarColor = Ui.ALARM_BG
        window.navigationBarColor = Ui.ALARM_BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.ALARM_BG)
            setPadding(dp(28), dp(64), dp(28), dp(32))
        }
        root.addView(Ui.text(this, "Emergency", 42f, white, true))
        root.addView(Ui.text(this, message, 18f, soft).apply { setPadding(0, dp(12), 0, 0) })

        // flexible space pushes the controls to the bottom
        root.addView(android.view.View(this), LinearLayout.LayoutParams(0, 0, 1f))

        countdown = Ui.text(this, "", 17f, white, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = Ui.round(this@AlarmActivity, 0x29FFFFFF, 16)
        }
        root.addView(countdown, Ui.lp(this, Ui.MATCH, Ui.WRAP, b = 20))

        root.addView(Ui.button(this, "Stop alarm", white, Ui.ALARM_BG) { stopSound() },
            Ui.lp(this, Ui.MATCH, Ui.WRAP, b = 12))
        root.addView(Ui.button(this, "Cancel call", Ui.ALARM_BG, white, white) {
            AlarmState.callCancelled = true
            handler.removeCallbacks(tick)
            countdown.text = "Call cancelled"
        }, Ui.lp(this, Ui.MATCH, Ui.WRAP, b = 4))
        root.addView(Ui.link(this, "Close", soft) { finish() },
            Ui.lp(this, Ui.WRAP, Ui.WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
        setContentView(root)
    }

    /** Display only: the service places the call, so it works even if this screen never opens. */
    private val tick = object : Runnable {
        override fun run() {
            if (AlarmState.callCancelled) return
            if (secondsLeft <= 0) { countdown.text = "Calling $callName..."; return }
            countdown.text = "Calling $callName in $secondsLeft s"
            secondsLeft--
            handler.postDelayed(this, 1000)
        }
    }

    private fun startSound() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            oldVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmActivity, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 300), 0))
        } catch (_: Exception) {}
    }

    private fun stopSound() {
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
        try {
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
            if (oldVolume >= 0) {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .setStreamVolume(AudioManager.STREAM_ALARM, oldVolume, 0)
                oldVolume = -1
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        stopSound()
        super.onDestroy()
    }
}
