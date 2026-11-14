package com.safetybracelet.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telecom.TelecomManager
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * Keeps a Bluetooth LE connection to the bracelet. When the bracelet reports an EMERGENCY
 * this service shows the alarm, sends the SMS and places the call. No internet is used.
 */
@SuppressLint("MissingPermission")
class MonitorService : Service() {

    @Volatile private var running = false
    @Volatile private var connected = false
    private var gatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastEmergencyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        try {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            ServiceCompat.startForeground(this, NOTIF_STATUS, statusNotification(Store.status(this)), type)
        } catch (e: Exception) {
            Store.setStatus(this, "Could not start: allow Bluetooth permission")
            Store.setMonitoring(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            Store.setLinked(this, false)
            Store.setDevice(this, Store.DEV_UNKNOWN)
            val f = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(btReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(btReceiver, f)
            connect()
        }

        when (intent?.action) {

            ACTION_ARM -> sendCommand("ACTIVATE")
            ACTION_DISARM -> sendCommand("DEACTIVATE")

        }

        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
        closeGatt()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_LINK)
        Store.setStatus(this, "Not monitoring")
        Store.setLinked(this, false)
        Store.setDevice(this, Store.DEV_UNKNOWN)
        super.onDestroy()
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_OFF -> {
                    connected = false
                    Store.setLinked(this@MonitorService, false)
                    Store.setDevice(this@MonitorService, Store.DEV_UNKNOWN)
                    closeGatt()
                    setStatus("Bluetooth is off. Turn it on.")
                    scheduleLinkLost()
                }
                BluetoothAdapter.STATE_ON -> connect()
            }
        }
    }

    // ------------------------------------------------------------------ connection

    private fun connect() {
        if (!running) return
        val addr = Store.braceletAddress(this)
        if (addr == null) { setStatus("No pendant selected"); return }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { setStatus("Bluetooth is off. Turn it on."); return }

        closeGatt()
        setStatus("Looking for pendant...")
        try {
            val dev = adapter.getRemoteDevice(addr)
            // autoConnect = true: Android keeps trying in the background until the pendant is in range.
            gatt = dev.connectGatt(this, true, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            setStatus("Bluetooth error, retrying...")
            handler.removeCallbacks(reconnect)
            handler.postDelayed(reconnect, 5000)
        }
    }

    private fun closeGatt() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    private val reconnect = Runnable { if (running && !connected) connect() }

    private val linkLost = Runnable {
        val n = NotificationCompat.Builder(this, CH_LINK)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Pendant not connected")
            .setContentText("The pendant has been out of reach for 2 minutes. Alerts will not arrive.")
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_LINK, n)
    }

    private fun scheduleLinkLost() {
        handler.removeCallbacks(linkLost)
        handler.postDelayed(linkLost, 120000)
    }

    private fun cancelLinkLost() {
        handler.removeCallbacks(linkLost)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_LINK)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                handler.post {
                    handler.removeCallbacks(reconnect)
                    cancelLinkLost()
                    setStatus("Connected, setting up...")
                }
                try { g.discoverServices() } catch (_: Exception) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                Store.setLinked(this@MonitorService, false)
                Store.setDevice(this@MonitorService, Store.DEV_UNKNOWN)
                handler.post {
                    if (!running) return@post
                    setStatus("Pendant out of reach, reconnecting...")
                    scheduleLinkLost()
                    handler.removeCallbacks(reconnect)
                    handler.postDelayed(reconnect, 4000)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(Ble.SERVICE)?.getCharacteristic(Ble.CHAR)
            if (ch == null) {
                setStatus("That device is not a Halo pendant")
                return
            }
            enableNotifications(g, ch)
            Store.setLinked(this@MonitorService, true)
            setStatus("Pendant connected and watching", Store.LEVEL_OK)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {

            val ch = g.getService(Ble.SERVICE)?.getCharacteristic(Ble.CHAR) ?: return

            try { g.readCharacteristic(ch) } catch (_: Exception) {}

        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) {

            if (ch.uuid == Ble.CHAR && status == BluetoothGatt.GATT_SUCCESS) handler.post { handleRead(value) }

        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {

            if (Build.VERSION.SDK_INT < 33 && ch.uuid == Ble.CHAR && status == BluetoothGatt.GATT_SUCCESS) {

                @Suppress("DEPRECATION") val v = ch.value ?: return
                handler.post { handleRead(v) }

            }

        }

        // Android 13+ delivers the value here
        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (ch.uuid == Ble.CHAR) handler.post { handlePayload(value) }
        }

        // Android 12 and older deliver it here
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && ch.uuid == Ble.CHAR) {
                @Suppress("DEPRECATION") val v = ch.value ?: return
                handler.post { handlePayload(v) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        try {
            g.setCharacteristicNotification(ch, true)
            val d = ch.getDescriptor(Ble.CCCD) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun sendCommand(text: String) {

        val g = gatt ?: return
        val ch = g.getService(Ble.SERVICE)?.getCharacteristic(Ble.CHAR) ?: return
        val data = text.toByteArray(Charsets.UTF_8)

        try {

            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                ch.value = data
                g.writeCharacteristic(ch)
            }

        } catch (_: Exception) {}

    }

    // ------------------------------------------------------------------ bracelet messages

    private fun handleRead(bytes: ByteArray) {

        when (String(bytes, Charsets.UTF_8).trim()) {

            "A" -> Store.setDevice(this, Store.DEV_ARMED)
            "D" -> Store.setDevice(this, Store.DEV_DISARMED)

        }

    }

    private fun handlePayload(bytes: ByteArray) {

        if (bytes.isEmpty()) return

        when (String(bytes, Charsets.UTF_8).trim()) {

            "A" -> {
                Store.setDevice(this, Store.DEV_ARMED)
                setStatus("Pendant armed and watching", Store.LEVEL_OK)
            }

            "D" -> {
                Store.setDevice(this, Store.DEV_DISARMED)
                cancelWarn()
                setStatus("Pendant connected and watching", Store.LEVEL_OK)
            }

            "WARN" -> {
                Store.setDevice(this, Store.DEV_WARN)
                setStatus("Check in: press the safe button on the pendant", Store.LEVEL_ALERT)
                onWarn()
            }

            "SAFE" -> {
                Store.setDevice(this, Store.DEV_ARMED)
                cancelWarn()
                setStatus("Pendant armed and watching", Store.LEVEL_OK)
            }

            "E" -> {

                Store.setDevice(this, Store.DEV_SOS)
                cancelWarn()

                val now = System.currentTimeMillis()

                if (now - lastEmergencyAt > 25000) {
                    lastEmergencyAt = now
                    onEmergency("The check-in timer ran out and the wearer did not respond.")
                }

            }

            "DURESS" -> {

                cancelWarn()

                val now = System.currentTimeMillis()

                if (now - lastEmergencyAt > 25000) {
                    lastEmergencyAt = now
                    onDuress()
                }

            }

        }

    }

    // ------------------------------------------------------------------ emergency

    private fun time() = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        .format(java.util.Date())

    private fun onEmergency(message: String) {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bracelet:emergency")
            .acquire(60000)
        setStatus("EMERGENCY received at ${time()}", Store.LEVEL_ALERT)
        AlarmState.callCancelled = false
        showAlarm(message)
        // SMS and call run in the service, so they do not depend on the alarm screen opening.
        Thread { sendSmsToContacts("EMERGENCY from safety pendant: $message") }.start()
        Thread { callAfterDelay() }.start()
    }

    private fun onDuress() {

        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bracelet:duress")
            .acquire(60000)

        Thread {
            sendSmsToContacts(
                "SILENT ALERT from safety pendant: the wearer pressed the duress button and may be in danger " +
                    "and unable to speak. Avoid calling her. Contact help or go to her location."
            )
        }.start()

    }

    private fun onWarn() {

        beep(5)
        vibrate(longArrayOf(0, 500, 250, 500, 250, 500))

        val pi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(this, CH_WARN)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Check in now")
            .setContentText("Press the safe button on your pendant or an SOS will be sent.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_WARN, n)

    }

    private fun cancelWarn() {

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_WARN)

    }

    private fun beep(count: Int) {

        val tone = try {
            ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (_: Exception) {
            return
        }

        for (k in 0 until count) {
            handler.postDelayed({ try { tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400) } catch (_: Exception) {} }, k * 700L)
        }

        handler.postDelayed({ try { tone.release() } catch (_: Exception) {} }, count * 700L + 800L)

    }

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray) {

        try {

            val v = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            v.vibrate(VibrationEffect.createWaveform(pattern, -1))

        } catch (_: Exception) {}

    }

    private fun showAlarm(message: String) {
        val i = Intent(this, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("message", message)
        val pi = PendingIntent.getActivity(
            this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CH_ALARM)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SOS from your pendant")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ALARM, n)
        try { startActivity(i) } catch (_: Exception) { /* full-screen notification covers this */ }
    }

    private fun has(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun callAfterDelay() {
        val first = Store.contacts(this).firstOrNull() ?: return
        try { Thread.sleep(CALL_DELAY_MS) } catch (_: InterruptedException) { return }
        if (AlarmState.callCancelled) return
        sendBroadcast(Intent(AlarmState.ACTION_STOP_ALARM).setPackage(packageName))
        placeCall(first.second)
    }

    private fun placeCall(number: String) {
        if (!has(Manifest.permission.CALL_PHONE)) return
        val uri = Uri.fromParts("tel", number, null)
        try {
            (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).placeCall(uri, Bundle())
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
    }

    private fun describe(loc: Location?): String {
        if (loc == null) return "Location unavailable."
        val mins = ((System.currentTimeMillis() - loc.time) / 60000).coerceAtLeast(0)
        return "Location: https://maps.google.com/?q=${loc.latitude},${loc.longitude} " +
            "(accuracy ~${loc.accuracy.toInt()} m, ${mins} min old)"
    }

    private fun sendToAll(body: String) {
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31)
            getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        for ((_, number) in Store.contacts(this)) {
            try {
                sms.sendMultipartTextMessage(number, null, sms.divideMessage(body), null, null)
            } catch (_: Exception) {}
        }
    }

    private fun sendSmsToContacts(alertText: String) {
        if (!has(Manifest.permission.SEND_SMS)) return
        // 1) Send immediately with the last known location (no waiting).
        val quick = lastKnown()
        sendToAll("$alertText ${describe(quick)}")
        // 2) Then try for a fresh fix and send it as a follow-up if it adds something.
        val fresh = freshLocation()
        if (fresh != null && (quick == null || fresh.distanceTo(quick) > 30f)) {
            sendToAll("Updated. ${describe(fresh)}")
        }
    }

    private fun lastKnown(): Location? {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        try {
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best!!.time) best = l
            }
        } catch (_: SecurityException) {}
        return best
    }

    private fun freshLocation(): Location? {
        if (Build.VERSION.SDK_INT < 30 || !has(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        var fresh: Location? = null
        val latch = CountDownLatch(1)
        try {
            lm.getCurrentLocation(
                provider, null, Executors.newSingleThreadExecutor(),
                Consumer<Location?> { l -> fresh = l; latch.countDown() }
            )
            latch.await(10, TimeUnit.SECONDS)
        } catch (_: Exception) {}
        return fresh
    }

    // ------------------------------------------------------------------ notifications

    private fun setStatus(s: String, level: Int = Store.LEVEL_WARN) {
        Store.setStatus(this, s, level)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_STATUS, statusNotification(s))
    }

    private fun statusNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Halo is watching")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Monitoring status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_LINK, "Pendant connection", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WARN, "Check-in warnings", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Emergency alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)   // the alarm screen plays its own sound
            }
        )
    }

    companion object {
        const val CH_STATUS = "status"
        const val CH_ALARM = "alarm"
        const val CH_LINK = "link"
        const val CH_WARN = "warn"
        const val ACTION_ARM = "com.safetybracelet.ble.ARM"
        const val ACTION_DISARM = "com.safetybracelet.ble.DISARM"
        const val NOTIF_STATUS = 1
        const val NOTIF_ALARM = 2
        const val NOTIF_LINK = 3
        const val NOTIF_WARN = 4
        const val CALL_DELAY_MS = 6000L
    }
}
