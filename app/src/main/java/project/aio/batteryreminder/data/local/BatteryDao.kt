package project.aio.batteryreminder.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BatteryDao {
    @Insert
    suspend fun insert(entry: BatteryEntity)

    // Get last 20 entries to calculate recent drain speed
    @Query("SELECT * FROM battery_history WHERE isCharging = 0 ORDER BY timestamp DESC LIMIT 20")
    suspend fun getRecentDischargeHistory(): List<BatteryEntity>

    @Query("DELETE FROM battery_history WHERE timestamp < :threshold")
    suspend fun cleanOldData(threshold: Long)
}