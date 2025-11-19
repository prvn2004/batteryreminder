package project.aio.batteryreminder.ui.alert

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import project.aio.batteryreminder.data.PreferencesManager
import project.aio.batteryreminder.databinding.ActivityAlertBinding
import project.aio.batteryreminder.utils.AlertManager
import javax.inject.Inject

@AndroidEntryPoint
class AlertActivity : AppCompatActivity() {

    @Inject lateinit var alertManager: AlertManager
    @Inject lateinit var preferencesManager: PreferencesManager
    private lateinit var binding: ActivityAlertBinding
    private var isFlashingUI = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake screen
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        binding = ActivityAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val message = intent.getStringExtra("message") ?: "BATTERY ALERT"
        binding.tvAlertMessage.text = message

        startAlerts(message)

        binding.btnDismiss.setOnClickListener {
            stopAlerts()
            finish()
        }
    }

    private fun startAlerts(message: String) {
        lifecycleScope.launch {
            preferencesManager.soundEnabled.collect { if(it) alertManager.playSound() }
        }
        lifecycleScope.launch {
            preferencesManager.vibrationEnabled.collect { if(it) alertManager.vibrate() }
        }
        lifecycleScope.launch {
            preferencesManager.flashEnabled.collect { if(it) alertManager.startStrobe(this) }
        }
        lifecycleScope.launch {
            preferencesManager.ttsEnabled.collect { if(it) alertManager.speak(message) }
        }

        // Screen Flicker Logic
        // Inside startAlerts in AlertActivity.kt
        lifecycleScope.launch {
            preferencesManager.soundEnabled.collect { enabled ->
                if (enabled) {
                    // Collect URI only if enabled
                    preferencesManager.soundUri.collect { uri ->
                        alertManager.playSound(uri)
                    }
                }
            }
        }
    }

    private fun stopAlerts() {
        isFlashingUI = false
        alertManager.stopSound()
        alertManager.stopVibrate()
        alertManager.stopStrobe()
    }

    override fun onDestroy() {
        stopAlerts()
        super.onDestroy()
    }
}