package com.nesscurie.utils.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager

/**
 * wifi信号的控制类,如果只是要显示wifi信号的图标可使用该类
 * @param context 上下文
 * @wifiLevelResource wifi信号图标资源的数组,会根据数组长度分级信号强度,并在对应信号等级回调对应资源
 */
class WifiSignalController(private val context: Context, private val wifiLevelResource: IntArray) {

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private var enabled = false
    private var isConnected = false
    private var ssid: String? = null
    private var level = 0
    private var onWifiStateChangeListener: ((enabled: Boolean, connected: Boolean, resource: Int) -> Unit)? = null

    @Suppress("DEPRECATION")
    private val wifiReceiver: BroadcastReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                        enabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                                WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED
                    }
                    WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<NetworkInfo?>(WifiManager.EXTRA_NETWORK_INFO)
                        val connected = networkInfo != null && networkInfo.isConnected
                        // If Connected grab the signal strength and ssid.
                        if (connected) {
                            // try getting it out of the intent first
                            val info = intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO)
                                    ?: wifiManager.connectionInfo
                            ssid = if (info != null) {
                                getSsid(info)
                            } else {
                                null
                            }
                        } else if (!connected) {
                            ssid = null
                        }
                    }
                    WifiManager.RSSI_CHANGED_ACTION -> {
                        // Default to -200 as its below WifiManager.MIN_RSSI.
                        val rssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -200)
                        level = WifiManager.calculateSignalLevel(rssi, wifiLevelResource.size)
                    }
                }
                isConnected = ssid != null
                onWifiStateChangeListener?.invoke(enabled, isConnected, wifiLevelResource[level])
            }
        }
    }

    fun setOnWifiStateChangeListener(listener: ((enabled: Boolean, connected: Boolean, resource: Int) -> Unit)?) {
        val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION)
        context.registerReceiver(wifiReceiver, filter)
        onWifiStateChangeListener = listener
        enabled = wifiManager.isWifiEnabled
        isConnected = wifiManager.connectionInfo.supplicantState == SupplicantState.COMPLETED
        level = WifiManager.calculateSignalLevel(wifiManager.connectionInfo.rssi, 5)
        onWifiStateChangeListener?.invoke(enabled, isConnected, wifiLevelResource[level])
    }

    fun release() {
        onWifiStateChangeListener = null
        context.unregisterReceiver(wifiReceiver)
    }

    private fun getSsid(info: WifiInfo): String? {
        val ssid = info.ssid
        if (ssid != null) {
            return ssid
        }
        // OK, it's not in the connectionInfo; we have to go hunting for it
        val networks = wifiManager.configuredNetworks
        for (network in networks) {
            if (network.networkId == info.networkId) {
                return network.SSID
            }
        }
        return null
    }
}