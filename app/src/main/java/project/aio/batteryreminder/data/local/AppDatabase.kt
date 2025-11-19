package project.aio.batteryreminder.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import project.aio.batteryreminder.data.local.BatteryEntity

@Database(entities = [BatteryEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun batteryDao(): BatteryDao
}