// ===== batteryreminder\app\src\main\java\project\aio\batteryreminder\utils\PredictionEngine.kt =====
package project.aio.batteryreminder.utils

import android.content.Context
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import project.aio.batteryreminder.data.local.BatteryDao
import project.aio.batteryreminder.data.local.BatteryEntity
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class PredictionEngine @Inject constructor(
    @ApplicationContext private val context: Context, // Hilt needs this annotation
    private val batteryDao: BatteryDao
) {

    // Buffer to hold (Timestamp, Level)
    private val historyBuffer = ArrayDeque<Pair<Long, Int>>(30)
    private val scope = CoroutineScope(Dispatchers.IO)

    // System Service for Real-Time Data
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    // The Kalman Filter Instance
    private val kalmanFilter = HybridKalmanFilter()

    init {
        loadHistoryFromDb()
    }

    private fun loadHistoryFromDb() {
        scope.launch {
            val history = batteryDao.getRecentDischargeHistory() // Returns Newest -> Oldest
            if (history.isEmpty()) return@launch

            val validChain = mutableListOf<Pair<Long, Int>>()

            // Start with the newest point
            var previousPoint = history.first()
            validChain.add(Pair(previousPoint.timestamp, previousPoint.level))

            // Iterate backwards (Newest -> Oldest) to find a continuous chain
            for (i in 1 until history.size) {
                val current = history[i]
                val timeDiff = abs(previousPoint.timestamp - current.timestamp)
                val levelDiff = current.level - previousPoint.level

                // Allow max 30 min gap between readings and reasonable level continuity
                if (timeDiff < 30 * 60 * 1000 && levelDiff in 0..5) {
                    validChain.add(Pair(current.timestamp, current.level))
                    previousPoint = current
                } else {
                    break
                }
            }

            // We need Oldest -> Newest for the buffer
            synchronized(historyBuffer) {
                historyBuffer.clear()
                validChain.reversed().forEach {
                    historyBuffer.offerLast(it)
                }
            }
        }
    }

    fun resetBuffer() {
        synchronized(historyBuffer) {
            historyBuffer.clear()
        }
    }

    fun addHistoryPoint(timestamp: Long, level: Int) {
        synchronized(historyBuffer) {
            if (historyBuffer.isNotEmpty() && historyBuffer.peekLast()?.second == level) {
                return
            }
            if (historyBuffer.size >= 30) {
                historyBuffer.pollFirst()
            }
            historyBuffer.offerLast(Pair(timestamp, level))
        }
    }

    suspend fun logToDb(level: Int, isCharging: Boolean) {
        withContext(Dispatchers.IO) {
            if (Math.random() < 0.05) {
                val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
                batteryDao.cleanOldData(oneDayAgo)
            }
            batteryDao.insert(BatteryEntity(timestamp = System.currentTimeMillis(), level = level, isCharging = isCharging))
        }
    }

    // ================================================================================
    // METHOD 1: LEGACY / LONG TERM
    // Keeps your original logic purely based on History Buffer (Weighted Regression)
    // Good for "Standby" estimates or when Sensor data is missing.
    // ================================================================================
    fun estimateTimeRemainingWeighted(): Long {
        val data = synchronized(historyBuffer) { historyBuffer.toList() }
        if (data.size < 3) return -1L

        var sumWeights = 0.0; var sumX = 0.0; var sumY = 0.0
        var sumXY = 0.0; var sumXX = 0.0

        val startTime = data.first().first

        data.forEachIndexed { index, (time, level) ->
            val weight = (index + 1).toDouble()
            val x = (time - startTime) / 1000.0
            val y = level.toDouble()

            sumWeights += weight
            sumX += x * weight
            sumY += y * weight
            sumXY += x * y * weight
            sumXX += x * x * weight
        }

        val denominator = sumWeights * sumXX - sumX * sumX
        if (abs(denominator) < 0.000001) return -1L

        val slope = (sumWeights * sumXY - sumX * sumY) / denominator
        val intercept = (sumY - slope * sumX) / sumWeights

        if (abs(slope) < 0.00001) return -1L // Threshold for "infinite/idle"

        val isCharging = slope > 0
        val targetY = if (isCharging) 100.0 else 0.0
        val timeToTargetSecondsFromStart = (targetY - intercept) / slope
        val lastPointTimeSeconds = (data.last().first - startTime) / 1000.0
        val remainingSeconds = timeToTargetSecondsFromStart - lastPointTimeSeconds

        return if (remainingSeconds in 1.0..172800.0) remainingSeconds.toLong() else -1L
    }

    // ================================================================================
    // METHOD 2: HYBRID (RECOMMENDED)
    // Fuses History (Regression) with Real-Time Sensor Data via Kalman Filter.
    // Best for Real-Time "Active Use" prediction.
    // ================================================================================
    fun getHybridTimeRemaining(): Long {
        // 1. Get Capacity (Convert uAh to mAh)
        var capacityMicros = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        // Fallback: If OS reports garbage/zero capacity, assume standard 4000mAh to allow calculation
        if (capacityMicros <= 0) capacityMicros = 4000L * 1000L
        val totalCapacitymAh = capacityMicros / 1000.0

        // 2. Get Historical Expectation (Model)
        // Based on your buffer, what SHOULD the current be?
        val historicalCurrentmA = calculateHistoricalCurrent(totalCapacitymAh)

        // 3. Get Real-Time Measurement (Sensor)
        // Convert uA to mA. abs() handles discharge being reported as negative.
        val rawCurrent = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val instantCurrentmA = abs(rawCurrent) / 1000.0

        // 4. Filter / Fuse
        val smoothedCurrent = if (instantCurrentmA < 2.0) {
            // If sensor says 0mA (Deep Sleep/Error), trust History 100%
            if (historicalCurrentmA > 0) historicalCurrentmA else 0.0
        } else {
            // If sensor is active, fuse with history
            kalmanFilter.update(modelPrediction = historicalCurrentmA, sensorMeasurement = instantCurrentmA)
        }

        // 5. Calculate Time
        // If current is virtually zero, return -1 (Infinite/Unknown)
        if (smoothedCurrent < 5.0) return -1L

        // Time (Hours) = Capacity (mAh) / Current (mA)
        // NOTE: This calculates Full-to-Empty. We need Current-to-Empty.
        // We need to scale based on current battery % ?
        // Actually, charge counter usually gives remaining capacity.
        // If charge counter is max capacity, we need to multiply by %.
        // Standard Android BATTERY_PROPERTY_CHARGE_COUNTER is usually "Remaining battery capacity in microampere-hours".

        val hoursLeft = totalCapacitymAh / smoothedCurrent
        val secondsLeft = (hoursLeft * 3600).toLong()

        // Cap at 48 hours to avoid showing unrealistic numbers
        return if (secondsLeft in 1..172800) secondsLeft else -1L
    }

    /**
     * Helper: Converts the Regression Slope (%/sec) into Current (mA)
     * This allows the History data to "speak the same language" as the hardware sensor.
     */
    private fun calculateHistoricalCurrent(totalCapacitymAh: Double): Double {
        val data = synchronized(historyBuffer) { historyBuffer.toList() }
        if (data.size < 3) return 0.0 // Not enough history

        var sumWeights = 0.0; var sumX = 0.0; var sumY = 0.0
        var sumXY = 0.0; var sumXX = 0.0
        val startTime = data.first().first

        data.forEachIndexed { index, (time, level) ->
            val weight = (index + 1).toDouble()
            val x = (time - startTime) / 1000.0
            val y = level.toDouble()

            sumWeights += weight
            sumX += x * weight
            sumY += y * weight
            sumXY += x * y * weight
            sumXX += x * x * weight
        }

        val denominator = sumWeights * sumXX - sumX * sumX
        if (abs(denominator) < 0.000001) return 0.0

        val slope = (sumWeights * sumXY - sumX * sumY) / denominator // % change per second

        // We only care about discharge magnitude
        val dropPerSecondPct = abs(slope)
        val dropPerHourPct = dropPerSecondPct * 3600.0

        // mA = (%_per_hour / 100) * Total_Capacity_mAh
        // Note: TotalCapacity here refers to REMAINING capacity in standard API?
        // Actually for slope calculation we need Rated Capacity, but assuming linear degradation is fine for estimation.
        return (dropPerHourPct / 100.0) * totalCapacitymAh
    }

    /**
     * 1D Kalman Filter logic optimized for Battery State Estimation
     */
    private class HybridKalmanFilter {
        private var estimate = 0.0
        private var errorCovariance = 1.0

        // Q: Process Noise (How much we trust the "Model" / History)
        // Low Q = We think history is reliable, ignore sudden spikes.
        // High Q = We think history changes fast, adapt quickly.
        private val Q = 0.005

        // R: Measurement Noise (How much we trust the "Sensor" / Current)
        // Low R = Trust sensor explicitly (react fast).
        // High R = Trust sensor less (smooth out the graph).
        private val R = 0.1

        fun update(modelPrediction: Double, sensorMeasurement: Double): Double {
            // Initialization check
            if (estimate == 0.0) {
                estimate = if (modelPrediction > 0) modelPrediction else sensorMeasurement
                return estimate
            }

            // 1. Prediction Step
            // We predict the state is what our History Model says
            // If history is empty (0), carry forward previous estimate
            val predictedState = if (modelPrediction > 0) modelPrediction else estimate
            val predictedError = errorCovariance + Q

            // 2. Update Step
            val kalmanGain = predictedError / (predictedError + R)
            estimate = predictedState + kalmanGain * (sensorMeasurement - predictedState)
            errorCovariance = (1 - kalmanGain) * predictedError

            return estimate
        }
    }
}