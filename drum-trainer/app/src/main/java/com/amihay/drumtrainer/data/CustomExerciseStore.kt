package com.amihay.drumtrainer.data

import com.amihay.drumtrainer.model.Exercise
import com.amihay.drumtrainer.model.ExerciseParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Exercises written in the editor, kept in the same JSON format as the built-in ones. */
class CustomExerciseStore(private val file: File) {

    private fun readArray(): JSONArray =
        if (file.exists()) runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray()) else JSONArray()

    /** Saved exercises; an entry that no longer parses is skipped rather than failing them all. */
    fun load(): List<Exercise> {
        val arr = readArray()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { ExerciseParser.parse(JSONArray().put(arr.getJSONObject(i)).toString()).single() }.getOrNull()
        }
    }

    fun upsert(json: JSONObject) {
        val id = json.getString("id")
        val arr = readArray()
        val out = JSONArray()
        var replaced = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") == id) {
                out.put(json)
                replaced = true
            } else {
                out.put(o)
            }
        }
        if (!replaced) out.put(json)
        write(out)
    }

    fun delete(id: String) {
        val arr = readArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) arr.getJSONObject(i).takeIf { it.optString("id") != id }?.let { out.put(it) }
        write(out)
    }

    private fun write(arr: JSONArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString(2))
        tmp.renameTo(file)
    }
}
