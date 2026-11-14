package com.safetybracelet.ble

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Bluetooth IDs. They must match the bracelet firmware. */
object Ble {
    val SERVICE: UUID = UUID.fromString("19b10000-e8f2-537e-4f6c-d104768a1214")
    val CHAR: UUID = UUID.fromString("19b10001-e8f2-537e-4f6c-d104768a1214")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/** Shared between the background service and the alarm screen. */
object AlarmState {
    @Volatile var callCancelled = false
    const val ACTION_STOP_ALARM = "com.safetybracelet.ble.STOP_ALARM"
}

object Store {
    private const val FILE = "bracelet_ble"
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun braceletAddress(c: Context): String? = prefs(c).getString("addr", null)
    fun braceletName(c: Context): String = prefs(c).getString("bname", "SafetyModule") ?: "SafetyModule"
    fun setBracelet(c: Context, address: String, name: String) =
        prefs(c).edit().putString("addr", address).putString("bname", name).apply()

    fun monitoring(c: Context): Boolean = prefs(c).getBoolean("monitoring", false)
    fun setMonitoring(c: Context, v: Boolean) = prefs(c).edit().putBoolean("monitoring", v).apply()

    fun status(c: Context): String = prefs(c).getString("status", "Not monitoring") ?: "Not monitoring"
    const val LEVEL_WARN = 1    // connecting / waiting
    const val LEVEL_OK = 2      // bracelet connected and watching
    const val LEVEL_ALERT = 3   // emergency in progress
    fun level(c: Context): Int = prefs(c).getInt("level", LEVEL_WARN)
    fun setStatus(c: Context, v: String, level: Int = LEVEL_WARN) =
        prefs(c).edit().putString("status", v).putInt("level", level).apply()

    const val DEV_UNKNOWN = 0
    const val DEV_DISARMED = 1
    const val DEV_ARMED = 2
    const val DEV_WARN = 3
    const val DEV_SOS = 4

    fun device(c: Context): Int = prefs(c).getInt("device", DEV_UNKNOWN)
    fun setDevice(c: Context, v: Int) = prefs(c).edit().putInt("device", v).apply()

    fun linked(c: Context): Boolean = prefs(c).getBoolean("linked", false)
    fun setLinked(c: Context, v: Boolean) = prefs(c).edit().putBoolean("linked", v).apply()

    fun contacts(c: Context): List<Pair<String, String>> {
        val arr = JSONArray(prefs(c).getString("contacts", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.getString("name") to o.getString("number")
        }
    }

    private fun saveContacts(c: Context, list: List<Pair<String, String>>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.first).put("number", it.second)) }
        prefs(c).edit().putString("contacts", arr.toString()).apply()
    }

    fun addContact(c: Context, name: String, number: String) {
        val clean = number.filter { it.isDigit() || it == '+' }
        if (clean.isEmpty()) return
        val list = contacts(c).toMutableList()
        if (list.none { it.second == clean }) list.add(name to clean)
        saveContacts(c, list)
    }

    fun removeContact(c: Context, index: Int) {
        val list = contacts(c).toMutableList()
        if (index in list.indices) list.removeAt(index)
        saveContacts(c, list)
    }
}
