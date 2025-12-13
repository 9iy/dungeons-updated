package me.bloo.whosthatpokemon2.dungeons

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path

/** Keep GUI display metadata per dungeon type so menus stay on-brand. */
data class DungeonTypeDisplay(
    var name: String = "",
    var icon: String = "minecraft:stone",
    var enabled: Boolean = true,
    var description: String = ""
)

object DungeonTypeDisplayConfig {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = DungeonConfigPaths.resolve("dungeon_type_display.json")

    @Volatile
    private var displays: MutableMap<String, DungeonTypeDisplay> = load() ?: mutableMapOf()

    @Synchronized
    fun get(type: String): DungeonTypeDisplay? = displays[type]?.copy()

    @Synchronized
    fun all(): Map<String, DungeonTypeDisplay> = displays.mapValues { it.value.copy() }

    @Synchronized
    fun set(type: String, display: DungeonTypeDisplay) {
        displays[type] = display.copy()
        persist()
    }

    @Synchronized
    fun isEnabled(type: String): Boolean = displays[type]?.enabled ?: true

    @Synchronized
    fun reload() {
        load()?.let { displays = it }
    }

    private fun load(): MutableMap<String, DungeonTypeDisplay>? {
        return try {
            if (Files.exists(path)) {
                Files.newBufferedReader(path).use { reader ->
                    val type = object : TypeToken<Map<String, DungeonTypeDisplay>>() {}.type
                    val map: Map<String, DungeonTypeDisplay> = gson.fromJson(reader, type) ?: emptyMap()
                    map.mapValues { (_, value) -> value.copy() }.toMutableMap()
                }
            } else {
                mutableMapOf()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun persist() {
        try {
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(path).use { writer ->
                gson.toJson(displays, writer)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
