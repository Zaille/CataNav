package com.catanav.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun sourceToString(s: PointSource): String = s.name

    @TypeConverter
    fun stringToSource(s: String): PointSource = PointSource.valueOf(s)

    @TypeConverter
    fun methodToString(m: CalibrationMethod): String = m.name

    @TypeConverter
    fun stringToMethod(s: String): CalibrationMethod = CalibrationMethod.valueOf(s)
}

@Database(
    entities = [
        MapDefinitionEntity::class,
        MapVersionEntity::class,
        MapCalibrationEntity::class,
        AnchorEntity::class,
        CalibrationTestResultEntity::class,
        TripEntity::class,
        TrackPointEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class CataNavDatabase : RoomDatabase() {
    abstract fun mapDao(): MapDao
    abstract fun anchorDao(): AnchorDao
    abstract fun calibrationTestDao(): CalibrationTestDao
    abstract fun tripDao(): TripDao
    abstract fun trackPointDao(): TrackPointDao
    abstract fun tripMapDao(): TripMapDao

    companion object {
        @Volatile
        private var instance: CataNavDatabase? = null

        fun get(context: Context): CataNavDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CataNavDatabase::class.java,
                    "catanav.db",
                )
                    // Pre-release (locked decision): no migration of v1 user data.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
