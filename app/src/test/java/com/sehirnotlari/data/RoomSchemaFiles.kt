package com.sehirnotlari.data

import org.json.JSONObject
import java.io.File

/** Reads the Room schemas exported to app/schemas (v1 was exported from the shipped v1 entity). */
object RoomSchemaFiles {
    private const val DIR = "schemas/com.sehirnotlari.data.CityNotesDatabase"

    fun database(version: Int): JSONObject {
        val file = listOf(File("$DIR/$version.json"), File("app/$DIR/$version.json")).first { it.isFile }
        return JSONObject(file.readText()).getJSONObject("database")
    }

    fun notesEntity(version: Int): JSONObject = entity(version, "notes")

    fun entity(version: Int, table: String): JSONObject {
        val entities = database(version).getJSONArray("entities")
        return (0 until entities.length()).map { entities.getJSONObject(it) }
            .first { it.getString("tableName") == table }
    }

    fun tables(version: Int): List<String> {
        val entities = database(version).getJSONArray("entities")
        return (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
    }

    fun columns(version: Int): List<String> {
        val fields = notesEntity(version).getJSONArray("fields")
        return (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
    }

    /** Table, index and room_master_table statements exactly as Room ran them for [version]. */
    fun createStatements(version: Int): List<String> {
        val entity = notesEntity(version)
        val indices = entity.getJSONArray("indices")
        val setup = database(version).getJSONArray("setupQueries")
        return buildList {
            add(entity.getString("createSql").replace("\${TABLE_NAME}", "notes"))
            for (i in 0 until indices.length()) {
                add(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", "notes"))
            }
            for (i in 0 until setup.length()) add(setup.getString(i))
        }
    }
}
