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
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.provider.ContactsContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

private class Look(
    val word: String,
    val color: Int,
    val detail: String,
    val button: String,
    val style: Int,
    val pulse: Boolean
)

class MainActivity : Activity() {

    private lateinit var ring: FrameLayout
    private lateinit var ringWord: TextView
    private lateinit var detailView: TextView
    private lateinit var mainBtn: TextView
    private lateinit var stopLink: TextView
    private lateinit var braceletBox: LinearLayout
    private lateinit var contactsBox: LinearLayout
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
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        root.addView(Ui.text(this, "Halo", 26f, Ui.INK, true))

        root.addView(
            Ui.text(this, "Personal safety pendant", 14f, Ui.MUTED)
                .apply { setPadding(0, dp(2), 0, dp(18)) }
        )

        // ---- Status card ----
        val hero = Ui.card(this).apply { gravity = Gravity.CENTER_HORIZONTAL }

        ring = FrameLayout(this)
        ringWord = Ui.text(this, "", 26f, Ui.INK, true)
        ring.addView(ringWord, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
        hero.addView(ring, LinearLayout.LayoutParams(dp(168), dp(168)).apply { topMargin = dp(4) })

        detailView = Ui.text(this, "", 15f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(18), dp(8), dp(20))
        }

        hero.addView(detailView, Ui.lp(this, Ui.MATCH, Ui.WRAP))

        mainBtn = Ui.button(this, "Start monitoring", Ui.INK, Ui.PAGE) { onMain() }
        hero.addView(mainBtn, Ui.lp(this, Ui.MATCH, Ui.WRAP))
        root.addView(hero, Ui.lp(this, Ui.MATCH, Ui.WRAP))

        // ---- Bracelet card ----
        braceletBox = Ui.card(this)
        root.addView(braceletBox, Ui.lp(this, Ui.MATCH, Ui.WRAP, t = 14))

        // ---- Contacts card ----
        contactsBox = Ui.card(this)
        root.addView(contactsBox, Ui.lp(this, Ui.MATCH, Ui.WRAP, t = 14))

        // ---- Footer ----
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        footer.addView(Ui.link(this, "Test the alarm screen", Ui.MUTED) {
            startActivity(
                Intent(this, AlarmActivity::class.java)
                    .putExtra("test", true)
                    .putExtra("message", "This is a test. No SMS or call will be made.")
            )
        })

        stopLink = Ui.link(this, "Stop monitoring", Ui.RED) { stopMonitoring() }
        footer.addView(stopLink)
        root.addView(footer, Ui.lp(this, Ui.MATCH, Ui.WRAP, t = 10))

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
                "Off", Ui.GREY, "Start monitoring to receive alerts from your pendant.",
                "Start monitoring", 0, false
            )

            !linked -> Look("Connecting", Ui.AMBER, Store.status(this), "Waiting for pendant...", 2, true)

            dev == Store.DEV_SOS -> Look(
                "Alert", Ui.RED, "Your contacts have been alerted. Restart the pendant to reset it.",
                "Alert sent", 2, false
            )

            dev == Store.DEV_WARN -> Look(
                "Check in", Ui.AMBER, "Press the safe button on the pendant now, or an SOS will be sent.",
                "Disarm", 1, true
            )

            dev == Store.DEV_ARMED -> Look(
                "Armed", Ui.GREEN, "Press the safe button on the pendant before the timer runs out.",
                "Disarm", 1, false
            )

            else -> Look(
                "Ready", Ui.MUTED, "The pendant is connected. Arm it when you start your journey.",
                "Arm pendant", 0, false
            )

        }

        ringWord.text = look.word
        ringWord.setTextColor(look.color)

        ring.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((look.color and 0x00FFFFFF) or 0x14000000)
            setStroke(dp(8), look.color)
        }

        setPulse(look.pulse)

        detailView.text = look.detail
        mainBtn.text = look.button

        when (look.style) {
            0 -> Ui.styleButton(this, mainBtn, Ui.INK, Ui.PAGE)
            1 -> Ui.styleButton(this, mainBtn, Ui.CARD, look.color, look.color)
            else -> Ui.styleButton(this, mainBtn, Ui.CARD, Ui.MUTED, Ui.LINE)
        }

        stopLink.visibility = if (on) View.VISIBLE else View.GONE

        renderBracelet()
        renderContacts()

    }

    private fun setPulse(on: Boolean) {

        if (on) {

            if (pulse == null) {
                pulse = ObjectAnimator.ofFloat(ring, "alpha", 1f, 0.45f).apply {
                    duration = 1000
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    start()
                }
            }

        } else {

            pulse?.cancel()
            pulse = null
            ring.alpha = 1f

        }

    }

    private fun renderBracelet() {

        braceletBox.removeAllViews()

        val addr = Store.braceletAddress(this)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(this, "Pendant", 13f, Ui.MUTED))

        col.addView(
            Ui.text(this, if (addr == null) "No pendant yet" else Store.braceletName(this), 17f, Ui.INK, true)
                .apply { setPadding(0, dp(2), 0, dp(2)) }
        )

        col.addView(Ui.text(this, addr ?: "Power it on, then tap Find.", 13f, Ui.MUTED))
        row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))

        row.addView(Ui.link(this, if (addr == null) "Find" else "Change", Ui.INK) {
            withPermissions { findBracelet() }
        })

        braceletBox.addView(row)

    }

    private fun renderContacts() {

        contactsBox.removeAllViews()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            Ui.text(this, "Emergency contacts", 17f, Ui.INK, true),
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f)
        )

        header.addView(Ui.link(this, "Add", Ui.INK) {
            startActivityForResult(
                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_PICK
            )
        })

        contactsBox.addView(header)

        contactsBox.addView(
            Ui.text(
                this,
                "Everyone gets an SMS with your location. The first contact is also called. A silent alert only sends the SMS.",
                13f, Ui.MUTED
            ).apply { setPadding(0, dp(2), 0, dp(8)) }
        )

        val list = Store.contacts(this)

        if (list.isEmpty()) {

            contactsBox.addView(
                Ui.text(this, "No contacts yet. Add someone who should be told if you need help.", 14f, Ui.MUTED)
                    .apply { setPadding(0, dp(8), 0, dp(4)) }
            )
            return

        }

        list.forEachIndexed { i, (name, number) ->

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }

            val avatar = Ui.text(this, name.firstOrNull()?.uppercase() ?: "?", 16f, Ui.INK, true).apply {
                gravity = Gravity.CENTER
                background = Ui.round(this@MainActivity, Ui.TINT, 20)
            }

            row.addView(avatar, Ui.lp(this, dp(40), dp(40), r = 14))

            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            nameRow.addView(Ui.text(this, name, 16f, Ui.INK, true))

            if (i == 0) {

                nameRow.addView(
                    Ui.text(this, "Called first", 12f, Ui.GREEN, true).apply {
                        setPadding(dp(8), dp(2), dp(8), dp(2))
                        background = Ui.round(this@MainActivity, Ui.TINT, 10)
                    },
                    Ui.lp(this, Ui.WRAP, Ui.WRAP, l = 8)
                )

            }

            col.addView(nameRow)
            col.addView(Ui.text(this, number, 13f, Ui.MUTED))
            row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))

            row.addView(Ui.link(this, "Remove", Ui.RED) {
                Store.removeContact(this, i)
                refresh()
            })

            contactsBox.addView(row)

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
