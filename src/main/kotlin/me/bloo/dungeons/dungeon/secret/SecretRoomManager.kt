package me.bloo.dungeons.dungeon.secret

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.util.UUID
import me.bloo.dungeons.config.DungeonConfigPaths

/**
 * Manage secret room definitions and keep tabs on who’s inside right now.
 */
object SecretRoomManager {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path = DungeonConfigPaths.resolve("secret_rooms.json")

    @Volatile
    private var definitions: MutableList<SecretRoomDefinition> = loadDefinitions() ?: mutableListOf()
    private val occupancy: MutableMap<String, String> = mutableMapOf()

    private fun loadDefinitions(): MutableList<SecretRoomDefinition>? {
        return try {
            if (Files.exists(path)) {
                Files.newBufferedReader(path).use { reader ->
                    val type = object : TypeToken<List<SecretRoomDefinition>>() {}.type
                    val list: List<SecretRoomDefinition> = gson.fromJson(reader, type) ?: emptyList()
                    list.toMutableList()
                }
            } else {
                Files.createDirectories(path.parent)
                mutableListOf()
            }
        } catch (error: Exception) {
            error.printStackTrace()
            null
        }
    }

    private fun save() {
        try {
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(path).use { writer ->
                gson.toJson(definitions, writer)
            }
        } catch (error: Exception) {
            error.printStackTrace()
        }
    }

    fun reload() {
        loadDefinitions()?.let { loaded ->
            definitions = loaded
            val validIds = loaded.mapTo(mutableSetOf()) { it.id }
            occupancy.keys.retainAll(validIds)
        }
    }

    fun add(definition: SecretRoomDefinition): SecretRoomDefinition {
        val sanitized = definition.copy(id = definition.id.ifBlank { UUID.randomUUID().toString() })
        definitions.removeIf { it.id == sanitized.id }
        definitions.add(sanitized)
        save()
        return sanitized
    }

    fun definitionsForType(type: String): List<SecretRoomDefinition> {
        return definitions.filter { it.dungeonType.equals(type, ignoreCase = true) }
    }

    fun claimRoom(type: String, world: String, sessionId: String): SecretRoomDefinition? {
        val prioritized = definitions
            .filter { it.dungeonType.equals(type, ignoreCase = true) && it.world == world }
            .sortedBy { it.id }
        val available = (prioritized + definitions
            .filter { it.dungeonType.equals(type, ignoreCase = true) && it.world != world })
            .firstOrNull { occupancy[it.id] == null }
        if (available != null) {
            occupancy[available.id] = sessionId
        }
        return available
    }

    fun releaseRoom(id: String, sessionId: String?) {
        val owner = occupancy[id]
        if (owner != null && (sessionId == null || owner == sessionId)) {
            occupancy.remove(id)
        }
    }

    fun isOccupied(id: String): Boolean = occupancy.containsKey(id)
}
