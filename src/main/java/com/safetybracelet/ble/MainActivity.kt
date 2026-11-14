package com.safetybracelet.ble

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.provider.ContactsContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

private class Look(
    val title: String,
    val detail: String,
    val button: String,
    val color: Int,
    val steps: Int,
    val active: Boolean
)

class MainActivity : Activity() {

    private lateinit var chipDot: View
    private lateinit var chipText: TextView
    private lateinit var heroBox: LinearLayout
    private lateinit var heroTitle: TextView
    private lateinit var heroDetail: TextView
    private lateinit var track: LinearLayout
    private lateinit var heroButton: TextView
    private lateinit var deviceBox: LinearLayout
    private lateinit var contactsBox: LinearLayout
    private lateinit var stopLink: TextView
    private var pulse: ObjectAnimator? = null
    private var afterPerms: (() -> Unit)? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    private fun dp(v: Int) = Ui.dp(this, v)

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        window.statusBarColor = Ui.PAGE
        window.navigationBarColor = Ui.PAGE

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(36))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(Ui.heavy(this, "HALO", 28f, Ui.TEXT).apply { letterSpacing = 0.18f })
        brand.addView(Ui.text(this, "Personal safety pendant", 13f, Ui.MUTED))
        header.addView(brand, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))

        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(this@MainActivity, Ui.CARD, 16, Ui.LINE, 1)
            setPadding(dp(12), dp(7), dp(14), dp(7))
        }

        chipDot = View(this)
        chip.addView(chipDot, Ui.lp(this, dp(8), dp(8), r = 8))
        chipText = Ui.text(this, "", 13f, Ui.TEXT, true)
        chip.addView(chipText)
        header.addView(chip)
        root.addView(header, Ui.lp(this, Ui.MATCH, Ui.WRAP, b = 22))

        heroBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
        }

        heroBox.addView(Ui.caps(this, "Pendant status", 11f, Ui.MUTED))
        heroTitle = Ui.heavy(this, "", 34f, Ui.TEXT).apply { setPadding(0, dp(6), 0, dp(6)) }
        heroBox.addView(heroTitle)
        heroDetail = Ui.text(this, "", 15f, Ui.MUTED).apply { setLineSpacing(0f, 1.15f) }
        heroBox.addView(heroDetail, Ui.lp(this, Ui.MATCH, Ui.WRAP, b = 20))
        track = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        heroBox.addView(track, Ui.lp(this, Ui.MATCH, Ui.WRAP, b = 22))
        heroButton = Ui.button(this, "", Ui.ACCENT, Color.WHITE) { onMain() }
        heroBox.addView(heroButton, Ui.lp(this, Ui.MATCH, Ui.WRAP))
        root.addView(heroBox, Ui.lp(this, Ui.MATCH, Ui.WRAP))

        deviceBox = Ui.card(this)
        root.addView(deviceBox, Ui.lp(this, Ui.MATCH, Ui.WRAP, t = 14))

        contactsBox = Ui.card(this)
        root.addView(contactsBox, Ui.lp(this, Ui.MATCH, Ui.WRAP, t = 14))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        footer.addView(Ui.link(this, "Test alarm screen", Ui.MUTED) {
            startActivity(
                Intent(this, AlarmActivity::class.java)
                    .putExtra("message", "This is a test. No messages or calls were sent.")
                    .putExtra("test", true)
            )
        })

        stopLink = Ui.link(this, "Stop monitoring", Ui.RED) { stopMonitoring() }
        footer.addView(stopLink)
        root.addView(footer, Ui.lp(this, Ui.MATCH, Ui.WRAP, t = 12))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.PAGE)
            isFillViewport = true
            addView(root)
        })

    }

    override fun onResume() {

        super.onResume()
        Store.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        refresh()

    }

    override fun onPause() {

        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        setPulse(false)
        super.onPause()

    }

    // ------------------------------------------------------------ rendering

    private fun refresh() {

        val on = Store.monitoring(this)
        val linked = on && Store.linked(this)
        val dev = Store.device(this)

        val look = when {

            !on -> Look(
                "Offline", "Pair your pendant, then start monitoring to receive alerts.",
                "Start monitoring", Ui.ACCENT, 0, true
            )

            !linked -> Look(
                "Searching", Store.status(this),
                "Searching for pendant...", Ui.AMBER, 0, false
            )

            dev == Store.DEV_SOS -> Look(
                "SOS sent", "Your contacts have been alerted. Restart the pendant to reset it.",
                "Alert sent", Ui.RED, 4, false
            )

            dev == Store.DEV_WARN -> Look(
                "Check in!", "Press the safe button on the pendant now, or an SOS will be sent.",
                "Disarm", Ui.AMBER, 3, true
            )

            dev == Store.DEV_ARMED -> Look(
                "Armed", "Press the safe button on the pendant before the timer runs out.",
                "Disarm", Ui.GREEN, 2, true
            )

            else -> Look(
                "Ready", "The pendant is connected. Arm it when you start your journey.",
                "Arm pendant", Ui.ACCENT, 1, true
            )

        }

        heroBox.background = Ui.gradient(this, Ui.mix(look.color, 0x40), Ui.CARD, 28)
        heroTitle.text = look.title
        heroTitle.setTextColor(look.color)
        heroDetail.text = look.detail
        heroButton.text = look.button

        if (look.active) Ui.styleButton(this, heroButton, look.color, Ui.onColor(look.color), radius = 18)
        else Ui.styleButton(this, heroButton, Ui.mix(look.color, 0x22), look.color, look.color, 18)

        track.removeAllViews()

        listOf("Ready", "Armed", "Check-in", "SOS").forEachIndexed { i, label ->

            val lit = i < look.steps
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            col.addView(
                View(this).apply {
                    background = Ui.round(this@MainActivity, if (lit) look.color else Ui.LINE, 3)
                },
                Ui.lp(this, Ui.MATCH, dp(5))
            )

            col.addView(
                Ui.text(this, label, 11f, if (lit) Ui.TEXT else Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) }
            )

            track.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { if (i > 0) leftMargin = dp(6) })

        }

        val chipColor = when {
            !on -> Ui.GREY
            !linked -> Ui.AMBER
            else -> Ui.GREEN
        }

        chipText.text = when {
            !on -> "Offline"
            !linked -> "Searching"
            else -> "Connected"
        }

        chipDot.background = Ui.circle(chipColor)
        stopLink.visibility = if (on) View.VISIBLE else View.GONE

        setPulse(on && (!linked || dev == Store.DEV_WARN))
        renderDevice()
        renderContacts()

    }

    private fun setPulse(on: Boolean) {

        if (on) {

            if (pulse == null) {
                pulse = ObjectAnimator.ofFloat(heroTitle, "alpha", 1f, 0.35f).apply {
                    duration = 800
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    start()
                }
            }

        } else {

            pulse?.cancel()
            pulse = null
            heroTitle.alpha = 1f

        }

    }

    private fun renderDevice() {

        deviceBox.removeAllViews()

        val addr = Store.braceletAddress(this)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(
            Ui.heavy(this, "P", 18f, Ui.ACCENT).apply {
                gravity = Gravity.CENTER
                background = Ui.circle(Ui.mix(Ui.ACCENT, 0x33))
            },
            Ui.lp(this, dp(44), dp(44), r = 14)
        )

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.caps(this, "Paired pendant", 10f, Ui.MUTED))

        col.addView(
            Ui.text(this, if (addr == null) "None yet" else Store.braceletName(this), 17f, Ui.TEXT, true)
                .apply { setPadding(0, dp(2), 0, dp(2)) }
        )

        col.addView(Ui.text(this, addr ?: "Power it on, then tap Pair.", 12f, Ui.MUTED))
        row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))

        row.addView(Ui.link(this, if (addr == null) "Pair" else "Change", Ui.ACCENT) {
            withPermissions { findBracelet() }
        })

        deviceBox.addView(row)

    }

    private fun renderContacts() {

        contactsBox.removeAllViews()

        val list = Store.contacts(this)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(Ui.caps(this, "Trusted circle", 11f, Ui.MUTED), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))

        header.addView(Ui.link(this, "+ Add", Ui.ACCENT) {
            startActivityForResult(
                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_PICK
            )
        })

        contactsBox.addView(header)

        contactsBox.addView(
            Ui.text(
                this, "An SOS texts everyone your location and calls the first contact. A silent alert only texts.",
                13f, Ui.MUTED
            ).apply { setPadding(0, dp(2), 0, dp(10)) }
        )

        if (list.isEmpty()) {

            contactsBox.addView(Ui.text(this, "No contacts yet. Add at least one.", 14f, Ui.TEXT))
            return

        }

        val tones = listOf(Ui.ACCENT, Ui.GREEN, Ui.AMBER, 0xFF4DA3FF.toInt())

        list.forEachIndexed { i, (name, number) ->

            val tone = tones[i % tones.size]

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.round(this@MainActivity, Ui.RAISED, 14)
                setPadding(dp(12), dp(10), dp(6), dp(10))
            }

            row.addView(
                Ui.heavy(this, name.take(1).uppercase(), 15f, tone).apply {
                    gravity = Gravity.CENTER
                    background = Ui.circle(Ui.mix(tone, 0x33))
                },
                Ui.lp(this, dp(36), dp(36), r = 12)
            )

            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(Ui.text(this, name, 15f, Ui.TEXT, true))
            col.addView(Ui.text(this, if (i == 0) "$number  -  called first" else number, 12f, Ui.MUTED))
            row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))

            row.addView(Ui.link(this, "Remove", Ui.RED) {
                Store.removeContact(this, i)
                refresh()
            })

            contactsBox.addView(row, Ui.lp(this, Ui.MATCH, Ui.WRAP, b = 8))

        }

    }

    // ------------------------------------------------------------ permissions

    private fun neededPermissions(): List<String> {

        val l = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= 31) {
            l.add(Manifest.permission.BLUETOOTH_SCAN)
            l.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)

        return l

    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun withPermissions(action: () -> Unit) {

        val missing = neededPermissions().filter { !granted(it) }

        if (missing.isEmpty()) action()
        else { afterPerms = action; requestPermissions(missing.toTypedArray(), REQ_PERMS) }

    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {

        super.onRequestPermissionsResult(code, perms, results)

        if (code != REQ_PERMS) return

        if (results.any { it != PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(
                this, "Some permissions were denied: Bluetooth, SMS, calls or location may not work",
                Toast.LENGTH_LONG
            ).show()
        }

        val a = afterPerms
        afterPerms = null
        a?.invoke()

    }

    // ------------------------------------------------------------ find the bracelet

    private fun findBracelet() {

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Turn Bluetooth on first", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_SCAN)) {
            Toast.makeText(this, "Allow the Nearby devices permission", Toast.LENGTH_LONG).show()
            return
        }

        val scanner = adapter.bluetoothLeScanner

        if (scanner == null) {
            Toast.makeText(this, "Bluetooth scanner not available", Toast.LENGTH_LONG).show()
            return
        }

        val found = ArrayList<Pair<String, String>>()   // name to address
        val shown = ArrayList<String>()
        val listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown)

        val cb = object : ScanCallback() {

            override fun onScanResult(callbackType: Int, result: ScanResult) {

                val addr = result.device.address

                if (found.any { it.second == addr }) return

                val name = result.scanRecord?.deviceName ?: "SafetyModule"
                found.add(name to addr)
                shown.add("$name\n$addr")
                listAdapter.notifyDataSetChanged()

            }

        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Looking for your pendant")
            .setAdapter(listAdapter) { d, which ->

                val (name, addr) = found[which]
                Store.setBracelet(this, addr, name)
                d.dismiss()
                refresh()

                if (Store.monitoring(this)) {   // restart so it connects to the new bracelet
                    stopService(Intent(this, MonitorService::class.java))
                    ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
                }

            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnDismissListener {
            try { scanner.stopScan(cb) } catch (_: Exception) {}
        }

        dialog.show()

        try {

            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Ble.SERVICE)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(listOf(filter), settings, cb)

        } catch (e: Exception) {

            dialog.dismiss()
            Toast.makeText(this, "Could not scan: ${e.message}", Toast.LENGTH_LONG).show()

        }

    }

    // ------------------------------------------------------------ start / stop

    private fun onMain() {

        if (!Store.monitoring(this)) {
            startMonitoring()
            return
        }

        if (!Store.linked(this)) {
            Toast.makeText(this, "Still looking for the pendant", Toast.LENGTH_SHORT).show()
            return
        }

        when (Store.device(this)) {

            Store.DEV_ARMED, Store.DEV_WARN -> command(MonitorService.ACTION_DISARM)
            Store.DEV_SOS -> Toast.makeText(this, "Restart the pendant to reset the alert", Toast.LENGTH_SHORT).show()
            else -> command(MonitorService.ACTION_ARM)

        }

    }

    private fun command(action: String) {

        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java).setAction(action))

    }

    private fun stopMonitoring() {

        stopService(Intent(this, MonitorService::class.java))
        Store.setMonitoring(this, false)
        Store.setLinked(this, false)
        Store.setDevice(this, Store.DEV_UNKNOWN)
        refresh()

    }

    private fun startMonitoring() {

        if (Store.braceletAddress(this) == null) {
            Toast.makeText(this, "Pair your pendant first", Toast.LENGTH_SHORT).show()
            return
        }

        if (Store.contacts(this).isEmpty()) {
            Toast.makeText(this, "Tip: add at least one emergency contact", Toast.LENGTH_LONG).show()
        }

        withPermissions { beginMonitoring() }

    }

    private fun beginMonitoring() {

        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            Toast.makeText(this, "Allow the Nearby devices permission to connect", Toast.LENGTH_LONG).show()
            return
        }

        Store.setMonitoring(this, true)
        Store.setStatus(this, "Starting...")
        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))

        // Lets the alarm screen open while another app is in front or the phone is locked.
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow 'Display over other apps' for this app", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        // Android 14+ may block the full-screen alarm unless this is allowed.
        if (Build.VERSION.SDK_INT >= 34) {

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (!nm.canUseFullScreenIntent()) {
                Toast.makeText(this, "Allow full-screen alerts for this app", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        .setData(Uri.parse("package:$packageName"))
                )
            }

        }

        refresh()

    }

    // ------------------------------------------------------------ contact picker

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {

        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return

        val uri = data?.data ?: return

        contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { c ->

            if (c.moveToFirst()) {
                Store.addContact(this, c.getString(0) ?: "Contact", c.getString(1) ?: "")
            }

        }

        refresh()

    }

    companion object {
        private const val REQ_PICK = 10
        private const val REQ_PERMS = 11
    }

}
