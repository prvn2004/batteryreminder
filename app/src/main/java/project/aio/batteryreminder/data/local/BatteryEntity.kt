package project.aio.batteryreminder.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "battery_history")
data class BatteryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val level: Int,
    val isCharging: Boolean
)