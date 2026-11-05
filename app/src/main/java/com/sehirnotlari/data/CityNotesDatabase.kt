package com.sehirnotlari.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [NoteEntity::class], version = 1, exportSchema = false)
abstract class CityNotesDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao

    companion object {
        @Volatile private var instance: CityNotesDatabase? = null

        fun get(context: Context): CityNotesDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CityNotesDatabase::class.java,
                    "city_notes.db"
                ).build().also { instance = it }
            }
    }
}
