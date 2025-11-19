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
import project.aio.batteryreminder.ui.alert.AlertActivity
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class BatteryMonitorService : Service() {

    @Inject lateinit var preferencesManager: PreferencesManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var thresholds = listOf<Threshold>()
    private var lastLevel = -1
    private var isAlertActive = false

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
    }

    private fun checkBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = ((level * 100) / scale.toFloat()).toInt()

        // Initialize lastLevel on first run
        if (lastLevel == -1) {
            lastLevel = pct
            return
        }

        // Only check if level changed
        if (pct != lastLevel && !isAlertActive) {
            // Check if we crossed any threshold
            // Case 1: Discharging (lastLevel > pct) -> check if we crossed DOWN generic threshold
            // Case 2: Charging (lastLevel < pct) -> check if we crossed UP generic threshold

            for (t in thresholds) {
                val target = t.percentage
                // Check if we just passed this target
                val crossedDown = lastLevel > target && pct <= target
                val crossedUp = lastLevel < target && pct >= target

                if (crossedDown || crossedUp) {
                    triggerAlert("Battery Alert: $pct%")
                    break // Only trigger once per update
                }
            }
        }

        // Reset alert lock if we moved away significantly (hysteresis of 2%)
        // This prevents alert loop if battery fluctuates between 19-20%
        /* In a real app, we'd implement specific hysteresis logic */
        if (isAlertActive) {
            // Simple reset for now: if user dismissed activity, we rely on that.
            // But here we just auto-reset if level changes by 2%
            if (abs(pct - lastLevel) >= 2) {
                isAlertActive = false
            }
        }

        lastLevel = pct
    }

    private fun triggerAlert(message: String) {
        isAlertActive = true
        val alertIntent = Intent(this, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("message", message)
        }
        startActivity(alertIntent)
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