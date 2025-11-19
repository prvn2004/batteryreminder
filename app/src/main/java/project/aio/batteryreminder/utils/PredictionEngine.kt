// ===== batteryreminder\app\src\main\java\project\aio\batteryreminder\utils\PredictionEngine.kt =====
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

    // RAM Cache: Stores (Timestamp, Level). Max 20 items.
    // Avoids hitting the database for every calculation.
    private val historyBuffer = ArrayDeque<Pair<Long, Int>>(25)

    fun resetBuffer() {
        historyBuffer.clear()
    }

    fun addHistoryPoint(timestamp: Long, level: Int) {
        synchronized(historyBuffer) {
            // Deduplication: Don't add if level is same as last entry
            if (historyBuffer.isNotEmpty() && historyBuffer.peekLast()?.second == level) {
                return
            }

            if (historyBuffer.size >= 20) {
                historyBuffer.pollFirst() // Remove oldest
            }
            historyBuffer.offerLast(Pair(timestamp, level))
        }
    }

    // Database write is purely for historical logs/debugging, separate from logic
    suspend fun logToDb(level: Int, isCharging: Boolean) {
        withContext(Dispatchers.IO) {
            // Cleanup once a day approx, purely random chance to save checking time every run
            if (Math.random() < 0.01) {
                val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
                batteryDao.cleanOldData(oneDayAgo)
            }
            batteryDao.insert(BatteryEntity(timestamp = System.currentTimeMillis(), level = level, isCharging = isCharging))
        }
    }

    /**
     * Uses Least Squares Linear Regression to predict time to 0%.
     * y = mx + c
     * y = Battery Level
     * x = Timestamp
     * We want to find x where y = 0.
     */
    fun estimateTimeRemainingLinearRegression(): Long {
        val data = synchronized(historyBuffer) { historyBuffer.toList() }

        // Need at least 3 points for a trend
        if (data.size < 3) return -1L

        val n = data.size.toDouble()
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumXX = 0.0

        // Normalizing time to prevent massive number overflow
        val startTime = data.first().first

        for ((time, level) in data) {
            val x = (time - startTime) / 1000.0 // Seconds since start of buffer
            val y = level.toDouble()

            sumX += x
            sumY += y
            sumXY += (x * y)
            sumXX += (x * x)
        }

        // Calculate Slope (m)
        val slope = (n * sumXY - sumX * sumY) / (n * sumXX - sumX * sumX)

        // If slope is positive or zero, battery isn't draining or is charging
        if (slope >= 0) return -1L

        // Calculate Intercept (c)
        val intercept = (sumY - slope * sumX) / n

        // Solve for y = 0  =>  0 = mx + c  =>  x = -c / m
        val timeToZeroSecondsFromStart = -intercept / slope

        // Calculate remaining time relative to NOW
        val lastPointTime = (data.last().first - startTime) / 1000.0
        val remainingSeconds = timeToZeroSecondsFromStart - lastPointTime

        return if (remainingSeconds > 0) remainingSeconds.toLong() else 0L
    }

    // Legacy fallback (kept for reference, but Regression is better)
    suspend fun calculateTimeRemaining(currentLevel: Int): Long {
        return estimateTimeRemainingLinearRegression()
    }
}