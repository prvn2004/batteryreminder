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
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import project.aio.batteryreminder.R
import project.aio.batteryreminder.databinding.FragmentDashboardBinding
import project.aio.batteryreminder.utils.CableBenchmarkEngine
import project.aio.batteryreminder.utils.PredictionEngine
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class DashboardFragment : Fragment() {

    @Inject lateinit var predictionEngine: PredictionEngine
    @Inject lateinit var benchmarkEngine: CableBenchmarkEngine

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private var isBenchmarking = false
    private var benchmarkJob: Job? = null

    // Cache the last broadcast so our live loop has Voltage/Temp data
    private var lastBatteryIntent: Intent? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                lastBatteryIntent = it
                // Immediate update on event
                updateUI(it)
            }
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

        // 1. Start Live Polling Loop (The FIX for live data)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    // Force UI update using last known intent (or fetch new sticky)
                    val sticky = lastBatteryIntent ?: requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    sticky?.let { updateUI(it) }
                    delay(1000) // Update every 1 second
                }
            }
        }

        binding.btnRunBenchmark.setOnClickListener {
            if (isBenchmarking) {
                stopBenchmark()
            } else {
                startBenchmark()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lastBatteryIntent = requireContext().registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onPause() {
        super.onPause()
        requireContext().unregisterReceiver(batteryReceiver)
        if(isBenchmarking) stopBenchmark()
    }

    private fun updateUI(intent: Intent) {
        if (isBenchmarking) return // Don't refresh main UI while running test

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

        // Calculates LIVE watts (fetching current instantly)
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
        // Query instant current property
        val batteryManager = requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentUa = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

        val amps = abs(currentUa) / 1_000_000.0
        val volts = voltageMv / 1000.0
        return amps * volts
    }

    // --- BENCHMARK LOGIC ---

    private fun startBenchmark() {
        val intent = requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        if (!isCharging) {
            Toast.makeText(context, "Please connect charger first!", Toast.LENGTH_SHORT).show()
            return
        }

        isBenchmarking = true
        binding.btnRunBenchmark.text = "CANCEL TEST"
        binding.progressBenchmark.visibility = View.VISIBLE
        binding.layoutBenchmarkResults.visibility = View.GONE
        binding.tvBenchmarkWarning.visibility = View.GONE
        binding.tvBenchmarkGrade.text = "..."
        binding.tvBenchmarkGrade.setTextColor(ContextCompat.getColor(requireContext(), R.color.gray_light))

        benchmarkJob = lifecycleScope.launch {
            benchmarkEngine.runBenchmark().collect { state ->
                when(state) {
                    is CableBenchmarkEngine.BenchmarkState.Running -> {
                        binding.progressBenchmark.progress = state.progress
                        binding.tvBenchmarkStatus.text = "Analyzing: ${String.format("%.1f", state.currentWattage)}W"
                    }
                    is CableBenchmarkEngine.BenchmarkState.Completed -> {
                        isBenchmarking = false
                        displayResults(state.result)
                    }
                    is CableBenchmarkEngine.BenchmarkState.Error -> {
                        isBenchmarking = false
                        binding.tvBenchmarkStatus.text = "Error: ${state.message}"
                        resetBenchmarkUI()
                    }
                    is CableBenchmarkEngine.BenchmarkState.Idle -> {}
                }
            }
        }
    }

    private fun stopBenchmark() {
        benchmarkJob?.cancel()
        resetBenchmarkUI()
        binding.tvBenchmarkStatus.text = "Test Cancelled"
    }

    private fun displayResults(result: CableBenchmarkEngine.BenchmarkResult) {
        binding.progressBenchmark.visibility = View.GONE
        binding.btnRunBenchmark.text = "TEST AGAIN"

        val colorRes = when (result.grade.replace("+", "")) {
            "A" -> R.color.grade_a
            "B" -> R.color.grade_b
            "C" -> R.color.grade_c
            else -> R.color.grade_f
        }

        binding.tvBenchmarkGrade.text = result.grade
        binding.tvBenchmarkGrade.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
        binding.tvBenchmarkStatus.text = "Score: ${result.score}/100"

        binding.layoutBenchmarkResults.visibility = View.VISIBLE
        binding.tvResultSpeed.text = "Avg: ${String.format("%.1f", result.avgWattage)}W"
        binding.tvResultRipple.text = "Ripple: ${result.voltageRipple}mV"
        binding.tvResultStability.text = "Stability: ${String.format("%.1f", result.stabilityScore)}/10"
        binding.tvResultType.text = result.chargingType

        if (result.warning != null) {
            binding.tvBenchmarkWarning.visibility = View.VISIBLE
            binding.tvBenchmarkWarning.text = "Note: ${result.warning}"
        }
    }

    private fun resetBenchmarkUI() {
        isBenchmarking = false
        binding.progressBenchmark.visibility = View.GONE
        binding.btnRunBenchmark.text = "START DIAGNOSTIC"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}