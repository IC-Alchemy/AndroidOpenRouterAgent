package com.example.openrouteragent

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/** How far back a network change still counts as a likely cause of a dropped stream. */
internal const val NETWORK_CHANGE_WINDOW_MILLIS = 120_000L

/** One change of the phone's default network, timed with [SystemClock.elapsedRealtime]. */
internal data class NetworkChange(val at: Long, val what: String)

/**
 * What the phone was doing when a stream dropped: whether the screen was on, whether the app was in front, power
 * saving, and recent network changes. Android aborts open sockets when the phone moves between Wi-Fi and mobile data
 * or cuts an app's network, and that shows up as "Software caused connection abort". This makes the error say which.
 */
object RunEnvironment {
    private const val MAX_CHANGES = 8
    @Volatile private var appContext: Context? = null
    private val changes = ArrayDeque<NetworkChange>()
    private var current: Network? = null
    private var currentLabel: String? = null

    /** Starts watching the default network. Safe to call more than once. */
    fun start(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    record(network, networkLabel(capabilities))
                }

                override fun onLost(network: Network) {
                    synchronized(this@RunEnvironment) {
                        if (network != current) return
                        current = null
                        currentLabel = null
                        add("lost its network")
                    }
                }
            })
        }.onFailure { Log.w("AgentRun", "Could not watch the network: ${it.message}") }
    }

    private fun record(network: Network, label: String) = synchronized(this) {
        if (network == current && label == currentLabel) return
        val previous = currentLabel
        val first = current == null && previous == null && changes.isEmpty()
        current = network
        currentLabel = label
        // The first network seen at start-up is not a change.
        if (!first) add(if (previous == null) "connected to $label" else "switched from $previous to $label")
    }

    /** Call with the lock held. */
    private fun add(what: String) {
        changes.addLast(NetworkChange(SystemClock.elapsedRealtime(), what))
        while (changes.size > MAX_CHANGES) changes.removeFirst()
    }

    /** One short line for an error message, or null before [start]. */
    fun describe(): String? {
        val context = appContext ?: return null
        val power = context.getSystemService(PowerManager::class.java)
        val recent = synchronized(this) { changes.toList() }
        return describePhoneState(
            screenOn = power?.isInteractive,
            appInFront = AppVisibility.visible,
            powerSave = power?.isPowerSaveMode == true,
            deviceIdle = power?.isDeviceIdleMode == true,
            changes = recent,
            now = SystemClock.elapsedRealtime(),
        )
    }
}

internal fun networkLabel(capabilities: NetworkCapabilities): String = when {
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
    else -> "another network"
}

/** Builds the phone line of a dropped-stream error. Network changes older than the window are left out. */
internal fun describePhoneState(
    screenOn: Boolean?, appInFront: Boolean, powerSave: Boolean, deviceIdle: Boolean,
    changes: List<NetworkChange>, now: Long,
): String {
    val parts = mutableListOf<String>()
    screenOn?.let { parts += if (it) "screen on" else "screen off" }
    parts += if (appInFront) "app on screen" else "app in the background"
    if (powerSave) parts += "battery saver on"
    if (deviceIdle) parts += "Doze idle"
    val recent = changes.filter { now - it.at in 0..NETWORK_CHANGE_WINDOW_MILLIS }
    parts += if (recent.isEmpty()) "no network change in the last ${NETWORK_CHANGE_WINDOW_MILLIS / 60_000} minutes"
        else recent.joinToString(", ") { "phone ${it.what} ${ago(now - it.at)} earlier" }
    return parts.joinToString(", ")
}

private fun ago(millis: Long): String =
    if (millis < 1_000) "under a second" else "${(millis + 500) / 1_000} s"
