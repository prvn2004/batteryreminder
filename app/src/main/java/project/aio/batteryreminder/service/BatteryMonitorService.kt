package project.aio.batteryreminder.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import project.aio.batteryreminder.R
import project.aio.batteryreminder.data.PreferencesManager
import project.aio.batteryreminder.data.model.Threshold
import project.aio.batteryreminder.ui.MainActivity
import project.aio.batteryreminder.ui.overlay.OverlayService
import project.aio.batteryreminder.utils.PredictionEngine
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class BatteryMonitorService : Service() {

    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var overlayService: OverlayService
    @Inject lateinit var predictionEngine: PredictionEngine // NEW

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var thresholds = listOf<Threshold>()
    private var emergencySecondsLimit = 120
    private var lastLevel = -1
    private var isAlertActive = false
    private var isEmergencyActive = false // To track prediction alert

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { checkBattery(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification("Monitoring active..."))
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        serviceScope.launch {
            preferencesManager.thresholds.collect { thresholds = it }
        }
        serviceScope.launch {
            preferencesManager.emergencyThreshold.collect { emergencySecondsLimit = it }
        }
    }

    private fun checkBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val pct = ((level * 100) / scale.toFloat()).toInt()

        if (lastLevel == -1) {
            lastLevel = pct
            return
        }

        // 1. LOG DATA FOR LEARNING (If level changed)
        if (pct != lastLevel) {
            serviceScope.launch {
                predictionEngine.logBatteryState(pct, isCharging)
            }
        }

        // 2. STANDARD THRESHOLD CHECKS
        if (pct != lastLevel && !isAlertActive && !isEmergencyActive) {
            for (t in thresholds) {
                val target = t.percentage
                val crossedDown = lastLevel > target && pct <= target
                val crossedUp = lastLevel < target && pct >= target

                if (crossedDown || crossedUp) {
                    triggerAlert("BATTERY EVENT", pct)
                    break
                }
            }
        }

        // 3. INTELLIGENT PREDICTION CHECK (Only when discharging)
        if (!isCharging && pct < 15) { // Only run expensive math on low battery
            serviceScope.launch {
                val secondsLeft = predictionEngine.calculateTimeRemaining(pct)

                // If valid prediction AND time is less than limit AND not already alerting
                if (secondsLeft > 0 && secondsLeft < emergencySecondsLimit && !isEmergencyActive) {
                    isEmergencyActive = true
                    triggerEmergencyPrediction(secondsLeft)
                }
            }
        } else if (isCharging) {
            isEmergencyActive = false // Reset if charged
            if (isAlertActive) overlayService.removeOverlay() // Auto dismiss overlay if plugged in
        }

        lastLevel = pct
    }

    private fun triggerAlert(message: String, percentage: Int) {
        isAlertActive = true
        if (android.provider.Settings.canDrawOverlays(this)) {
            CoroutineScope(Dispatchers.Main).launch {
                overlayService.showOverlay(message, percentage, isEmergency = false)
            }
        }
    }

    private fun triggerEmergencyPrediction(secondsLeft: Long) {
        if (android.provider.Settings.canDrawOverlays(this)) {
            CoroutineScope(Dispatchers.Main).launch {
                overlayService.showOverlay("SHUTDOWN IMMINENT", secondsLeft.toInt(), isEmergency = true)
            }
        }
    }

    // ... createNotification / onDestroy ...
    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, "battery_monitor_channel")
            .setContentTitle("AIO Monitor")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_battery_std)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) {}
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}