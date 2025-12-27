package me.bloo.dungeons.dungeon.model

import net.minecraft.util.math.BlockPos

/**
 * Map out a dungeon region using two corner anchors.
 */
data class Dungeon(
    val name: String,
    val type: String,
    val corner1: BlockPosDto?,
    val corner2: BlockPosDto?,
    val block: String = "minecraft:stone",
    val taken: Boolean = false,
    val dimension: String? = null,
    val activatorBlocks: Map<String, List<BlockPosDto>> = emptyMap(),
    val gildedChestPositions: List<BlockPosDto> = emptyList()
)

/** Simple serializable block position for config handoffs. */
data class BlockPosDto(val x: Int, val y: Int, val z: Int)

fun BlockPosDto.toBlockPos(): BlockPos = BlockPos(x, y, z)

fun BlockPos.toDto(): BlockPosDto = BlockPosDto(this.x, this.y, this.z)
