package project.aio.batteryreminder.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

@Singleton
class CableBenchmarkEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    data class BenchmarkResult(
        val score: Int,             // 0 - 100
        val grade: String,          // A+, A, B, C, F
        val avgWattage: Double,
        val maxWattage: Double,
        val voltageRipple: Int,     // mV difference (Max - Min)
        val stabilityScore: Double, // 0.0 - 10.0 (10 is perfect)
        val chargingType: String,   // "Slow", "Fast", "Rapid"
        val warning: String? = null // "Battery too full", "Overheating", etc.
    )

    sealed class BenchmarkState {
        object Idle : BenchmarkState()
        data class Running(val progress: Int, val currentWattage: Double) : BenchmarkState()
        data class Completed(val result: BenchmarkResult) : BenchmarkState()
        data class Error(val message: String) : BenchmarkState()
    }

    fun runBenchmark(): Flow<BenchmarkState> = flow {
        emit(BenchmarkState.Running(0, 0.0))

        // 1. Pre-flight Checks
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 50
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        val pct = (level * 100) / scale.toFloat()
        val temp = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

        var warningMsg: String? = null
        if (pct > 85) warningMsg = "Battery > 85%. Speed limited by saturation phase."
        if (temp > 40) warningMsg = "Device is hot ($temp°C). Thermal throttling likely."

        // 2. Warm up (Stabilize connection) - 2 seconds
        for (i in 1..10) {
            emit(BenchmarkState.Running(i, getInstantWatts()))
            delay(200)
        }

        // 3. Data Collection - 8 seconds (40 samples)
        val voltages = mutableListOf<Int>()
        val wattages = mutableListOf<Double>()

        for (i in 1..40) {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val v = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val w = getInstantWatts(intent)

            if (v > 0) voltages.add(v)
            wattages.add(w)

            // Progress from 10% to 100%
            val progress = 10 + ((i / 40.0) * 90).toInt()
            emit(BenchmarkState.Running(progress, w))
            delay(200)
        }

        // 4. Analysis & Scoring
        if (wattages.isEmpty()) {
            emit(BenchmarkState.Error("No data collected"))
            return@flow
        }

        // Metrics
        val avgWatts = wattages.average()
        val maxWatts = wattages.maxOrNull() ?: 0.0

        // Voltage Ripple (Lower is better). Good cables maintain steady voltage.
        val minV = voltages.minOrNull() ?: 0
        val maxV = voltages.maxOrNull() ?: 0
        val ripple = maxV - minV

        // Stability (Standard Deviation)
        val variance = wattages.map { (it - avgWatts).pow(2) }.average()
        val stdDev = sqrt(variance)

        // --- SCORING ALGORITHM ---
        // A. Stability Score (Max 50 pts): Perfect stability = 50. High jitter (> 1.0W) penalizes.
        val stabilityRaw = 50 - (stdDev * 20)
        val stabilityPoints = stabilityRaw.coerceIn(0.0, 50.0)

        // B. Ripple Penalty (Max 20 pts deduction)
        val ripplePenalty = ((ripple - 50) / 10.0).coerceAtLeast(0.0).coerceAtMost(20.0)

        // C. Throughput Bonus (Max 50 pts): Reward raw power delivery.
        val throughputPoints = (avgWatts * 2.0).coerceIn(0.0, 50.0)

        // D. Final Calculation
        var finalScore = (stabilityPoints + throughputPoints - ripplePenalty).toInt().coerceIn(0, 100)

        // Contextual Adjustment: If battery is full, ignore low throughput if stability is high
        if (pct > 85 && finalScore < 80 && stabilityPoints > 40) {
            finalScore += 15
            warningMsg = (warningMsg ?: "") + " [Score adjusted for high battery]"
        }

        // Grading
        val grade = when {
            finalScore >= 90 -> "A+"
            finalScore >= 80 -> "A"
            finalScore >= 70 -> "B"
            finalScore >= 50 -> "C"
            else -> "F"
        }

        // Classification
        val type = when {
            maxWatts < 5.0 -> "Slow / USB-A"
            maxWatts < 15.0 -> "Standard"
            maxWatts < 25.0 -> "Fast Charge"
            else -> "Rapid / PD"
        }

        emit(BenchmarkState.Completed(
            BenchmarkResult(
                score = finalScore,
                grade = grade,
                avgWattage = avgWatts,
                maxWattage = maxWatts,
                voltageRipple = ripple,
                stabilityScore = (stabilityPoints / 5.0), // Scale to 0-10
                chargingType = type,
                warning = warningMsg
            )
        ))

    }.flowOn(Dispatchers.IO)

    private fun getInstantWatts(intent: Intent? = null): Double {
        val activeIntent = intent ?: context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        val voltageMv = activeIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val volts = voltageMv / 1000.0

        val currentUa = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val amps = abs(currentUa) / 1_000_000.0

        return volts * amps
    }
}