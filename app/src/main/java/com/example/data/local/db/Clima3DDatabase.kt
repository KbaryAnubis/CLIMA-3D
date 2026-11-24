package com.example.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Local Room database for Clima 3D.
 * Preserves saved locations, custom map pins, and offline weather/air-quality/radar caches.
 * Never enables destructive migration so persisted user data is preserved across schema updates.
 */
@Database(
    entities = [
        SavedLocationEntity::class,
        WeatherForecastCacheEntity::class,
        AirQualityCacheEntity::class,
        RadarTimelineCacheEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class Clima3DDatabase : RoomDatabase() {
    abstract fun savedLocationDao(): SavedLocationDao
    abstract fun weatherCacheDao(): WeatherCacheDao

    companion object {
        private const val DATABASE_NAME: String = "clima3d_local.db"

        @Volatile
        private var instance: Clima3DDatabase? = null

        fun getInstance(context: Context): Clima3DDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    Clima3DDatabase::class.java,
                    DATABASE_NAME,
                ).build().also { instance = it }
            }

        /**
         * Creates an isolated in-memory Room database strictly for local JVM/Robolectric tests.
         */
        fun createInMemory(context: Context): Clima3DDatabase =
            Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                Clima3DDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
    }
}
