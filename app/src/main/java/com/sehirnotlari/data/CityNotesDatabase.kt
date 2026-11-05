package com.sehirnotlari.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [NoteEntity::class], version = 2, exportSchema = true)
abstract class CityNotesDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao

    companion object {
        const val NAME = "city_notes.db"

        @Volatile private var instance: CityNotesDatabase? = null

        /** Every schema change needs an explicit migration; destructive fallback is never enabled. */
        fun builder(context: Context, name: String = NAME): RoomDatabase.Builder<CityNotesDatabase> =
            Room.databaseBuilder(context.applicationContext, CityNotesDatabase::class.java, name)
                .addMigrations(*NoteSchemaMigrations.ALL)

        fun get(context: Context): CityNotesDatabase =
            instance ?: synchronized(this) {
                instance ?: builder(context).build().also { instance = it }
            }
    }
}
