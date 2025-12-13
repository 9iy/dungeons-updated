package me.bloo.whosthatpokemon2.dungeons.world

import net.minecraft.server.world.ChunkTicketType
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.registry.RegistryKey
import net.minecraft.world.World
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.max
import java.util.Comparator

/**
 * Pins (keeps loaded/registered) all chunks intersecting a rectangular area while a dungeon run is active.
 * Uses non-ticking tickets by default; can switch to ticking (setChunkForced) via chunkpin.json.
 */
data class ChunkLease(
    val worldKey: RegistryKey<World>,
    val runId: UUID,
    val chunks: Set<Long>
)

object ChunkPin {
    // runId -> lease
    private val ACTIVE = ConcurrentHashMap<UUID, ChunkLease>()

    // Custom non-ticking ticket type; comparator = natural order; distance = 0; alwaysTicking=false
    private val DUNGEON_TICKET: ChunkTicketType<Int> =
        ChunkTicketType.create("dungeon_bound", Comparator.naturalOrder<Int>(), 0)

    fun pinArea(world: ServerWorld, cornerA: BlockPos, cornerB: BlockPos, runId: UUID): ChunkLease {
        val minX = min(cornerA.x, cornerB.x)
        val minZ = min(cornerA.z, cornerB.z)
        val maxX = max(cornerA.x, cornerB.x)
        val maxZ = max(cornerA.z, cornerB.z)

        val minChunkX = Math.floorDiv(minX, 16)
        val maxChunkX = Math.floorDiv(maxX, 16)
        val minChunkZ = Math.floorDiv(minZ, 16)
        val maxChunkZ = Math.floorDiv(maxZ, 16)

        val ticking = ChunkPinConfig.get().forceTickingChunks

        val newlyPinned = buildSet {
            var cx = minChunkX
            while (cx <= maxChunkX) {
                var cz = minChunkZ
                while (cz <= maxChunkZ) {
                    if (ticking) {
                        world.setChunkForced(cx, cz, true)
                    } else {
                        pinNonTicking(world, cx, cz)
                    }
                    add(ChunkPos.toLong(cx, cz))
                    cz++
                }
                cx++
            }
        }

        val merged = ACTIVE[runId]?.let { old ->
            ChunkLease(world.registryKey, runId, old.chunks + newlyPinned)
        } ?: ChunkLease(world.registryKey, runId, newlyPinned)

        ACTIVE[runId] = merged
        println("[Dungeons] ChunkPin pinned ${newlyPinned.size} chunks (total=${merged.chunks.size}) for run=$runId in ${world.registryKey.value}")
        return merged
    }

    fun releaseAll(world: ServerWorld, runId: UUID) {
        val lease = ACTIVE.remove(runId) ?: return
        if (lease.worldKey != world.registryKey) return

        val ticking = ChunkPinConfig.get().forceTickingChunks

        lease.chunks.forEach { packed ->
            val cx = ChunkPos.getPackedX(packed)
            val cz = ChunkPos.getPackedZ(packed)
            if (ticking) {
                world.setChunkForced(cx, cz, false)
            } else {
                unpinNonTicking(world, cx, cz)
            }
        }
        println("[Dungeons] ChunkPin released ${lease.chunks.size} chunks for run=$runId in ${world.registryKey.value}")
    }

    /** Defensive cleanup on world unload / mod disable. */
    fun releaseAllForWorld(world: ServerWorld) {
        val ids = ACTIVE.values.filter { it.worldKey == world.registryKey }.map { it.runId }
        ids.forEach { releaseAll(world, it) }
    }

    private fun pinNonTicking(world: ServerWorld, cx: Int, cz: Int) {
        world.chunkManager.addTicket(DUNGEON_TICKET, ChunkPos(cx, cz), 1, 0)
    }

    private fun unpinNonTicking(world: ServerWorld, cx: Int, cz: Int) {
        world.chunkManager.removeTicket(DUNGEON_TICKET, ChunkPos(cx, cz), 1, 0)
    }
}
