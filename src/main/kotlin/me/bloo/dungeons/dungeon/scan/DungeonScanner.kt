package me.bloo.dungeons.dungeon.scan

import kotlin.math.max
import kotlin.math.min
import me.bloo.dungeons.config.DungeonConfig
import me.bloo.dungeons.core.DungeonConstants
import me.bloo.dungeons.dungeon.model.Dungeon
import me.bloo.dungeons.dungeon.model.toBlockPos
import me.bloo.dungeons.dungeon.secret.SecretRoomManager
import net.minecraft.block.Blocks
import net.minecraft.registry.Registries
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos

/** Scan a dungeon region for our identifier blocks and spell out success vs. failure. */
object DungeonScanner {
    enum class ErrorReason {
        INVALID_BOUNDS,
        WRONG_DIMENSION,
        NO_IDENTIFIER_BLOCK,
        MISSING_ENTRANCE_CORNER_1,
        MISSING_ENTRANCE_CORNER_2,
        MISSING_EXIT_PAIR
    }

    data class Bounds(val min: BlockPos, val max: BlockPos) {
        override fun toString(): String {
            return "${min.x},${min.y},${min.z}..${max.x},${max.y},${max.z}"
        }
    }

    sealed interface ScanResult {
        val worldKey: Identifier
        val origin: BlockPos
        val bounds: Bounds
        data class Success(
            override val worldKey: Identifier,
            override val origin: BlockPos,
            override val bounds: Bounds,
            val spawn: BlockPos?,
            val roomBlocks: Map<String, List<BlockPos>>,
            val actionBlocks: Map<String, List<BlockPos>>,
            val blockCounts: Map<Identifier, Int>,
            val gildedChestMarkers: List<BlockPos>,
            val entranceCorners: Pair<BlockPos, BlockPos>,
            val exitCorners: Pair<BlockPos, BlockPos>,
            val expectedBlocks: Map<String, Identifier>
        ) : ScanResult

        data class Error(
            override val worldKey: Identifier,
            override val origin: BlockPos,
            override val bounds: Bounds,
            val reason: ErrorReason,
            val details: String,
            val expectedBlocks: Map<String, Identifier>,
            val foundCounts: Map<Identifier, Int>,
            val missingChunks: List<Pair<Int, Int>> = emptyList(),
            val playerMessage: String
        ) : ScanResult
    }

