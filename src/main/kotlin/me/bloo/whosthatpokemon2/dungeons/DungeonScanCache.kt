package me.bloo.whosthatpokemon2.dungeons

import net.minecraft.server.world.ServerWorld

/**
 * Cache dungeon scan results so we’re not rescanning every tick.
 * Anything older than 200 ticks (10s) gets refreshed when touched.
 */
object DungeonScanCache {
    private data class CacheEntry(val result: DungeonScanner.ScanResult, var tick: Long)
    private val cache = mutableMapOf<String, CacheEntry>()

    fun scan(world: ServerWorld, dungeon: Dungeon): DungeonScanner.ScanResult {
        val now = world.time
        val entry = cache[dungeon.name]
        return if (entry == null || now - entry.tick > 200) {
            val res = DungeonScanner.scan(world, dungeon)
            cache[dungeon.name] = CacheEntry(res, now)
            res
        } else {
            entry.result
        }
    }

    fun clear() { cache.clear() }
}
