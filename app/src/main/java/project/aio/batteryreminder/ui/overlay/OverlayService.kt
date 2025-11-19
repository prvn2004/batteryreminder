// ===== batteryreminder\app\src\main\java\project\aio\batteryreminder\ui\overlay\OverlayService.kt =====
package project.aio.batteryreminder.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import project.aio.batteryreminder.R
import project.aio.batteryreminder.data.PreferencesManager
import project.aio.batteryreminder.databinding.ViewBatteryOverlayBinding
import project.aio.batteryreminder.utils.AlertManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverlayService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alertManager: AlertManager,
    private val preferencesManager: PreferencesManager
) {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var binding: ViewBatteryOverlayBinding? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var originalBrightness: Int = -1

    /**
     * Shows the alert overlay.
     * @param message Title text
     * @param percentageOrSeconds Battery % for standard alerts, or Seconds Left for emergency.
     * @param isEmergency If true, enables aggressive battery saving (dimming) and countdown logic.
     */
    fun showOverlay(message: String, percentageOrSeconds: Int, isEmergency: Boolean = false) {
        // Prevent double overlays
        if (overlayView != null) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Use Theme wrapper to ensure styles apply correctly
        val themeContext = ContextThemeWrapper(context, R.style.Theme_BatteryReminder)
        val layoutInflater = LayoutInflater.from(themeContext)

        binding = ViewBatteryOverlayBinding.inflate(layoutInflater)
        overlayView = binding?.root

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        // BATTERY OPTIMIZATION:
        // If this is a critical "Shutdown Imminent" alert, we actually want to SAVE battery
        // so the user has time to plug in.
        // 1. Set Window brightness to absolute minimum (0.01f).
        if (isEmergency) {
            params.screenBrightness = 0.01f
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        try {
            windowManager?.addView(overlayView, params)
            startAlertLogic(message, percentageOrSeconds, isEmergency)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startAlertLogic(message: String, value: Int, isEmergency: Boolean) {
        binding?.apply {
            tvMessage.text = message.uppercase()

            // Styling
            val isLow = if (!isEmergency) value <= 20 else true
            val color = if (isLow) 0xFFFF0000.toInt() else 0xFFFFFFFF.toInt()
            tvPercentage.setTextColor(color)
            borderView.setBorderColor(color)

            btnDismiss.setOnClickListener { removeOverlay() }
        }

        // Try System Wide Dimming (Backup if Window Attributes don't cut enough power)
        if (isEmergency) {
            attemptSystemDimming()
        }

        job = scope.launch {
            // 1. Load Preferences Asynchronously (Don't block UI)
            var duration = 30
            var sound = false
            var flash = false
            var vib = false
            var soundUri = ""
            var autoDim = true

            val prefs = preferencesManager

            // Parallel collection of settings
            val settingsJob = launch {
                launch { prefs.alertDuration.collect { duration = if (isEmergency) -1 else it } }
                launch { prefs.soundEnabled.collect { sound = if (isEmergency) false else it } } // No sound in emergency (save juice)
                launch { prefs.flashEnabled.collect { flash = if (isEmergency) false else it } } // No flash in emergency (save juice)
                launch { prefs.vibrationEnabled.collect { vib = it } }
                launch { prefs.soundUri.collect { soundUri = it } }
                launch { prefs.autoDimEnabled.collect { autoDim = it } }
            }

            // Give a tiny moment for prefs to load, then cancel the collector job so we don't keep watching settings
            delay(100)
            settingsJob.cancel()

            // 2. Trigger Hardware Alerts
            if (sound) alertManager.playSound(soundUri)
            if (vib) alertManager.vibrate() // Initial vibration
            if (flash) alertManager.startStrobe(this)

            // 3. The Main Loop (Optimized for CPU sleeping)
            launch {
                var timeLeft = value

                // Pre-format text view settings to avoid doing it in loop
                if(isEmergency) binding?.tvPercentage?.textSize = 72f

                while (isActive) {
                    // --- UI Updates ---
                    if (isEmergency) {
                        val mins = timeLeft / 60
                        val secs = timeLeft % 60
                        val timeStr = String.format("%02d:%02d", mins, secs)

                        // Only update text if it actually changed (saves layout passes)
                        if (binding?.tvPercentage?.text != timeStr) {
                            binding?.tvPercentage?.text = timeStr
                        }

                        if (timeLeft > 0) timeLeft--

                        // Periodic Vibration (Heartbeat) every 5 seconds for emergency
                        // Less frequent than before to save battery
                        if (vib && timeLeft % 5 == 0) {
                            alertManager.vibrateHeartbeat()
                        }
                    } else {
                        // For standard % alerts, just ensure text is set once
                        val str = "$value%"
                        if (binding?.tvPercentage?.text != str) binding?.tvPercentage?.text = str
                    }

                    // --- Efficient Animation ---
                    // Pulse the border
                    binding?.borderView?.animate()?.alpha(1.0f)?.setDuration(200)?.start()

                    // Flash effect (Dimmed significantly for emergency)
                    val flashAlpha = if (isEmergency) 0.02f else 0.1f
                    binding?.whiteFlashOverlay?.alpha = flashAlpha

                    delay(200) // Wait for fade in

                    // Fade out
                    binding?.borderView?.animate()?.alpha(0.4f)?.setDuration(200)?.start()
                    binding?.whiteFlashOverlay?.alpha = 0.0f

                    // --- BATTERY SAVING SLEEP ---
                    // Sleep for the remainder of the second.
                    // 200ms used above. Sleep 800ms.
                    // This allows the CPU to drop to low-power state between ticks.
                    delay(800)
                }
            }

            // Auto-dismiss for non-emergency alerts
            if (duration != -1 && !isEmergency) {
                delay(duration * 1000L)
                removeOverlay()
            }
        }
    }

    private fun attemptSystemDimming() {
        // Only write settings if permitted.
        // This physically lowers the voltage to the backlight/pixels on supported devices.
        if (Settings.System.canWrite(context)) {
            try {
                originalBrightness = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                // Set to absolute minimum (1)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 1)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun restoreBrightness() {
        if (originalBrightness != -1 && Settings.System.canWrite(context)) {
            try {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, originalBrightness)
            } catch(e: Exception) {}
            originalBrightness = -1
        }
    }

    fun removeOverlay() {
        try {
            restoreBrightness()
            job?.cancel() // Cancel all coroutines/animations

            // Stop hardware
            alertManager.stopSound()
            alertManager.stopVibrate()
            alertManager.stopStrobe()

            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
            }
            overlayView = null
            binding = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}