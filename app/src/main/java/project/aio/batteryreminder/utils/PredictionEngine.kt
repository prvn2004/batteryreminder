package project.aio.batteryreminder.utils

import project.aio.batteryreminder.data.local.BatteryDao
import project.aio.batteryreminder.data.local.BatteryEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class PredictionEngine @Inject constructor(
    private val batteryDao: BatteryDao
) {

    // Returns estimated seconds remaining
    suspend fun calculateTimeRemaining(currentLevel: Int): Long {
        val history = batteryDao.getRecentDischargeHistory()

        if (history.size < 2) return -1L // Not enough data

        // Calculate average time to drop 1%
        var totalTimeDiff = 0L
        var totalDrop = 0

        // Pair adjacent entries
        for (i in 0 until history.size - 1) {
            val newer = history[i]
            val older = history[i+1]

            val levelDiff = older.level - newer.level
            // Only count valid discharge drops
            if (levelDiff > 0) {
                val timeDiff = newer.timestamp - older.timestamp
                totalTimeDiff += timeDiff
                totalDrop += levelDiff
            }
        }

        if (totalDrop == 0) return -1L

        val avgMillisPerPercent = totalTimeDiff / totalDrop

        // Apply Non-Linear Adjustment
        // Batteries drain FASTER at lower percentages.
        // If level is < 15%, we assume drain is 1.5x faster than average.
        val lowBatteryFactor = if (currentLevel < 15) 0.7 else 1.0

        val estimatedMillisLeft = (avgMillisPerPercent * currentLevel * lowBatteryFactor).toLong()

        return estimatedMillisLeft / 1000L // Return seconds
    }

    suspend fun logBatteryState(level: Int, isCharging: Boolean) {
        // cleanup old data occasionally (older than 24 hours)
        val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        batteryDao.cleanOldData(oneDayAgo)

        // Insert new
        batteryDao.insert(BatteryEntity(timestamp = System.currentTimeMillis(), level = level, isCharging = isCharging))
    }
}