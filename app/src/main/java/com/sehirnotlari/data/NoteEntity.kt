package com.sehirnotlari.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "notes", indices = [Index("city"), Index("updatedAt")])
data class NoteEntity(
    @PrimaryKey val id: String,
    val city: String,
    val title: String,
    val content: String = "",
    val category: String = "Genel",
    val star: Int = 0,
    val color: String = "",
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    val syncState: String = "LOCAL"
)
