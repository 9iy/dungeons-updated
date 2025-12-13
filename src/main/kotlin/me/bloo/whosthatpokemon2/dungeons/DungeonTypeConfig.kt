package me.bloo.whosthatpokemon2.dungeons

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path

data class DungeonTypePokemon(
    val weak: MutableList<String> = mutableListOf(),
    val medium: MutableList<String> = mutableListOf(),
    val hard: MutableList<String> = mutableListOf(),
    val boss: MutableList<String> = mutableListOf(),
    val bossSize: Double = 1.0
)

object DungeonTypeConfig {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = DungeonConfigPaths.resolve("dungeon_types.json")

    @Volatile
    private var types: MutableMap<String, DungeonTypePokemon> = load() ?: mutableMapOf()

    @Synchronized
    fun ensureType(type: String): DungeonTypePokemon {
        val existing = types[type]
        if (existing != null) {
            return existing.mutableCopy()
        }
        val created = if (type.equals("fire", ignoreCase = true)) {
            defaultFireConfig()
        } else {
            DungeonTypePokemon()
        }
        types[type] = created
        persist()
        return created.mutableCopy()
    }

    @Synchronized
    fun get(type: String): DungeonTypePokemon? = types[type]?.mutableCopy()

    @Synchronized
    fun update(type: String, config: DungeonTypePokemon) {
        types[type] = config.mutableCopy()
        persist()
    }

    @Synchronized
    fun reload() {
        load()?.let { types = it }
    }

    private fun load(): MutableMap<String, DungeonTypePokemon>? {
        return try {
            val loaded = if (Files.exists(path)) {
                Files.newBufferedReader(path).use { reader ->
                    val type = object : TypeToken<Map<String, DungeonTypePokemon>>() {}.type
                    val map: Map<String, DungeonTypePokemon> = gson.fromJson(reader, type) ?: emptyMap()
                    map.mapValues { (_, value) -> value.mutableCopy() }.toMutableMap()
                }
            } else {
                mutableMapOf()
            }
            val withDefault = ensureDefaultFire(loaded)
            if (withDefault !== loaded) {
                persist(withDefault)
            }
            withDefault
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun ensureDefaultFire(types: MutableMap<String, DungeonTypePokemon>): MutableMap<String, DungeonTypePokemon> {
        if (types.keys.any { it.equals("fire", ignoreCase = true) }) {
            return types
        }
        val updated = types.toMutableMap()
        updated["fire"] = defaultFireConfig()
        return updated
    }

    private fun defaultFireConfig(): DungeonTypePokemon {
        return DungeonTypePokemon(
            weak = mutableListOf("charmander"),
            medium = mutableListOf("charmeleon"),
            hard = mutableListOf("charizard"),
            boss = mutableListOf("ninetales"),
            bossSize = 1.0
        )
    }

    @Synchronized
    private fun persist(typesToPersist: Map<String, DungeonTypePokemon> = types) {
        try {
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(path).use { writer ->
                gson.toJson(typesToPersist, writer)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun DungeonTypePokemon.mutableCopy(): DungeonTypePokemon {
        return DungeonTypePokemon(
            weak = weak.toMutableList(),
            medium = medium.toMutableList(),
            hard = hard.toMutableList(),
            boss = boss.toMutableList(),
            bossSize = bossSize
        )
    }
}

