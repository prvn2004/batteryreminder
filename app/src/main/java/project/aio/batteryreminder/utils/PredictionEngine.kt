// ===== batteryreminder\app\src\main\java\project\aio\batteryreminder\utils\PredictionEngine.kt =====
package project.aio.batteryreminder.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import project.aio.batteryreminder.data.local.BatteryDao
import project.aio.batteryreminder.data.local.BatteryEntity
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class PredictionEngine @Inject constructor(
    private val batteryDao: BatteryDao
) {

    // Buffer to hold (Timestamp, Level)
    private val historyBuffer = ArrayDeque<Pair<Long, Int>>(30)
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        // FIX 1: Load historical data on startup so we don't stay in "Learning..."
        // if the app restarts at 93%
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

                // Logic: A continuous discharge session shouldn't have massive time gaps (>30 mins)
                // or massive level jumps (e.g. 90% -> 50% instantly implies missing data)
                val timeDiff = abs(previousPoint.timestamp - current.timestamp)
                val levelDiff = current.level - previousPoint.level // Should be >= 0 for discharge history (e.g. 94 - 93)

                // Allow max 30 min gap between readings and reasonable level continuity
                if (timeDiff < 30 * 60 * 1000 && levelDiff in 0..5) {
                    validChain.add(Pair(current.timestamp, current.level))
                    previousPoint = current
                } else {
                    // Chain broken (likely a charge session or phone was off)
                    break
                }
            }

            // We need Oldest -> Newest for the buffer
            synchronized(historyBuffer) {
                historyBuffer.clear()
                // Add in reverse order (Oldest first)
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
            // Deduplication: Don't add if same level exists (we want the entry point of the level)
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
        // Using Dispatchers.IO to ensure DB ops don't block
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            if (Math.random() < 0.05) { // Increased clean chance to 5%
                val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
                batteryDao.cleanOldData(oneDayAgo)
            }
            batteryDao.insert(BatteryEntity(timestamp = System.currentTimeMillis(), level = level, isCharging = isCharging))
        }
    }

    /**
     * Universal Weighted Regression.
     * Returns seconds until target (0% or 100%).
     */
    fun estimateTimeRemainingWeighted(): Long {
        val data = synchronized(historyBuffer) { historyBuffer.toList() }

        // Need at least 3 points for a decent curve, but with loaded history, this is easier to hit.
        if (data.size < 3) return -1L

        var sumWeights = 0.0
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumXX = 0.0

        val startTime = data.first().first

        data.forEachIndexed { index, (time, level) ->
            // Weight recent points more heavily (index + 1)
            val weight = (index + 1).toDouble()
            val x = (time - startTime) / 1000.0 // Seconds since start
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

        // FIX 2: Sensitivity Threshold
        // Old threshold (0.001) required ~3.6% drain/hour.
        // New threshold (0.00001) detects ~0.036% drain/hour (covering deep sleep).
        if (abs(slope) < 0.00001) return -1L

        // Detect Direction
        val isCharging = slope > 0
        val targetY = if (isCharging) 100.0 else 0.0

        // y = mx + c  =>  x = (y - c) / m
        val timeToTargetSecondsFromStart = (targetY - intercept) / slope

        val lastPointTimeSeconds = (data.last().first - startTime) / 1000.0
        val remainingSeconds = timeToTargetSecondsFromStart - lastPointTimeSeconds

        // Sanity cap: If prediction is > 24 hours (86400s), it's practically infinite/idle
        return if (remainingSeconds in 1.0..172800.0) remainingSeconds.toLong() else -1L
    }
}