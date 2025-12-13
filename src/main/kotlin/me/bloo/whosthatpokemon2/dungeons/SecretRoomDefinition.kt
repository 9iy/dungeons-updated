package me.bloo.whosthatpokemon2.dungeons

/**
 * Describe a secret room layout tied to a specific dungeon type.
 */
data class SecretRoomDefinition(
    val id: String,
    val dungeonType: String,
    val world: String,
    val corner1: BlockPosDto,
    val corner2: BlockPosDto,
    val trapdoors: List<BlockPosDto>,
    val readyAreaMin: BlockPosDto,
    val readyAreaMax: BlockPosDto,
    val bossSpawn: BlockPosDto,
    val bossTeleportTargets: List<BlockPosDto>
)
