package me.bloo.whosthatpokemon2.dungeons.config

import com.google.gson.GsonBuilder
import me.bloo.whosthatpokemon2.dungeons.BlockPosDto
import me.bloo.whosthatpokemon2.dungeons.DungeonConstants
import me.bloo.whosthatpokemon2.dungeons.DungeonWorldResolver
import java.nio.file.Files

/** Load configurable block identifiers and related bits for dungeon scanning. */
data class DungeonConfig(
    val actionBlocks: Map<String, String>,
    val roomBlocks: Map<String, String>,
    val endWorld: String,
    val endPos: BlockPosDto,
    val doorBlock: String,
    val doorStrictSize: Boolean = false,
    val portalNumberAnnouncements: Boolean? = true,
    val flameBodyFreezeMultiplier: Double = 0.5,
    val scan: ScanSettings? = null,
    val legendaryGuaranteeGraceMinutes: Long = 30
) {
    fun scanSettings(): ScanSettings = scan ?: ScanSettings()

    fun withDefaults(): DungeonConfig = if (scan != null) this else copy(scan = ScanSettings())

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()
        private val path = DungeonConfigPaths.resolve("dungeon_config.json")
        @Volatile var instance: DungeonConfig = load() ?: default()

        private fun default() = DungeonConfig(
            actionBlocks = mapOf(
                "door" to "minecraft:bedrock",
                "gold" to "minecraft:gold_block",
                "emerald" to "minecraft:emerald_block",
                "dispenser" to "minecraft:dispenser",
                "bossCrafter" to "minecraft:crafter",
                "frontDoor" to "minecraft:target",
                "finalDoor" to "minecraft:note_block"
            ),
            roomBlocks = mapOf(
                "raid" to "minecraft:pumpkin",
                "tallRaid" to "minecraft:loom",
                "lootOrRaid" to "minecraft:furnace",
                "boss" to "cobblemon:apricorn_log",
                "spawn" to "minecraft:green_glazed_terracotta"
            ),
            endWorld = "minecraft:overworld",
            endPos = BlockPosDto(0, 64, 0),
            doorBlock = "minecraft:waxed_oxidized_copper_grate",
            doorStrictSize = false,
            portalNumberAnnouncements = true,
            flameBodyFreezeMultiplier = 0.5,
            scan = ScanSettings(),
            legendaryGuaranteeGraceMinutes = 30
        )

        private fun load(createIfMissing: Boolean = true): DungeonConfig? {
            return try {
                if (Files.exists(path)) {
                    Files.newBufferedReader(path).use { reader ->
                        gson.fromJson(reader, DungeonConfig::class.java)?.withDefaults()
                    }
                } else if (createIfMissing) {
                    val cfg = default()
                    Files.createDirectories(path.parent)
                    Files.newBufferedWriter(path).use { writer -> gson.toJson(cfg, writer) }
                    cfg
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        fun reload() {
            load(createIfMissing = false)?.let { instance = it }
        }

        fun save(config: DungeonConfig) {
            try {
                Files.createDirectories(path.parent)
                val normalized = config.withDefaults()
                Files.newBufferedWriter(path).use { writer -> gson.toJson(normalized, writer) }
                instance = normalized
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

data class ScanSettings(
    val logDebug: Boolean = false,
    val errorCooldownTicks: Long = 60
)

