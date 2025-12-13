package me.bloo.whosthatpokemon2.dungeons

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

object GoodChestLootConfig {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = DungeonConfigPaths.resolve("good_chest_loot.json")

    @Volatile
    private var table: DungeonLootTable = load() ?: default()

    fun get(): DungeonLootTable = table.deepCopy()

    fun update(newTable: DungeonLootTable) {
        table = newTable.deepCopy()
        persist()
    }

    fun reload() {
        load(createIfMissing = false)?.let { table = it }
    }

    private fun default(): DungeonLootTable {
        return DungeonLootTable(
            airPercentage = 50,
            loot = mutableListOf(
                LootItemConfig(
                    item = "minecraft:golden_apple",
                    chance = 100.0,
                    stackable = true,
                    stackRange = StackRangeConfig(1, 3)
                ),
                LootItemConfig(
                    item = "minecraft:gold_nugget",
                    chance = 100.0,
                    stackable = true,
                    stackRange = StackRangeConfig(8, 32)
                )
            )
        )
    }

    private fun load(createIfMissing: Boolean = true): DungeonLootTable? {
        return try {
            if (Files.exists(path)) {
                Files.newBufferedReader(path).use { reader ->
                    gson.fromJson(reader, DungeonLootTable::class.java) ?: default()
                }
            } else if (createIfMissing) {
                val table = default()
                Files.createDirectories(path.parent)
                Files.newBufferedWriter(path).use { writer -> gson.toJson(table, writer) }
                table
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun persist() {
        try {
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(path).use { writer -> gson.toJson(table, writer) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun DungeonLootTable.deepCopy(): DungeonLootTable {
        return DungeonLootTable(
            airPercentage = airPercentage,
            loot = loot.map { it.deepCopy() }.toMutableList()
        )
    }

    private fun LootItemConfig.deepCopy(): LootItemConfig {
        return LootItemConfig(
            item = item,
            chance = chance,
            stackable = stackable,
            stackRange = stackRange?.copy()
        )
    }
}