    fun scan(
        world: ServerWorld,
        dungeon: Dungeon,
        config: DungeonConfig = DungeonConfig.instance
    ): ScanResult {
        val worldKey = world.registryKey.value
        val settings = config.scanSettings()
        val expectedBlocks = expectedBlocks(config)
        val worldKeyString = worldKey.toString()
        val secretRoomBounds = SecretRoomManager.definitionsForType(dungeon.type)
            .filter { it.world == worldKeyString }
            .map { definition ->
                val c1 = definition.corner1.toBlockPos()
                val c2 = definition.corner2.toBlockPos()
                val minPos = BlockPos(min(c1.x, c2.x), min(c1.y, c2.y), min(c1.z, c2.z))
                val maxPos = BlockPos(max(c1.x, c2.x), max(c1.y, c2.y), max(c1.z, c2.z))
                minPos to maxPos
            }

        val c1 = dungeon.corner1
        val c2 = dungeon.corner2
        if (c1 == null || c2 == null) {
            return ScanResult.Error(
                worldKey = worldKey,
                origin = BlockPos.ORIGIN,
                bounds = Bounds(BlockPos.ORIGIN, BlockPos.ORIGIN),
                reason = ErrorReason.INVALID_BOUNDS,
                details = "Dungeon '${dungeon.name}' has undefined corner positions.",
                expectedBlocks = expectedBlocks,
                foundCounts = emptyMap(),
                playerMessage = "Dungeon cannot start: Dungeon bounds are not configured. Ask an admin to verify the region."
            )
        }

        val minX = min(c1.x, c2.x)
        val maxX = max(c1.x, c2.x)
        val minY = min(c1.y, c2.y)
        val maxY = max(c1.y, c2.y)
        val minZ = min(c1.z, c2.z)
        val maxZ = max(c1.z, c2.z)
        val bounds = Bounds(BlockPos(minX, minY, minZ), BlockPos(maxX, maxY, maxZ))
        val origin = BlockPos((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2)

        val configuredDimension = dungeon.dimension
        if (!configuredDimension.isNullOrBlank() && configuredDimension != worldKey.toString()) {
            return ScanResult.Error(
                worldKey = worldKey,
                origin = origin,
                bounds = bounds,
                reason = ErrorReason.WRONG_DIMENSION,
                details = "Dungeon '${dungeon.name}' is configured for dimension $configuredDimension but was scanned in ${worldKey}.",
                expectedBlocks = expectedBlocks,
                foundCounts = emptyMap(),
                playerMessage = "Dungeon cannot start: Wrong dimension. Enter the dungeon from $configuredDimension."
            )
        }

        val roomLookup = buildLookup(config.roomBlocks)
        val actionLookup = buildLookup(config.actionBlocks)

        val roomBlocks = mutableMapOf<String, MutableList<BlockPos>>()
        val actionBlocks = mutableMapOf<String, MutableList<BlockPos>>()
        val blockCounts = mutableMapOf<Identifier, Int>()
        val gildedChestMarkers = mutableListOf<BlockPos>()
        var spawn: BlockPos? = null

        val mutablePos = BlockPos.Mutable()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    mutablePos.set(x, y, z)
                    val state = world.getBlockState(mutablePos)
                    if (state.isAir) continue
                    val id = Registries.BLOCK.getId(state.block)
                    blockCounts[id] = (blockCounts[id] ?: 0) + 1

                    val pos = mutablePos.toImmutable()
                    val insideSecretRoom = secretRoomBounds.any { (minPos, maxPos) ->
                        pos.x in minPos.x..maxPos.x &&
                            pos.y in minPos.y..maxPos.y &&
                            pos.z in minPos.z..maxPos.z
                    }
                    if (!insideSecretRoom) {
                        if (state.isOf(Blocks.NETHERITE_BLOCK) || state.isOf(Blocks.SMOKER)) {
                            gildedChestMarkers.add(pos)
                        }

                        roomLookup[id]?.forEach { name ->
                            roomBlocks.getOrPut(name) { mutableListOf() }.add(pos)
                            if (name == DungeonConstants.SPAWN_MARKER_KEY && spawn == null) {
                                spawn = pos
                            }
                        }

                        actionLookup[id]?.forEach { name ->
                            actionBlocks.getOrPut(name) { mutableListOf() }.add(pos)
                        }
                    }
                }
            }
        }

        val bossMarkers = roomBlocks[DungeonConstants.BOSS_ROOM_MARKER_KEY] ?: emptyList()
        if (bossMarkers.isEmpty()) {
            return ScanResult.Error(
                worldKey = worldKey,
                origin = origin,
                bounds = bounds,
                reason = ErrorReason.NO_IDENTIFIER_BLOCK,
                details = "Dungeon '${dungeon.name}' is missing boss room markers (${DungeonConstants.ANCHOR_BLOCK_ID}).",
                expectedBlocks = expectedBlocks,
                foundCounts = blockCounts,
                playerMessage = "Dungeon cannot start: Missing boss room identifier blocks. Ask an admin to verify the layout."
            )
        }

        val entranceMarkers = actionBlocks[DungeonConstants.ENTRANCE_MARKER_KEY] ?: emptyList()
        if (entranceMarkers.isEmpty()) {
            return ScanResult.Error(
                worldKey = worldKey,
                origin = origin,
                bounds = bounds,
                reason = ErrorReason.MISSING_ENTRANCE_CORNER_1,
                details = "Dungeon '${dungeon.name}' is missing entrance door marker blocks.",
                expectedBlocks = expectedBlocks,
                foundCounts = blockCounts,
                playerMessage = missingMarkerMessage("entrance", expectedBlocks)
            )
        }
        if (entranceMarkers.size == 1) {
            return ScanResult.Error(
                worldKey = worldKey,
                origin = origin,
                bounds = bounds,
                reason = ErrorReason.MISSING_ENTRANCE_CORNER_2,
                details = "Dungeon '${dungeon.name}' has only one entrance door marker block.",
                expectedBlocks = expectedBlocks,
                foundCounts = blockCounts,
                playerMessage = missingMarkerMessage("entrance", expectedBlocks)
            )
        }

        val exitMarkers = actionBlocks[DungeonConstants.EXIT_MARKER_KEY] ?: emptyList()
        if (exitMarkers.size < 2) {
            return ScanResult.Error(
                worldKey = worldKey,
                origin = origin,
                bounds = bounds,
                reason = ErrorReason.MISSING_EXIT_PAIR,
                details = "Dungeon '${dungeon.name}' is missing exit door marker blocks.",
                expectedBlocks = expectedBlocks,
                foundCounts = blockCounts,
                playerMessage = missingMarkerMessage("exit", expectedBlocks)
            )
        }

        val entrancePair = entranceMarkers.sortedWith(compareBy(BlockPos::getX, BlockPos::getY, BlockPos::getZ)).take(2)
        val exitPair = exitMarkers.sortedWith(compareBy(BlockPos::getX, BlockPos::getY, BlockPos::getZ)).take(2)

        val success = ScanResult.Success(
            worldKey = worldKey,
            origin = origin,
            bounds = bounds,
            spawn = spawn,
            roomBlocks = roomBlocks.mapValues { it.value.toList() },
            actionBlocks = actionBlocks.mapValues { it.value.toList() },
            blockCounts = blockCounts.toMap(),
            gildedChestMarkers = gildedChestMarkers.toList(),
            entranceCorners = entrancePair[0] to entrancePair[1],
            exitCorners = exitPair[0] to exitPair[1],
            expectedBlocks = expectedBlocks
        )

        if (settings.logDebug) {
            val entranceLabel = "${formatPos(success.entranceCorners.first)} & ${formatPos(success.entranceCorners.second)}"
            val exitLabel = "${formatPos(success.exitCorners.first)} & ${formatPos(success.exitCorners.second)}"
            val sizeLabel = "${maxX - minX + 1}x${maxY - minY + 1}x${maxZ - minZ + 1}"
            println(
                "[Dungeons][Scan] ${dungeon.name}: world=${worldKey} bounds=${bounds} size=$sizeLabel " +
                    "entrance=$entranceLabel exit=$exitLabel"
            )
        }

        return success
    }

    private fun expectedBlocks(config: DungeonConfig): Map<String, Identifier> {
        val map = mutableMapOf<String, Identifier>()
        config.actionBlocks[DungeonConstants.ENTRANCE_MARKER_KEY]?.let { id ->
            Identifier.tryParse(id)?.let { parsed -> map[DungeonConstants.ENTRANCE_MARKER_KEY] = parsed }
        }
        config.actionBlocks[DungeonConstants.EXIT_MARKER_KEY]?.let { id ->
            Identifier.tryParse(id)?.let { parsed -> map[DungeonConstants.EXIT_MARKER_KEY] = parsed }
        }
        config.roomBlocks[DungeonConstants.BOSS_ROOM_MARKER_KEY]?.let { id ->
            Identifier.tryParse(id)?.let { parsed -> map[DungeonConstants.BOSS_ROOM_MARKER_KEY] = parsed }
        }
        config.roomBlocks[DungeonConstants.SPAWN_MARKER_KEY]?.let { id ->
            Identifier.tryParse(id)?.let { parsed -> map[DungeonConstants.SPAWN_MARKER_KEY] = parsed }
        }
        return map
    }

    private fun buildLookup(entries: Map<String, String>): Map<Identifier, List<String>> {
        val lookup = mutableMapOf<Identifier, MutableList<String>>()
        entries.forEach { (name, value) ->
            Identifier.tryParse(value)?.let { id ->
                lookup.getOrPut(id) { mutableListOf() }.add(name)
            }
        }
        return lookup
    }

    private fun formatPos(pos: BlockPos): String = "${pos.x},${pos.y},${pos.z}"

    private fun missingMarkerMessage(type: String, expected: Map<String, Identifier>): String {
        val ids = when (type) {
            "entrance" -> listOfNotNull(expected[DungeonConstants.ENTRANCE_MARKER_KEY])
            "exit" -> listOfNotNull(expected[DungeonConstants.EXIT_MARKER_KEY])
            else -> emptyList()
        }
        val label = if (ids.isEmpty()) type else "$type (${ids.joinToString { it.toString() }})"
        return "Dungeon cannot start: Missing $label marker blocks. Ask an admin to verify door corners and room identifier."
    }
}
