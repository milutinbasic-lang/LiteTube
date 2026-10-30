package app.litelauncher

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Feeds the top bar from system broadcasts. Nothing polls: the clock follows the system's
 * once-a-minute TIME_TICK, and everything is unregistered while the launcher isn't visible,
 * so it costs nothing when you're in another app.
 */
@Suppress("DEPRECATION") // The old, permission-free APIs still work and keep minSdk 21.
class StatusMonitor(private val context: Context, private val bar: TopBarView, private val home: HomeView) {

    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
    private val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager?
    private var clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
    private var signalLevel = -1

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> onBattery(intent)
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> {
                    updateClockFormat(); updateClock()
                }
                Intent.ACTION_TIME_TICK -> updateClock()
                else -> updateNetwork()
            }
        }
    }

    private val phoneListener = object : PhoneStateListener() {
        override fun onSignalStrengthsChanged(strength: SignalStrength) {
            signalLevel = if (Build.VERSION.SDK_INT >= 23) strength.level else -1
            updateNetwork()
        }
    }

    fun start() {
        updateClockFormat()
        updateClock()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        // Battery is a sticky broadcast, so registering also delivers the current level.
        val sticky = if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        sticky?.let(::onBattery)
        signalLevel = -1
        try {
            telephony?.listen(phoneListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        } catch (e: RuntimeException) {
            // Some ROMs restrict this; the bar simply shows no signal icon.
        }
        updateNetwork()
    }

    fun stop() {
        context.unregisterReceiver(receiver)
        try {
            telephony?.listen(phoneListener, PhoneStateListener.LISTEN_NONE)
        } catch (e: RuntimeException) {
        }
    }

    private fun updateClockFormat() {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        val locale = Locale.getDefault()
        clockFormat = SimpleDateFormat(pattern, locale)
        dateFormat = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale)
    }

    private fun updateClock() {
        val now = Date()
        val time = clockFormat.format(now)
        bar.setTime(time)
        home.setTime(time, dateFormat.format(now))
    }

    private fun onBattery(intent: Intent) {
        if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        bar.setBattery(if (level < 0 || scale <= 0) -1 else level * 100 / scale, charging)
    }

    @SuppressLint("MissingPermission") // ACCESS_NETWORK_STATE and ACCESS_WIFI_STATE are declared.
    private fun updateNetwork() {
        val onWifi = if (Build.VERSION.SDK_INT >= 23) {
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } else {
            connectivity.activeNetworkInfo?.let { it.isConnected && it.type == ConnectivityManager.TYPE_WIFI } == true
        }
        val wifiLevel = if (onWifi) {
            val rssi = wifi?.connectionInfo?.rssi ?: -100
            WifiManager.calculateSignalLevel(rssi, 4)
        } else -1
        val hasSim = telephony?.simState == TelephonyManager.SIM_STATE_READY
        bar.setNetwork(if (hasSim) signalLevel.coerceAtLeast(0) else -1, wifiLevel)
    }
}
