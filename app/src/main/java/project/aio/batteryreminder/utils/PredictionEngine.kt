package project.aio.batteryreminder.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import project.aio.batteryreminder.data.local.BatteryDao
import project.aio.batteryreminder.data.local.BatteryEntity
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PredictionEngine @Inject constructor(
    private val batteryDao: BatteryDao
) {

    private val historyBuffer = ArrayDeque<Pair<Long, Int>>(25)

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
            if (historyBuffer.size >= 25) {
                historyBuffer.pollFirst()
            }
            historyBuffer.offerLast(Pair(timestamp, level))
        }
    }

    suspend fun logToDb(level: Int, isCharging: Boolean) {
        withContext(Dispatchers.IO) {
            if (Math.random() < 0.01) {
                val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
                batteryDao.cleanOldData(oneDayAgo)
            }
            batteryDao.insert(BatteryEntity(timestamp = System.currentTimeMillis(), level = level, isCharging = isCharging))
        }
    }

    /**
     * Universal Weighted Regression.
     * Returns seconds until target.
     * If discharging: predicts time to 0.
     * If charging: predicts time to 100.
     */
    fun estimateTimeRemainingWeighted(): Long {
        val data = synchronized(historyBuffer) { historyBuffer.toList() }
        if (data.size < 3) return -1L

        var sumWeights = 0.0
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumXX = 0.0

        val startTime = data.first().first

        data.forEachIndexed { index, (time, level) ->
            val weight = (index + 1).toDouble() // Newer = heavier
            val x = (time - startTime) / 1000.0
            val y = level.toDouble()

            sumWeights += weight
            sumX += x * weight
            sumY += y * weight
            sumXY += x * y * weight
            sumXX += x * x * weight
        }

        val denominator = sumWeights * sumXX - sumX * sumX
        if (denominator == 0.0) return -1L

        val slope = (sumWeights * sumXY - sumX * sumY) / denominator
        val intercept = (sumY - slope * sumX) / sumWeights

        // Detect Direction
        val isCharging = slope > 0

        val targetY = if (isCharging) 100.0 else 0.0

        // Prevent division by zero or infinite time on flat slope
        if (kotlin.math.abs(slope) < 0.001) return -1L

        // Solve for x when y = targetY
        // y = mx + c  =>  target = slope*x + intercept  =>  x = (target - intercept) / slope
        val timeToTarget = (targetY - intercept) / slope

        val lastPointTime = (data.last().first - startTime) / 1000.0
        val remainingSeconds = timeToTarget - lastPointTime

        return if (remainingSeconds > 0) remainingSeconds.toLong() else 0L
    }
}