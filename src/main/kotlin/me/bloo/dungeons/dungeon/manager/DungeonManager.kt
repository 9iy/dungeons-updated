package me.bloo.dungeons.dungeon.manager

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ThreadLocalRandom
import kotlin.random.asKotlinRandom
import me.bloo.dungeons.config.DungeonConfigPaths
import me.bloo.dungeons.config.DungeonLootConfig
import me.bloo.dungeons.config.DungeonTypeConfig
import me.bloo.dungeons.config.DungeonTypeDisplayConfig
import me.bloo.dungeons.dungeon.model.Dungeon

/**
 * Load + stash dungeon definitions so we can hand them out without drama.
 */
object DungeonManager {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val configPath: Path = DungeonConfigPaths.resolve("dungeons.json")

    private val dungeons: MutableMap<String, Dungeon> = mutableMapOf()

    init {
        readDefinitions()?.let { loaded ->
            dungeons.putAll(loaded)
        }
    }

    fun addDungeon(dungeon: Dungeon) {
        dungeons[dungeon.name] = dungeon
        DungeonTypeConfig.ensureType(dungeon.type)
        DungeonLootConfig.ensure(dungeon.type)
        save()
    }

    fun getDungeon(name: String): Dungeon? = dungeons[name]

    fun listTypes(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val displays = DungeonTypeDisplayConfig.all()
        dungeons.values.forEach { dungeon ->
            val icon = displays[dungeon.type]?.icon ?: dungeon.block
            map.putIfAbsent(dungeon.type, icon)
        }
        displays.forEach { (type, display) ->
            map.putIfAbsent(type, display.icon)
        }
        return map
    }

    fun list(): Collection<Dungeon> = dungeons.values

    @Synchronized
    fun findAvailableDungeon(type: String): Dungeon? {
        val available = dungeons.values.filter { it.type == type && !it.taken }
        if (available.isEmpty()) return null
        val random = ThreadLocalRandom.current().asKotlinRandom()
        val selected = available.random(random)
        val claimed = selected.copy(taken = true)
        dungeons[selected.name] = claimed
        save()
        return claimed
    }

    fun markTaken(name: String, taken: Boolean) {
        dungeons[name]?.let { dungeons[name] = it.copy(taken = taken); save() }
    }

    private fun readDefinitions(): MutableMap<String, Dungeon>? {
        return try {
            if (Files.exists(configPath)) {
                Files.newBufferedReader(configPath).use { reader ->
                    val type = object : TypeToken<List<Dungeon>>() {}.type
                    val list: List<Dungeon> = gson.fromJson(reader, type) ?: emptyList()
                    val loaded = mutableMapOf<String, Dungeon>()
                    list.forEach { dungeon ->
                        loaded[dungeon.name] = dungeon
                        DungeonTypeConfig.ensureType(dungeon.type)
                        DungeonLootConfig.ensure(dungeon.type)
                    }
                    loaded
                }
            } else {
                mutableMapOf()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun reload() {
        readDefinitions()?.let { loaded ->
            dungeons.clear()
            dungeons.putAll(loaded)
        }
    }

    private fun save() {
        Files.createDirectories(configPath.parent)
        Files.newBufferedWriter(configPath).use { writer ->
            gson.toJson(dungeons.values.toList(), writer)
        }
    }
}
