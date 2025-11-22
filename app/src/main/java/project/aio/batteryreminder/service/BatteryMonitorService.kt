package project.aio.batteryreminder.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
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
    private var wakeLock: PowerManager.WakeLock? = null

    private var thresholds = listOf<Threshold>()
    private var emergencySecondsLimit = 120
    private var lastLevel = -1
    private var lastStatus = -1
    private var alertedLevels = HashSet<Int>()

    // Feature Flags
    private var ghostDrainEnabled = true
    private var thermalAlarmEnabled = true
    private var bedtimeEnabled = true

    // State
    private var screenOffTime: Long = 0
    private var screenOffLevel: Int = 0
    private var isAlertActive = false
    private var isEmergencyActive = false
    private var hasTriggeredBedtimeToday = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            // 1. Aggressive WakeLock: Keep CPU awake while processing
            acquireWakeLock(5000)

            if (thermalAlarmEnabled) checkThermal(intent)

            val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val rawStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

            // Process even if level hasn't changed (to keep 'alive' logic running)
            checkBattery(intent, rawLevel, rawStatus)
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOffTime = System.currentTimeMillis()
                    screenOffLevel = lastLevel
                    // 2. Aggressive: When screen dies, schedule the Watchdog immediately
                    scheduleWatchdog()
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (ghostDrainEnabled && screenOffTime > 0) checkGhostDrain()
                    // Cancel watchdog to save battery while user is active (Service is safe while screen is on)
                    // Optional: Keep it running if you want 100% paranoia
                    scheduleWatchdog()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        startForeground(1, createNotification("AIO Monitor Active"))

        // 3. Aggressive: Schedule the next heartbeat
        scheduleWatchdog()

        // START_REDELIVER_INTENT: If system kills us, restart with the same intent
        return START_REDELIVER_INTENT
    }

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // Partial WakeLock: CPU ON, Screen OFF
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryApp::MonitorLock")

        ensureNotificationChannel()

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, screenFilter)

        serviceScope.launch(Dispatchers.IO) {
            launch { preferencesManager.thresholds.collect { thresholds = it } }
            launch { preferencesManager.emergencyThreshold.collect { emergencySecondsLimit = it } }
            launch { preferencesManager.ghostDrainEnabled.collect { ghostDrainEnabled = it } }
            launch { preferencesManager.thermalAlarmEnabled.collect { thermalAlarmEnabled = it } }
            launch { preferencesManager.bedtimeReminderEnabled.collect { bedtimeEnabled = it } }
        }
    }

    private fun acquireWakeLock(timeout: Long) {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(timeout)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // THE WATCHDOG: This is the "Internet Solution"
    // It sets an alarm that wakes the device from Doze mode every 1 minute to check on the service.
    private fun scheduleWatchdog() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, RestartReceiver::class.java) // Call the Receiver, not Service directly
        val pendingIntent = PendingIntent.getBroadcast(
            this, 777, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = SystemClock.elapsedRealtime() + 60_000L // 1 Minute

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // setExactAndAllowWhileIdle is the "Nuclear" option. It fires even in Doze.
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pendingIntent)
            }
        } catch (e: SecurityException) {
            // Handle Android 12+ Exact Alarm permission denial
            Log.e("BatteryMonitor", "Exact Alarm permission missing")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 4. Aggressive: User swiped app? Restart immediately.
        val intent = Intent(this, RestartReceiver::class.java)
        sendBroadcast(intent)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // 5. Aggressive: System killed us? Restart immediately.
        val intent = Intent(this, RestartReceiver::class.java)
        sendBroadcast(intent)

        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(screenStateReceiver) } catch (e: Exception) {}
        serviceScope.cancel()
        super.onDestroy()
    }

    // --- LOGIC BELOW REMAINS UNCHANGED ---

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "battery_monitor_channel",
                "Battery Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service for monitoring battery levels"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun checkBattery(intent: Intent, rawLevel: Int, status: Int) {
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = ((rawLevel * 100) / scale.toFloat()).toInt()
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val levelChanged = pct != lastLevel
        lastLevel = pct
        lastStatus = status

        if (levelChanged) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(1, createNotification("Battery: $pct%"))
        }

        if (isCharging) {
            if (isEmergencyActive) isEmergencyActive = false
            if (isAlertActive) overlayService.removeOverlay()
            predictionEngine.resetBuffer()
            alertedLevels.clear()
            hasTriggeredBedtimeToday = false
            serviceScope.launch { predictionEngine.logToDb(pct, true) }
            return
        }

        if (pct > (lastLevel - 1)) alertedLevels.removeIf { it < pct }

        if (levelChanged || !isCharging) { // Check even if level hasn't changed
            serviceScope.launch {
                if(levelChanged) {
                    predictionEngine.logToDb(pct, false)
                    predictionEngine.addHistoryPoint(System.currentTimeMillis(), pct)
                }
                checkThresholds(pct)
                checkBedtime(pct)

                val isScreenOn = powerManager.isInteractive
                val isCritical = pct <= 15

                if (isScreenOn || isCritical) {
                    if (pct <= 20) {
                        var secondsLeft = predictionEngine.getHybridTimeRemaining()
                        if (secondsLeft <= 0) {
                            secondsLeft = predictionEngine.estimateTimeRemainingWeighted()
                        }

                        if (secondsLeft > 0 && secondsLeft in 1 until emergencySecondsLimit && !isEmergencyActive) {
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

    private fun checkThermal(intent: Intent) {
        val tempInt = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val tempC = tempInt / 10.0
        if (tempC >= 42.0 && !isAlertActive) {
            triggerAlert("OVERHEATING\n${tempC}°C", lastLevel)
        }
    }

    private fun checkGhostDrain() {
        val now = System.currentTimeMillis()
        val durationHrs = (now - screenOffTime) / (1000.0 * 60 * 60)
        val drop = screenOffLevel - lastLevel

        if (durationHrs > 0.5 && (drop / durationHrs) > 2.0) {
            sendNotification(
                "High Background Drain",
                "Lost $drop% in ${String.format("%.1f", durationHrs)} hrs while idle."
            )
        }
        screenOffTime = 0
    }

    private fun checkBedtime(pct: Int) {
        if (!bedtimeEnabled || hasTriggeredBedtimeToday || pct > 30) return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
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
            .setSmallIcon(R.drawable.battery)
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
            .setSmallIcon(R.drawable.battery)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}