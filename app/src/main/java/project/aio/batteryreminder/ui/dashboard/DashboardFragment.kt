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
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import project.aio.batteryreminder.R
import project.aio.batteryreminder.databinding.FragmentDashboardBinding
import project.aio.batteryreminder.utils.PredictionEngine
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

@AndroidEntryPoint
class DashboardFragment : Fragment() {

    @Inject lateinit var predictionEngine: PredictionEngine
    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private var isBenchmarking = false

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnRunBenchmark.setOnClickListener {
            val intent = requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

            if (!isCharging) {
                Toast.makeText(context, "Please plug in a charger first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            runCableBenchmark()
        }
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
        // Prevent UI jitter if benchmark is running
        if (isBenchmarking) return

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = (level * 100) / scale

        binding.progressCircle.progress = pct
        binding.tvPercentage.text = "$pct%"

        val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val tempInt = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val tempC = tempInt / 10.0

        binding.tvVoltageVal.text = "${voltageMv}mV"
        binding.tvTempVal.text = "${tempC}°C"

        val tempColor = when {
            tempC < 35 -> R.color.white
            tempC < 40 -> R.color.orange
            else -> R.color.red
        }
        binding.tvTempVal.setTextColor(ContextCompat.getColor(requireContext(), tempColor))

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        binding.tvStatusVal.text = if (isCharging) "CHARGING" else "DRAINING"
        binding.tvStatusVal.setTextColor(ContextCompat.getColor(requireContext(), if(isCharging) R.color.green else R.color.white))

        val watts = calculateWatts(voltageMv)
        binding.tvWattageVal.text = String.format("%.1fW", watts)

        lifecycleScope.launch {
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

    private fun calculateWatts(voltageMv: Int): Double {
        val batteryManager = requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentUa = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val amps = abs(currentUa) / 1_000_000.0
        val volts = voltageMv / 1000.0
        return amps * volts
    }

    private fun runCableBenchmark() {
        isBenchmarking = true
        binding.btnRunBenchmark.isEnabled = false
        binding.btnRunBenchmark.text = "TESTING..."
        binding.progressBenchmark.visibility = View.VISIBLE
        binding.progressBenchmark.progress = 0
        binding.tvBenchmarkStatus.text = "Sampling Power Stability..."
        binding.tvBenchmarkGrade.text = "--"
        binding.tvBenchmarkGrade.setTextColor(ContextCompat.getColor(requireContext(), R.color.gray_light))

        lifecycleScope.launch {
            val samples = mutableListOf<Double>()
            // Sample for 4 seconds (20 samples)
            for (i in 1..20) {
                val intent = requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
                val watts = calculateWatts(voltage)
                samples.add(watts)

                binding.progressBenchmark.progress = (i * 5) // 20 * 5 = 100
                delay(200)
            }

            // Analyze Data
            val average = samples.average()

            // Calculate Standard Deviation (Jitter)
            var sumDiffs = 0.0
            for (s in samples) {
                sumDiffs += (s - average).pow(2)
            }
            val stdDev = sqrt(sumDiffs / samples.size)

            // Grading Logic (Lower StdDev = Better Cable)
            val grade: String
            val colorRes: Int
            val message: String

            when {
                stdDev < 0.2 -> {
                    grade = "A"
                    colorRes = R.color.grade_a
                    message = "Excellent Stability"
                }
                stdDev < 0.5 -> {
                    grade = "B"
                    colorRes = R.color.grade_b
                    message = "Good Quality"
                }
                stdDev < 1.0 -> {
                    grade = "C"
                    colorRes = R.color.grade_c
                    message = "Average / Unstable"
                }
                else -> {
                    grade = "F"
                    colorRes = R.color.grade_f
                    message = "High Instability (Bad Cable)"
                }
            }

            // Display Result
            binding.tvBenchmarkGrade.text = grade
            binding.tvBenchmarkGrade.setTextColor(ContextCompat.getColor(requireContext(), colorRes))

            binding.tvBenchmarkStatus.text = "$message\nAvg: ${String.format("%.1f", average)}W  Jitter: ±${String.format("%.2f", stdDev)}"

            binding.btnRunBenchmark.text = "TEST AGAIN"
            binding.btnRunBenchmark.isEnabled = true
            binding.progressBenchmark.visibility = View.GONE
            isBenchmarking = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}