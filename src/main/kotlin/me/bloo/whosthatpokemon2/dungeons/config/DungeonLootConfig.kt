package me.bloo.whosthatpokemon2.dungeons.config

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ThreadLocalRandom

data class StackRangeConfig(
    val min: Int = 1,
    val max: Int = 1
) {
    fun roll(random: ThreadLocalRandom): Int {
        val normalizedMin = min.coerceAtLeast(1)
        val normalizedMax = max.coerceAtLeast(normalizedMin)
        return random.nextInt(normalizedMin, normalizedMax + 1)
    }
}

data class LootItemConfig(
    val item: String,
    val chance: Double,
    val stackable: Boolean,
    val stackRange: StackRangeConfig? = null
)

data class DungeonLootTable(
    var airPercentage: Int = 60,
    var flameShieldChance: Double = DEFAULT_FLAME_SHIELD_CHEST_CHANCE,
    val loot: MutableList<LootItemConfig> = mutableListOf(
        LootItemConfig("minecraft:splash_potion", 30.0, false),
        LootItemConfig("minecraft:cooked_beef", 40.0, true, StackRangeConfig(2, 8))
    )
)

private const val DEFAULT_FLAME_SHIELD_CHEST_CHANCE = 0.02

object DungeonLootConfig {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = DungeonConfigPaths.resolve("dungeon_loot.json")

    @Volatile
    private var tables: MutableMap<String, DungeonLootTable> = load() ?: mutableMapOf()

    @Synchronized
    fun ensure(type: String): DungeonLootTable {
        val existing = tables[type]
        if (existing != null) {
            return existing.mutableCopy()
        }
        val created = DungeonLootTable()
        tables[type] = created
        persist()
        return created.mutableCopy()
    }

    @Synchronized
    fun get(type: String): DungeonLootTable? = tables[type]?.mutableCopy()

    @Synchronized
    fun update(type: String, table: DungeonLootTable) {
        tables[type] = table.mutableCopy()
        persist()
    }

    @Synchronized
    fun reload() {
        load()?.let { tables = it }
    }

    private fun load(): MutableMap<String, DungeonLootTable>? {
        return try {
            if (Files.exists(path)) {
                Files.newBufferedReader(path).use { reader ->
                    val type = object : TypeToken<Map<String, DungeonLootTable>>() {}.type
                    val map: Map<String, DungeonLootTable> = gson.fromJson(reader, type) ?: emptyMap()
                    map.mapValues { (_, value) -> value.mutableCopy() }.toMutableMap()
                }
            } else {
                mutableMapOf()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @Synchronized
    private fun persist() {
        try {
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(path).use { writer ->
                gson.toJson(tables, writer)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun DungeonLootTable.mutableCopy(): DungeonLootTable {
        return DungeonLootTable(
            airPercentage = airPercentage,
            flameShieldChance = flameShieldChance,
            loot = loot.map { it.mutableCopy() }.toMutableList()
        )
    }

    private fun LootItemConfig.mutableCopy(): LootItemConfig {
        return LootItemConfig(
            item = item,
            chance = chance,
            stackable = stackable,
            stackRange = stackRange?.copy()
        )
    }
}
