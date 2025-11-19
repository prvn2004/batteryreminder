// ===== batteryreminder\app\src\main\java\project\aio\batteryreminder\data\local\BatteryDao.kt =====
package project.aio.batteryreminder.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BatteryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: BatteryEntity)

    // Optimized for infrequent background cleaning
    @Query("DELETE FROM battery_history WHERE timestamp < :threshold")
    suspend fun cleanOldData(threshold: Long)

    // CHANGED: Increased limit to 50 to ensure we capture enough points for the curve
    @Query("SELECT * FROM battery_history WHERE isCharging = 0 ORDER BY timestamp DESC LIMIT 50")
    suspend fun getRecentDischargeHistory(): List<BatteryEntity>
}