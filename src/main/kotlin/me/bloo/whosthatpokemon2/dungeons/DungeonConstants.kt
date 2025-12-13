package me.bloo.whosthatpokemon2.dungeons

import net.minecraft.util.Identifier

object DungeonConstants {
    const val ANCHOR_BLOCK_ID: String = "cobblemon:apricorn_log"
    const val REVALIDATE_MS: Long = 1_500L
    const val REVALIDATE_TICKS: Long = 30L

    const val ENTRANCE_MARKER_KEY: String = "frontDoor"
    const val EXIT_MARKER_KEY: String = "finalDoor"
    const val BOSS_ROOM_MARKER_KEY: String = "boss"
    const val SPAWN_MARKER_KEY: String = "spawn"

    val PORTAL_FRAME_BLOCK_IDS: Set<Identifier> = setOf(
        Identifier.of("minecraft:reinforced_deepslate")
    )

    val PORTAL_BLOCK_IDS: Set<Identifier> = PORTAL_FRAME_BLOCK_IDS + Identifier.of("minecraft:end_portal")
}
