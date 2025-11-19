package project.aio.batteryreminder.ui.dashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import project.aio.batteryreminder.R
import project.aio.batteryreminder.databinding.FragmentDashboardBinding
import project.aio.batteryreminder.utils.PredictionEngine
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class DashboardFragment : Fragment() {

    @Inject lateinit var predictionEngine: PredictionEngine
    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { updateUI(it) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        requireContext().registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onPause() {
        super.onPause()
        requireContext().unregisterReceiver(batteryReceiver)
    }

    private fun updateUI(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = (level * 100) / scale

        // 1. Main Circle Progress
        binding.progressCircle.progress = pct
        binding.tvPercentage.text = "$pct%"

        // 2. Voltage & Temp
        val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val tempInt = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val tempC = tempInt / 10.0

        binding.tvVoltageVal.text = "${voltageMv}mV"
        binding.tvTempVal.text = "${tempC}°C"

        // Colorize Temp
        val tempColor = when {
            tempC < 35 -> R.color.white
            tempC < 40 -> R.color.orange
            else -> R.color.red
        }
        binding.tvTempVal.setTextColor(ContextCompat.getColor(requireContext(), tempColor))

        // 3. Charging Status
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        binding.tvStatusVal.text = if (isCharging) "CHARGING" else "DRAINING"
        binding.tvStatusVal.setTextColor(ContextCompat.getColor(requireContext(), if(isCharging) R.color.green else R.color.white))

        // 4. LIVE WATTAGE CALCULATION
        val batteryManager = requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentUa = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

        // Watts = (uA / 1,000,000) * (mV / 1000)
        val amps = abs(currentUa) / 1_000_000.0
        val volts = voltageMv / 1000.0
        val watts = amps * volts

        binding.tvWattageVal.text = String.format("%.1fW", watts)

        // 5. Time To Full / Empty
        lifecycleScope.launch {
            // We add the current point to buffer so prediction works immediately on UI even if service is slow
            predictionEngine.addHistoryPoint(System.currentTimeMillis(), pct)
            val secondsLeft = predictionEngine.estimateTimeRemainingWeighted()

            if (secondsLeft > 0) {
                val mins = secondsLeft / 60
                val hrs = mins / 60
                val remMins = mins % 60

                val timeText = if (hrs > 0) "${hrs}h ${remMins}m" else "${remMins}m"
                val label = if (isCharging) "Time to Full" else "Time to Empty"

                binding.tvPredictionLabel.text = label
                binding.tvPredictionVal.text = timeText
            } else {
                binding.tvPredictionLabel.text = "Prediction"
                binding.tvPredictionVal.text = "Learning..."
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}