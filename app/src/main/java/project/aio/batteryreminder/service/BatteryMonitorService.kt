package project.aio.batteryreminder.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import project.aio.batteryreminder.R
import project.aio.batteryreminder.data.PreferencesManager
import project.aio.batteryreminder.data.model.Threshold
import project.aio.batteryreminder.ui.MainActivity
import project.aio.batteryreminder.ui.overlay.OverlayService
import project.aio.batteryreminder.utils.PredictionEngine
import java.util.Calendar
import javax.inject.Inject

@AndroidEntryPoint
class BatteryMonitorService : Service() {

    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var overlayService: OverlayService
    @Inject lateinit var predictionEngine: PredictionEngine

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var powerManager: PowerManager

    private var thresholds = listOf<Threshold>()
    private var emergencySecondsLimit = 120
    private var lastLevel = -1
    private var lastStatus = -1
    private var alertedLevels = HashSet<Int>()

    // Feature Flags
    private var ghostDrainEnabled = true
    private var thermalAlarmEnabled = true
    private var bedtimeEnabled = true

    // State Trackers
    private var screenOffTime: Long = 0
    private var screenOffLevel: Int = 0
    private var isAlertActive = false
    private var isEmergencyActive = false
    private var hasTriggeredBedtimeToday = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            // Thermal Watchdog (Check independently of level change)
            if (thermalAlarmEnabled) checkThermal(intent)

            val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val rawStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

            if (rawLevel == lastLevel && rawStatus == lastStatus) return
            checkBattery(intent, rawLevel, rawStatus)
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Start Ghost Drain Tracking
                    screenOffTime = System.currentTimeMillis()
                    screenOffLevel = lastLevel
                    predictionEngine.resetBuffer()
                }
                Intent.ACTION_SCREEN_ON -> {
                    // Check Ghost Drain
                    if (ghostDrainEnabled && screenOffTime > 0) {
                        checkGhostDrain()
                    }
                    predictionEngine.resetBuffer()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        startForeground(1, createNotification("AIO Monitor Active"))

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, screenFilter)

        // Collect Settings
        serviceScope.launch(Dispatchers.IO) {
            launch { preferencesManager.thresholds.collect { thresholds = it } }
            launch { preferencesManager.emergencyThreshold.collect { emergencySecondsLimit = it } }

            // New Feature Toggles
            launch { preferencesManager.ghostDrainEnabled.collect { ghostDrainEnabled = it } }
            launch { preferencesManager.thermalAlarmEnabled.collect { thermalAlarmEnabled = it } }
            launch { preferencesManager.bedtimeReminderEnabled.collect { bedtimeEnabled = it } }
        }
    }

    private fun checkBattery(intent: Intent, rawLevel: Int, status: Int) {
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = ((rawLevel * 100) / scale.toFloat()).toInt()
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val levelChanged = pct != lastLevel
        lastLevel = pct
        lastStatus = status

        if (isCharging) {
            if (isEmergencyActive) isEmergencyActive = false
            if (isAlertActive) overlayService.removeOverlay()
            predictionEngine.resetBuffer()
            alertedLevels.clear()
            hasTriggeredBedtimeToday = false // Reset bedtime flag on charge
            return
        }

        if (pct > (lastLevel - 1)) alertedLevels.removeIf { it < pct }

        if (levelChanged) {
            serviceScope.launch {
                predictionEngine.logToDb(pct, false)
                checkThresholds(pct)
                checkBedtime(pct) // Check bedtime logic

                val isScreenOn = powerManager.isInteractive
                val isCritical = pct <= 10

                if (isScreenOn || isCritical) {
                    predictionEngine.addHistoryPoint(System.currentTimeMillis(), pct)
                    if (pct <= 20) {
                        val secondsLeft = predictionEngine.estimateTimeRemainingWeighted()
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
    }

    // --- FEATURE IMPLEMENTATIONS ---

    private fun checkThermal(intent: Intent) {
        val tempInt = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val tempC = tempInt / 10.0

        // Threshold: 42.0 C
        if (tempC >= 42.0 && !isAlertActive) {
            triggerAlert("OVERHEATING\n${tempC}°C", lastLevel)
        }
    }

    private fun checkGhostDrain() {
        val now = System.currentTimeMillis()
        val durationHrs = (now - screenOffTime) / (1000.0 * 60 * 60)
        val drop = screenOffLevel - lastLevel

        // Logic: If slept for > 30 mins AND drain rate > 2% per hour
        if (durationHrs > 0.5 && (drop / durationHrs) > 2.0) {
            sendNotification(
                "High Background Drain",
                "Lost $drop% in ${String.format("%.1f", durationHrs)} hrs while idle."
            )
        }
        screenOffTime = 0 // Reset
    }

    private fun checkBedtime(pct: Int) {
        if (!bedtimeEnabled || hasTriggeredBedtimeToday || pct > 30) return

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        // Between 9 PM (21) and 11 PM (23)
        if (hour in 21..23) {
            sendNotification("Bedtime Battery Check", "Battery is $pct%. Charge now for the morning.")
            hasTriggeredBedtimeToday = true
        }
    }

    private fun checkThresholds(pct: Int) {
        if (isAlertActive || isEmergencyActive) return
        for (t in thresholds) {
            if (pct == t.percentage && !alertedLevels.contains(pct)) {
                triggerAlert("BATTERY EVENT", pct)
                alertedLevels.add(pct)
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

    private fun sendNotification(title: String, content: String) {
        val notification = NotificationCompat.Builder(this, "battery_monitor_channel")
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_battery_std)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        getSystemService(NotificationManager::class.java).notify(System.currentTimeMillis().toInt(), notification)
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
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(screenStateReceiver) } catch (e: Exception) {}
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}