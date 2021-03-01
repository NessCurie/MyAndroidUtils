package com.nesscurie.utils.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.net.ConnectivityManager
import android.os.Build
import android.support.annotation.RequiresApi
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import com.android.internal.R
import com.android.internal.telephony.TelephonyIntents

@Suppress("DEPRECATION")
@RequiresApi(Build.VERSION_CODES.M)
class MobileSignalController @JvmOverloads constructor(private val context: Context,
                                                       private val maxLevel: Int = DEFAULT_SIGNAL_MAX_LEVEL) {

    companion object {
        private const val DEFAULT_SIGNAL_MAX_LEVEL = 4

        const val SIM_STATE_NO_SIM = 0
        const val SIM_STATE_READY = 1
        const val SIM_STATE_NOT_READY = 2

        const val SIGNAL_STRENGTH_NONE_OR_UNKNOWN = 0

        private const val RSRP_THRESH_TYPE_STRICT = 0  //RSRP时信号等级判定更严格
    }

    private val telephonyManager: TelephonyManager by lazy {
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }
    private var onDataEnabledChangeListener: ((enabled: Boolean) -> Unit)? = null
    private var onSimStateChangeListener: ((simState: Int) -> Unit)? = null
    private var onMobileSignalChangeListener: ((level: Int) -> Unit)? = null
    private val simChangeListener: BroadcastReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (TelephonyIntents.ACTION_SIM_STATE_CHANGED == intent.action) {
                    val simState = telephonyManager.simState
                    invokeListener(simState)
                    when (simState) {
                        TelephonyManager.SIM_STATE_ABSENT -> {
                            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
                        }
                        else -> {
                            telephonyManager.listen(phoneStateListener,
                                    PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                                            or PhoneStateListener.LISTEN_CARRIER_NETWORK_CHANGE)
                        }
                    }
                } else if (ConnectivityManager.CONNECTIVITY_ACTION == intent.action) {
                    val enabled = telephonyManager.getDataEnabled()
                    invokeDataEnabled(enabled)
                }
            }
        }
    }

    private fun invokeListener(simState: Int) {
        when (simState) {
            TelephonyManager.SIM_STATE_READY -> onSimStateChangeListener?.invoke(SIM_STATE_READY)
            TelephonyManager.SIM_STATE_ABSENT -> {
                onSimStateChangeListener?.invoke(SIM_STATE_NO_SIM)
                onMobileSignalChangeListener?.invoke(0)
            }
            else -> onSimStateChangeListener?.invoke(SIM_STATE_NOT_READY)
        }
    }

    private fun invokeDataEnabled(enabled: Boolean) {
        onDataEnabledChangeListener?.invoke(enabled)
    }

    fun setOnSimStateChangeListener(onDataEnabledChangeListener: ((enabled: Boolean) -> Unit)?,
                                    onSimStateChangeListener: ((simState: Int) -> Unit)?,
                                    onMobileSignalChangeListener: ((level: Int) -> Unit)?) {
        this.onDataEnabledChangeListener = onDataEnabledChangeListener
        this.onSimStateChangeListener = onSimStateChangeListener
        this.onMobileSignalChangeListener = onMobileSignalChangeListener

        val enabled = telephonyManager.getDataEnabled()
        invokeDataEnabled(enabled)
        val simState = telephonyManager.simState  //刚开机会是SIM_STATE_NOT_READY 之后广播收到SIM_STATE_ABSENT
        invokeListener(simState)

        if (simState != TelephonyManager.SIM_STATE_ABSENT) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                    or PhoneStateListener.LISTEN_CARRIER_NETWORK_CHANGE)
            setNetWorkShow()
        }
        val filter = IntentFilter()
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED)
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        context.registerReceiver(simChangeListener, filter)
    }

    fun release() {
        context.unregisterReceiver(simChangeListener)
    }

    private val phoneStateListener = object : PhoneStateListener() {

        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            if (maxLevel == DEFAULT_SIGNAL_MAX_LEVEL) {
                onMobileSignalChangeListener?.invoke(signalStrength.level)
            } else {
                onMobileSignalChangeListener?.invoke(getLevel(signalStrength))
            }
        }

        override fun onCarrierNetworkChange(active: Boolean) {
            setNetWorkShow()
        }
    }

    private fun setNetWorkShow() {
        when (TelephonyManager.getNetworkClass(telephonyManager.networkType)) {
            TelephonyManager.NETWORK_CLASS_2_G,
            TelephonyManager.NETWORK_CLASS_3_G,
            TelephonyManager.NETWORK_CLASS_4_G -> {
                onSimStateChangeListener?.invoke(SIM_STATE_READY)
            }
            else -> onSimStateChangeListener?.invoke(SIM_STATE_NOT_READY)
        }
    }

    private fun getLevel(signalStrength: SignalStrength): Int {
        var level: Int
        if (signalStrength.isGsm) {
            level = signalStrength.getLteLevel(maxLevel)
            if (level == SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
                level = signalStrength.getTdScdmaLevel(maxLevel)
                if (level == SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
                    level = signalStrength.getGsmLevel(maxLevel)
                }
            }
        } else {
            val cdmaLevel: Int = signalStrength.getCdmaLevel(maxLevel)
            val evdoLevel: Int = signalStrength.getEvdoLevel(maxLevel)
            level = if (evdoLevel == SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
                /* We don't know evdo, use cdma */
                cdmaLevel
            } else if (cdmaLevel == SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
                /* We don't know cdma, use evdo */
                evdoLevel
            } else {
                /* We know both, use the lowest level */
                if (cdmaLevel < evdoLevel) cdmaLevel else evdoLevel
            }
        }
        return level
    }

    private fun SignalStrength.getLteLevel(maxLevel: Int): Int {
        /*
         * TS 36.214 Physical Layer Section 5.1.3 TS 36.331 RRC RSSI = received
         * signal + noise RSRP = reference signal dBm RSRQ = quality of signal
         * dB= Number of Resource blocksxRSRP/RSSI SNR = gain=signal/noise ratio
         * = -10log P1/P2 dB
         */
        var rsrpIconLevel = -1
        val rsrpThreshStrict = intArrayOf(-140, -125, -115, -105, -95, -85, -44)    //严格
        val rsrpThreshLenient = intArrayOf(-140, -128, -118, -108, -98, -88 - 44)   //宽松
        val rsrpThreshType = Resources.getSystem().getInteger(R.integer.config_LTE_RSRP_threshold_type)
        var threshRsrp = if (rsrpThreshType == RSRP_THRESH_TYPE_STRICT) {
            rsrpThreshStrict
        } else {
            rsrpThreshLenient
        }

        if (Resources.getSystem().getBoolean(R.bool.config_regional_lte_singnal_threshold)) {
            try {
                //msm8953 [-140, -113, -103, -97, -89, -44]
                val configThreshold = Resources.getSystem().getIntArray(R.array.lte_signal_strength_threshold)
                if (configThreshold.isNotEmpty()) {
                    val temp = IntArray(7)
                    temp[0] = configThreshold[0]
                    temp[1] = configThreshold[1] - 10
                    System.arraycopy(configThreshold, 1, temp, 2, configThreshold.size - 1)
                    threshRsrp = temp
                }
            } catch (e: Exception) {
            }
        }
        val letRsrp = this.getLteRsrp()
        val threshRsrpLastPosition = threshRsrp.size - 1
        for (i in threshRsrpLastPosition downTo 0) {
            if (i == threshRsrpLastPosition) {
                if (letRsrp > threshRsrp[i]) {
                    rsrpIconLevel = -1
                    break
                }
            } else {
                if (letRsrp >= threshRsrp[i]) {
                    rsrpIconLevel = i
                    break
                }
            }
        }

        if (Resources.getSystem().getBoolean(R.bool.config_regional_lte_singnal_threshold)) {
            if (rsrpIconLevel != -1) return rsrpIconLevel
        }
        /*
         * Values are -200 dB to +300 (SNR*10dB) RS_SNR >= 13.0 dB =>4 bars 4.5
         * dB <= RS_SNR < 13.0 dB => 3 bars 1.0 dB <= RS_SNR < 4.5 dB => 2 bars
         * -3.0 dB <= RS_SNR < 1.0 dB 1 bar RS_SNR < -3.0 dB/No Service Antenna
         * Icon Only
         */
        val lteRssnr = this.getLteRssnr()
        var snrIconLevel = -1
        if (lteRssnr > 300) snrIconLevel = -1
        else if (lteRssnr >= 130) snrIconLevel = maxLevel
        else if (lteRssnr >= 45) snrIconLevel = maxLevel - 1
        else if (lteRssnr >= 10) snrIconLevel = maxLevel - 2
        else if (lteRssnr >= -30) snrIconLevel = maxLevel - 3
        else if (lteRssnr >= -66) snrIconLevel = maxLevel - 4
        else if (lteRssnr >= -200) snrIconLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN
        /* Choose a measurement type to use for notification */
        if (snrIconLevel != -1 && rsrpIconLevel != -1) {
            /*
             * The number of bars displayed shall be the smaller of the bars
             * associated with LTE RSRP and the bars associated with the LTE
             * RS_SNR
             */
            return if (rsrpIconLevel < snrIconLevel) rsrpIconLevel else snrIconLevel
        }
        if (snrIconLevel != -1) return snrIconLevel
        if (rsrpIconLevel != -1) return rsrpIconLevel

        /* Valid values are (0-63, 99) as defined in TS 36.331 */
        val lteSignalStrength = this.getLteSignalStrength()
        var rssiIconLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN
        if (lteSignalStrength > 63) rssiIconLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN
        else if (lteSignalStrength >= 12) rssiIconLevel = maxLevel
        else if (lteSignalStrength >= 8) rssiIconLevel = maxLevel - 1
        else if (lteSignalStrength >= 5) rssiIconLevel = maxLevel - 2
        else if (lteSignalStrength >= 2) rssiIconLevel = maxLevel - 3
        else if (lteSignalStrength >= 0) rssiIconLevel = maxLevel - 4
        return rssiIconLevel
    }

    private fun SignalStrength.getTdScdmaLevel(maxLevel: Int): Int {
        val tdScdmaDbm: Int = this.getTdScdmaDbm()
        val level: Int
        if (tdScdmaDbm > -25 || tdScdmaDbm == SignalStrength.INVALID) level = SIGNAL_STRENGTH_NONE_OR_UNKNOWN
        else if (tdScdmaDbm >= -49) level = maxLevel
        else if (tdScdmaDbm >= -60) level = maxLevel - 1
        else if (tdScdmaDbm >= -73) level = maxLevel - 2
        else if (tdScdmaDbm >= -97) level = maxLevel - 3
        else if (tdScdmaDbm >= -110) level = maxLevel - 4
        else level = SIGNAL_STRENGTH_NONE_OR_UNKNOWN
        return level
    }

    private fun SignalStrength.getGsmLevel(maxLevel: Int): Int {
        var level: Int = SIGNAL_STRENGTH_NONE_OR_UNKNOWN
        //msm8953 false empty
        @Suppress("SimplifyBooleanWithConstants")
        if (false && Resources.getSystem().getBoolean(R.bool.config_regional_umts_singnal_threshold)) {
            val dbm: Int = this.getGsmDbm()
            val threshGsm = Resources.getSystem().getIntArray(R.array.umts_signal_strength_threshold)
            if (threshGsm.size < 6) return level
            if (dbm > threshGsm[5]) level = SIGNAL_STRENGTH_NONE_OR_UNKNOWN
            else if (dbm >= threshGsm[4]) level = maxLevel
            else if (dbm >= threshGsm[3]) level = maxLevel - 1
            else if (dbm >= threshGsm[2]) level = maxLevel - 2
            else if (dbm >= threshGsm[1]) level = maxLevel - 3
            else if (dbm >= threshGsm[0]) level = SIGNAL_STRENGTH_NONE_OR_UNKNOWN
        } else {
            // ASU ranges from 0 to 31 - TS 27.007 Sec 8.5
            // asu = 0 (-113dB or less) is very weak
            // signal, its better to show 0 bars to the user in such cases.
            // asu = 99 is a special case, where the signal strength is unknown.
            val asu: Int = getGsmSignalStrength()
            if (asu <= 2 || asu == 99) level = SIGNAL_STRENGTH_NONE_OR_UNKNOWN
            else if (asu >= 12) level = maxLevel
            else if (asu >= 9) level = maxLevel - 1
            else if (asu >= 6) level = maxLevel - 2
            else if (asu >= 4) level = maxLevel - 3
            else level = maxLevel - 4
        }
        return level
    }

    private fun SignalStrength.getCdmaLevel(maxLevel: Int): Int {
        val cdmaDbm: Int = cdmaDbm
        val cdmaEcio: Int = cdmaEcio
        val levelDbm: Int
        val levelEcio: Int
        if (cdmaDbm >= -75) levelDbm = maxLevel
        else if (cdmaDbm >= -82) levelDbm = maxLevel - 1
        else if (cdmaDbm >= -88) levelDbm = maxLevel - 2
        else if (cdmaDbm >= -95) levelDbm = maxLevel - 3
        else if (cdmaDbm >= -100) levelDbm = maxLevel - 4
        else levelDbm = SIGNAL_STRENGTH_NONE_OR_UNKNOWN

        // Ec/Io are in dB*10
        if (cdmaEcio >= -90) levelEcio = maxLevel
        else if (cdmaEcio >= -105) levelEcio = maxLevel - 1
        else if (cdmaEcio >= -120) levelEcio = maxLevel - 2
        else if (cdmaEcio >= -135) levelEcio = maxLevel - 3
        else if (cdmaEcio >= -150) levelEcio = maxLevel - 4
        else levelEcio = SIGNAL_STRENGTH_NONE_OR_UNKNOWN
        return if (levelDbm < levelEcio) levelDbm else levelEcio
    }

    private fun SignalStrength.getEvdoLevel(maxLevel: Int): Int {
        val evdoDbm: Int = evdoDbm
        val evdoSnr: Int = evdoSnr
        val levelEvdoDbm: Int
        val levelEvdoSnr: Int
        if (evdoDbm >= -65) levelEvdoDbm = maxLevel
        else if (evdoDbm >= -75) levelEvdoDbm = maxLevel - 1
        else if (evdoDbm >= -85) levelEvdoDbm = maxLevel - 2
        else if (evdoDbm >= -95) levelEvdoDbm = maxLevel - 3
        else if (evdoDbm >= -105) levelEvdoDbm = maxLevel - 4
        else levelEvdoDbm = SIGNAL_STRENGTH_NONE_OR_UNKNOWN

        if (evdoSnr >= 7) levelEvdoSnr = maxLevel
        else if (evdoSnr >= 6) levelEvdoSnr = maxLevel - 1
        else if (evdoSnr >= 4) levelEvdoSnr = maxLevel - 2
        else if (evdoSnr >= 3) levelEvdoSnr = maxLevel - 3
        else if (evdoSnr >= 1) levelEvdoSnr = maxLevel - 4
        else levelEvdoSnr = SIGNAL_STRENGTH_NONE_OR_UNKNOWN
        return if (levelEvdoDbm < levelEvdoSnr) levelEvdoDbm else levelEvdoSnr
    }
}