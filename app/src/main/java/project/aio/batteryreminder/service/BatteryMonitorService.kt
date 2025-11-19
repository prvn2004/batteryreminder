// ===== batteryreminder\app\src\main\java\project\aio\batteryreminder\service\BatteryMonitorService.kt =====
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

@AndroidEntryPoint
class BatteryMonitorService : Service() {

    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var overlayService: OverlayService
    @Inject lateinit var predictionEngine: PredictionEngine

    // Use Default dispatcher for CPU intensive math, IO for DB writes
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var thresholds = listOf<Threshold>()
    private var emergencySecondsLimit = 120
    private var lastLevel = -1
    private var lastStatus = -1
    private var isAlertActive = false
    private var isEmergencyActive = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            // ULTRA-LOW POWER CHECK:
            // Extract only what is needed to decide if we proceed.
            // Battery broadcasts fire on Temp/Voltage changes too. We ignore those to save battery.
            val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val rawStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

            if (rawLevel == lastLevel && rawStatus == lastStatus) {
                return // Exit immediately, do zero work.
            }

            checkBattery(intent, rawLevel, rawStatus)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification("Optimized monitoring active..."))

        // Register receiver
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)

        // Collect settings lazily
        serviceScope.launch(Dispatchers.IO) {
            launch { preferencesManager.thresholds.collect { thresholds = it } }
            launch { preferencesManager.emergencyThreshold.collect { emergencySecondsLimit = it } }
        }
    }

    private fun checkBattery(intent: Intent, rawLevel: Int, status: Int) {
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = ((rawLevel * 100) / scale.toFloat()).toInt()
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        // Update state trackers
        val levelChanged = pct != lastLevel
        lastLevel = pct
        lastStatus = status

        if (isCharging) {
            // Reset prediction buffer when charging
            if (isEmergencyActive) isEmergencyActive = false
            if (isAlertActive) overlayService.removeOverlay()
            predictionEngine.resetBuffer()
            return
        }

        // --- PROCESSING LOGIC (Only runs if discharging) ---

        if (levelChanged) {
            serviceScope.launch {
                // 1. Update Prediction Engine (In-Memory - Fast)
                predictionEngine.addHistoryPoint(System.currentTimeMillis(), pct)

                // 2. Asynchronously Log to DB (IO - Slow, but detached)
                predictionEngine.logToDb(pct, false)

                // 3. Check standard thresholds
                checkThresholds(pct)

                // 4. Check Prediction (Only at low battery to save CPU)
                if (pct <= 20) {
                    val secondsLeft = predictionEngine.estimateTimeRemainingLinearRegression()
                    if (secondsLeft in 1 until emergencySecondsLimit && !isEmergencyActive) {
                        isEmergencyActive = true
                        withContext(Dispatchers.Main) {
                            triggerEmergencyPrediction(secondsLeft)
                        }
                    }
                }
            }
        }
    }

    private fun checkThresholds(pct: Int) {
        if (isAlertActive || isEmergencyActive) return

        for (t in thresholds) {
            // Simple exact match logic for efficiency, or crossing logic
            // Assuming we trigger exactly on the drop
            if (pct == t.percentage) {
                triggerAlert("BATTERY EVENT", pct)
                break
            }
        }
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
            overlayService.showOverlay("SHUTDOWN IMMINENT", secondsLeft.toInt(), isEmergency = true)
        }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, "battery_monitor_channel")
            .setContentTitle("AIO Monitor")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_battery_std)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true) // Less intrusive
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