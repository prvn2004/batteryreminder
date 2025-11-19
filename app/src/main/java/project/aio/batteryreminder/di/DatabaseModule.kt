package project.aio.batteryreminder.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import project.aio.batteryreminder.data.local.AppDatabase
import project.aio.batteryreminder.data.local.BatteryDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "battery_ai_db"
        ).build()
    }

    @Provides
    fun provideBatteryDao(db: AppDatabase): BatteryDao = db.batteryDao()
}