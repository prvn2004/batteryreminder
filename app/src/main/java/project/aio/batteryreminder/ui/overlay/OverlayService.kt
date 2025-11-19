// ===== batteryreminder\app\src\main\java\project\aio\batteryreminder\ui\overlay\OverlayService.kt =====
package project.aio.batteryreminder.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
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

    fun showOverlay(message: String, percentageOrSeconds: Int, isEmergency: Boolean = false) {
        if (overlayView != null) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val themeContext = ContextThemeWrapper(context, R.style.Theme_BatteryReminder)
        val layoutInflater = LayoutInflater.from(themeContext)

        binding = ViewBatteryOverlayBinding.inflate(layoutInflater)
        overlayView = binding?.root

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Standard params - NO screen brightness dimming here
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

            val isLow = if (!isEmergency) value <= 20 else true
            val color = if (isLow) 0xFFFF0000.toInt() else 0xFFFFFFFF.toInt()
            tvPercentage.setTextColor(color)
            borderView.setBorderColor(color)

            btnDismiss.setOnClickListener { removeOverlay() }
        }

        job = scope.launch {
            var duration = 30
            var sound = false
            var flash = false
            var vib = false
            var soundUri = ""

            val prefs = preferencesManager
            val settingsJob = launch {
                launch { prefs.alertDuration.collect { duration = if (isEmergency) -1 else it } }
                launch { prefs.soundEnabled.collect { sound = if (isEmergency) false else it } }
                launch { prefs.flashEnabled.collect { flash = if (isEmergency) false else it } }
                launch { prefs.vibrationEnabled.collect { vib = it } }
                launch { prefs.soundUri.collect { soundUri = it } }
            }
            delay(100)
            settingsJob.cancel()

            if (sound) alertManager.playSound(soundUri)
            if (vib) alertManager.vibrate()
            if (flash) alertManager.startStrobe(this)

            launch {
                var timeLeft = value
                // Ensure text is large for countdown
                if(isEmergency) binding?.tvPercentage?.textSize = 72f

                while (isActive) {
                    if (isEmergency) {
                        val mins = timeLeft / 60
                        val secs = timeLeft % 60
                        val timeStr = String.format("%02d:%02d", mins, secs)

                        if (binding?.tvPercentage?.text != timeStr) {
                            binding?.tvPercentage?.text = timeStr
                        }

                        if (timeLeft > 0) timeLeft--

                        if (vib && timeLeft % 5 == 0) {
                            alertManager.vibrateHeartbeat()
                        }
                    } else {
                        val str = "$value%"
                        if (binding?.tvPercentage?.text != str) binding?.tvPercentage?.text = str
                    }

                    binding?.borderView?.animate()?.alpha(1.0f)?.setDuration(200)?.start()

                    val flashAlpha = if (isEmergency) 0.05f else 0.1f
                    binding?.whiteFlashOverlay?.alpha = flashAlpha

                    delay(200)

                    binding?.borderView?.animate()?.alpha(0.4f)?.setDuration(200)?.start()
                    binding?.whiteFlashOverlay?.alpha = 0.0f

                    delay(800)
                }
            }

            if (duration != -1 && !isEmergency) {
                delay(duration * 1000L)
                removeOverlay()
            }
        }
    }

    fun removeOverlay() {
        try {
            job?.cancel()
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