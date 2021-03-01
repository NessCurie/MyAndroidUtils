package com.nesscurie.utils.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.provider.Settings
import com.nesscurie.androidutils.R

class WifiApController(private val context: Context) {

    companion object {
        const val ENABLING = WifiManager.WIFI_AP_STATE_ENABLING
        const val ENABLED = WifiManager.WIFI_AP_STATE_ENABLED
        const val DISABLING = WifiManager.WIFI_AP_STATE_DISABLING
        const val DISABLED = WifiManager.WIFI_AP_STATE_DISABLED

        const val OPEN_INDEX = 0
        const val WPA2_INDEX = 1
    }

    private val wifiManager: WifiManager by lazy { context.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val wifiRegexs: Array<String> by lazy { connectivityManager.getTetherableWifiRegexs() }
    private var onWifiApStateChangeListener: ((Int, String, Boolean) -> Unit)? = null
    private var onWifiApInfoStateChangeListener: ((String, String, Int) -> Unit)? = null
    private val wifiApReceiver: BroadcastReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiManager.WIFI_AP_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(
                                WifiManager.EXTRA_WIFI_AP_STATE, WifiManager.WIFI_AP_STATE_FAILED)
                        if (state == WifiManager.WIFI_AP_STATE_FAILED) {
                            val reason = intent.getIntExtra(WifiManager.EXTRA_WIFI_AP_FAILURE_REASON,
                                    WifiManager.SAP_START_FAILURE_GENERAL)
                            handleWifiApStateChanged(state, reason)
                        } else {
                            handleWifiApStateChanged(state, WifiManager.SAP_START_FAILURE_GENERAL)
                        }
                    }
                    ConnectivityManager.ACTION_TETHER_STATE_CHANGED -> {
                        val available = intent.getStringArrayListExtra(
                                ConnectivityManager.EXTRA_AVAILABLE_TETHER)
                        val active = intent.getStringArrayListExtra(
                                ConnectivityManager.EXTRA_ACTIVE_TETHER)
                        val errored = intent.getStringArrayListExtra(
                                ConnectivityManager.EXTRA_ERRORED_TETHER)
                        updateTetherState(available.toTypedArray(), active.toTypedArray(), errored.toTypedArray())
                    }
                    Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                        enableWifiSwitch()
                    }
                }
            }
        }
    }

    fun init() {
        handleWifiApStateChanged(wifiManager.getWifiApState(), 0)
        val filter = IntentFilter(WifiManager.WIFI_AP_STATE_CHANGED_ACTION)
        filter.addAction(ConnectivityManager.ACTION_TETHER_STATE_CHANGED)
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        context.registerReceiver(wifiApReceiver, filter)
    }

    private fun handleWifiApStateChanged(state: Int, reason: Int) = when (state) {
        ENABLING -> {
            onWifiApStateChangeListener?.invoke(state, context.getString(R.string.opening), false)
        }
        ENABLED -> {
            val wifiApConfig: WifiConfiguration = wifiManager.getWifiApConfiguration()
            onWifiApStateChangeListener?.invoke(state, wifiApConfig.preSharedKey, true) //Doesn't need the airplane check
            val securityTypeIndex = if (wifiApConfig.allowedKeyManagement
                            .get(WifiConfiguration.KeyMgmt.WPA2_PSK)) {
                WPA2_INDEX
            } else {
                OPEN_INDEX
            }
            onWifiApInfoStateChangeListener?.invoke(wifiApConfig.SSID, wifiApConfig.preSharedKey, securityTypeIndex)
        }
        DISABLING -> {
            onWifiApStateChangeListener?.invoke(state, context.getString(R.string.closing), false)
        }
        DISABLED -> {
            onWifiApStateChangeListener?.invoke(state, context.getString(R.string.closed), enableWifiSwitch())
        }
        else -> {
            val hint = if (reason == WifiManager.SAP_START_FAILURE_NO_CHANNEL) {
                R.string.wifi_sap_no_channel_error
            } else {
                R.string.wifi_error
            }
            onWifiApStateChangeListener?.invoke(state, context.getString(hint), enableWifiSwitch())
        }
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun updateTetherState(available: Array<Any>, tethered: Array<Any>, errored: Array<Any>) {
        var wifiTethered = false
        var wifiErrored = false
        for (o in tethered) {
            val s = o as java.lang.String
            for (regex in wifiRegexs) {
                if (s.matches(regex)) wifiTethered = true
            }
        }
        for (o in errored) {
            val s = o as java.lang.String
            for (regex in wifiRegexs) {
                if (s.matches(regex)) wifiErrored = true
            }
        }
        if (wifiTethered) {
            if (wifiManager.getWifiApState() == WifiManager.WIFI_AP_STATE_ENABLED) {
                val wifiApConfig: WifiConfiguration = wifiManager.getWifiApConfiguration()
                val securityTypeIndex = if (wifiApConfig.allowedKeyManagement
                                .get(WifiConfiguration.KeyMgmt.WPA2_PSK)) {
                    WPA2_INDEX
                } else {
                    OPEN_INDEX
                }
                onWifiApInfoStateChangeListener?.invoke(wifiApConfig.SSID,
                        wifiApConfig.preSharedKey, securityTypeIndex)
            }
        } else if (wifiErrored) {
            val wifiApState = wifiManager.getWifiApState()
            if (wifiApState != WifiManager.WIFI_AP_STATE_ENABLED) {
                onWifiApStateChangeListener?.invoke(wifiApState,
                        context.getString(R.string.wifi_error), enableWifiSwitch())
            }
        }
    }

    private fun enableWifiSwitch(): Boolean {
        val isAirplaneMode = Settings.Global.getInt(context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        return !isAirplaneMode
    }

    fun reverseWifiAp() {
        if (wifiManager.getWifiApState() == WifiManager.WIFI_AP_STATE_ENABLED) {
            stopWifiAp()
        } else {
            startWifiAp()
        }
    }

    private fun startWifiAp() {
        connectivityManager.startTethering(ConnectivityManager.TETHERING_WIFI, true, null)
        handleWifiApStateChanged(WifiManager.WIFI_AP_STATE_ENABLING, 0)
    }

    private fun stopWifiAp() {
        connectivityManager.stopTethering(ConnectivityManager.TETHERING_WIFI)
    }

    fun setOnWifiApStateChangeListener(listener: ((Int, String, Boolean) -> Unit)?) {
        onWifiApStateChangeListener = listener
    }

    fun setOnWifiApInfoChangeListener(listener: ((String, String, Int) -> Unit)?) {
        onWifiApInfoStateChangeListener = listener
    }

    fun release() {
        onWifiApStateChangeListener = null
        onWifiApInfoStateChangeListener = null
        context.unregisterReceiver(wifiApReceiver)
    }
}