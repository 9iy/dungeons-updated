package me.bloo.dungeons.dungeon.runtime

import java.util.concurrent.CompletableFuture
import kotlin.comparisons.compareBy
import me.bloo.dungeons.config.DungeonConfig
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.ButtonBlock
import net.minecraft.block.LeverBlock
import net.minecraft.block.TorchBlock
import net.minecraft.block.TripwireBlock
import net.minecraft.block.TripwireHookBlock
import net.minecraft.block.VineBlock
import net.minecraft.block.WallTorchBlock
import net.minecraft.registry.RegistryKey
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import net.minecraft.world.chunk.ChunkStatus
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object DoorManager {
    private const val EXPECTED_WIDTH = 5
    private const val EXPECTED_HEIGHT = 6
    private const val DOOR_UPDATE_FLAGS =
        Block.NOTIFY_ALL or Block.FORCE_STATE or Block.REDRAW_ON_MAIN_THREAD

    private val DENIED_BLOCKS = setOf(
        Blocks.CHEST,
        Blocks.TRAPPED_CHEST,
        Blocks.BARREL,
        Blocks.ENDER_CHEST,
        Blocks.SPAWNER,
        Blocks.COMMAND_BLOCK,
        Blocks.CHAIN_COMMAND_BLOCK,
        Blocks.REPEATING_COMMAND_BLOCK,
        Blocks.STRUCTURE_BLOCK,
        Blocks.JIGSAW,
        Blocks.END_GATEWAY,
        Blocks.END_PORTAL,
        Blocks.END_PORTAL_FRAME,
        Blocks.REINFORCED_DEEPSLATE,
        Blocks.NETHER_PORTAL
    )

    enum class DoorType { ENTRANCE, EXIT }

    enum class DoorState { LOCKED, UNLOCKED }

    enum class Plane { XY, YZ }

    data class RoomKey(val sessionId: String, val roomIndex: Int)

    data class DoorKey(val roomKey: RoomKey, val type: DoorType)

    data class Door(
        val key: DoorKey,
        val type: DoorType,
        val markers: Pair<BlockPos, BlockPos>,
        val plane: Plane,
        val interior: List<BlockPos>,
        val state: DoorState
    )

    private data class DoorCandidate(
        val plane: Plane,
        val markerA: BlockPos,
        val markerB: BlockPos,
        val min: BlockPos,
        val max: BlockPos,
        val interior: List<BlockPos>,
        val volume: Int,
        val horizontalArea: Int,
        val height: Int,
        val width: Int
    )

    private data class TrackedDoor(
        val key: DoorKey,
        val type: DoorType,
        val plane: Plane,
        val markerA: BlockPos,
        val markerB: BlockPos,
        val min: BlockPos,
        val max: BlockPos,
        val interior: List<BlockPos>,
        val barrierState: BlockState,
        val dimension: RegistryKey<World>,
        var state: DoorState = DoorState.UNLOCKED,
        val changedBlocks: MutableMap<BlockPos, BlockState> = mutableMapOf()
    )

    private val doors = mutableMapOf<DoorKey, TrackedDoor>()
    private val roomDoors = mutableMapOf<RoomKey, MutableSet<DoorKey>>()

    fun findDoorsInRoom(
        world: ServerWorld,
        sessionId: String,
        roomIndex: Int,
        roomMin: BlockPos,
        roomMax: BlockPos,
        entranceMarkers: Collection<BlockPos>,
        exitMarkers: Collection<BlockPos>,
        barrierState: BlockState = Blocks.WAXED_OXIDIZED_COPPER_GRATE.defaultState
    ): List<Door> {
        val roomKey = RoomKey(sessionId, roomIndex)
        forgetRoom(roomKey)
        val results = mutableListOf<Door>()
        registerDoor(
            world = world,
            roomKey = roomKey,
            type = DoorType.ENTRANCE,
            roomMin = roomMin,
            roomMax = roomMax,
            markers = entranceMarkers,
            barrierState = barrierState
        )?.let { results.add(it.toPublic()) }
        registerDoor(
            world = world,
            roomKey = roomKey,
            type = DoorType.EXIT,
            roomMin = roomMin,
            roomMax = roomMax,
            markers = exitMarkers,
            barrierState = barrierState
        )?.let { results.add(it.toPublic()) }
        return results
    }

    fun lockDoor(world: ServerWorld, doorKey: DoorKey?): Boolean {
        if (doorKey == null) return false
        val tracked = doors[doorKey] ?: return false
        if (tracked.state == DoorState.LOCKED) return false
        if (!ensureSameWorld(world, tracked)) return false
        return executeOnMainThread(world) {
            ensureChunksLoaded(world, tracked)
            var placed = 0
            var skipped = 0
            var denied = 0
            tracked.changedBlocks.clear()
            for (pos in tracked.interior) {
                val state = world.getBlockState(pos)
                if (state == tracked.barrierState) {
                    skipped++
                    continue
                }
                if (isDeniedBlock(state)) {
                    denied++
                    continue
                }
                if (!isReplaceable(state)) {
                    skipped++
                    continue
                }
                tracked.changedBlocks[pos] = state
                world.setBlockState(pos, tracked.barrierState, DOOR_UPDATE_FLAGS)
                placed++
            }
            tracked.state = DoorState.LOCKED
            logCloseSummary(tracked, placed, skipped, denied)
            placed > 0
        }
    }

    fun unlockDoor(world: ServerWorld, doorKey: DoorKey?): Boolean {
        if (doorKey == null) return false
        val tracked = doors[doorKey] ?: return false
        if (!ensureSameWorld(world, tracked)) return false
        return executeOnMainThread(world) {
            ensureChunksLoaded(world, tracked)
            val wasLocked = tracked.state == DoorState.LOCKED
            var restored = 0
            var missing = 0
            val entries = tracked.changedBlocks.entries.toList()
            for ((pos, original) in entries) {
                val current = world.getBlockState(pos)
                if (!current.isOf(tracked.barrierState.block)) {
                    missing++
                }
                if (current != original) {
                    world.setBlockState(pos, original, DOOR_UPDATE_FLAGS)
                    restored++
                }
            }
            if (wasLocked) {
                val airState = Blocks.AIR.defaultState
                for (pos in tracked.interior) {
                    val current = world.getBlockState(pos)
                    if (current == tracked.barrierState) {
                        world.setBlockState(pos, airState, DOOR_UPDATE_FLAGS)
                        restored++
                    }
                }
            }
            tracked.changedBlocks.clear()
            tracked.state = DoorState.UNLOCKED
            logOpenSummary(tracked, restored, missing)
            restored > 0 || wasLocked
        }
    }

    fun lockAllDoors(world: ServerWorld, roomKey: RoomKey?): Boolean {
        if (roomKey == null) return false
        val keys = roomDoors[roomKey] ?: return false
        var changed = false
        keys.forEach { key -> if (lockDoor(world, key)) changed = true }
        return changed
    }

    fun unlockAllDoors(world: ServerWorld, roomKey: RoomKey?): Boolean {
        if (roomKey == null) return false
        val keys = roomDoors[roomKey] ?: return false
        var changed = false
        keys.forEach { key -> if (unlockDoor(world, key)) changed = true }
        return changed
    }

    fun getDoorState(doorKey: DoorKey?): DoorState? {
        if (doorKey == null) return null
        return doors[doorKey]?.state
    }

    fun forgetRoom(roomKey: RoomKey) {
        roomDoors.remove(roomKey)?.forEach { key -> doors.remove(key) }
    }

    private fun registerDoor(
        world: ServerWorld,
        roomKey: RoomKey,
        type: DoorType,
        roomMin: BlockPos,
        roomMax: BlockPos,
        markers: Collection<BlockPos>,
        barrierState: BlockState
    ): TrackedDoor? {
        val strictSize = DungeonConfig.instance.doorStrictSize
        val candidates = markers
            .filter { pos ->
                pos.x in roomMin.x..roomMax.x &&
                    pos.y in roomMin.y..roomMax.y &&
                    pos.z in roomMin.z..roomMax.z
            }
            .map { it.toImmutable() }
        if (candidates.size < 2) {
            logSkipping(roomKey, type, "expected at least 2 markers, found ${candidates.size}")
            return null
        }
        val failureReasons = mutableListOf<String>()
        val valid = mutableListOf<DoorCandidate>()
        for (i in 0 until candidates.size - 1) {
            val markerA = candidates[i]
            for (j in i + 1 until candidates.size) {
                val markerB = candidates[j]
                val candidate = createCandidate(
                    roomKey = roomKey,
                    type = type,
                    roomMin = roomMin,
                    roomMax = roomMax,
                    markerA = markerA,
                    markerB = markerB,
                    barrierState = barrierState,
                    strictSize = strictSize,
                    failureReasons = failureReasons
                )
                if (candidate != null) {
                    valid.add(candidate)
                }
            }
        }
        if (valid.isEmpty()) {
            val reason = failureReasons.firstOrNull() ?: "no valid marker pairs"
            logSkipping(roomKey, type, reason)
            return null
        }
        val comparator = compareBy<DoorCandidate>({ it.volume }, { it.horizontalArea }, { it.height })
        val best = valid.maxWith(comparator)
        val key = DoorKey(roomKey, type)
        val tracked = TrackedDoor(
            key = key,
            type = type,
            plane = best.plane,
            markerA = best.markerA,
            markerB = best.markerB,
            min = best.min,
            max = best.max,
            interior = best.interior,
            barrierState = barrierState,
            dimension = world.registryKey,
            state = DoorState.UNLOCKED
        )
        doors[key] = tracked
        roomDoors.getOrPut(roomKey) { mutableSetOf() }.add(key)
        log(
            tracked,
            "paired markers=(${formatPos(best.markerA)}) & (${formatPos(best.markerB)}) bounds=${formatPos(best.min)}..${formatPos(best.max)} volume=${best.volume}"
        )
        return tracked
    }

    private fun createCandidate(
        roomKey: RoomKey,
        type: DoorType,
        roomMin: BlockPos,
        roomMax: BlockPos,
        markerA: BlockPos,
        markerB: BlockPos,
        barrierState: BlockState,
        strictSize: Boolean,
        failureReasons: MutableList<String>
    ): DoorCandidate? {
        val sameX = markerA.x == markerB.x
        val sameZ = markerA.z == markerB.z
        if (sameX == sameZ) {
            failureReasons.add("markers ${formatPos(markerA)} & ${formatPos(markerB)} not aligned on a single plane")
            return null
        }
        val plane = if (sameX) Plane.YZ else Plane.XY
        val minX = min(markerA.x, markerB.x)
        val maxX = max(markerA.x, markerB.x)
        val minY = min(markerA.y, markerB.y)
        val maxY = max(markerA.y, markerB.y)
        val minZ = min(markerA.z, markerB.z)
        val maxZ = max(markerA.z, markerB.z)
        if (maxY == minY) {
            failureReasons.add("markers ${formatPos(markerA)} & ${formatPos(markerB)} lack vertical separation")
            return null
        }
        val minPos = BlockPos(minX, minY, minZ)
        val maxPos = BlockPos(maxX, maxY, maxZ)
        val height = maxY - minY + 1
        val width = if (plane == Plane.XY) maxX - minX + 1 else maxZ - minZ + 1
        if (strictSize && (width != EXPECTED_WIDTH || height != EXPECTED_HEIGHT)) {
            failureReasons.add("markers ${formatPos(markerA)} & ${formatPos(markerB)} size mismatch width=$width height=$height")
            return null
        }
        val interior = mutableListOf<BlockPos>()
        val mutable = BlockPos.Mutable()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    mutable.set(x, y, z)
                    val pos = mutable.toImmutable()
                    if (pos == markerA || pos == markerB) continue
                    if (!inside(pos, roomMin, roomMax)) {
                        failureReasons.add("interior ${formatPos(pos)} outside room bounds")
                        return null
                    }
                    interior.add(pos)
                }
            }
        }
        if (interior.isEmpty()) {
            failureReasons.add("markers ${formatPos(markerA)} & ${formatPos(markerB)} produced empty interior")
            return null
        }
        val volume = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1)
        val horizontalArea = (maxX - minX + 1) * (maxZ - minZ + 1)
        return DoorCandidate(
            plane = plane,
            markerA = markerA,
            markerB = markerB,
            min = minPos,
            max = maxPos,
            interior = interior.toList(),
            volume = volume,
            horizontalArea = horizontalArea,
            height = height,
            width = width
        )
    }

    private fun ensureChunksLoaded(world: ServerWorld, door: TrackedDoor) {
        val minChunkX = door.min.x shr 4
        val maxChunkX = door.max.x shr 4
        val minChunkZ = door.min.z shr 4
        val maxChunkZ = door.max.z shr 4
        for (cx in minChunkX..maxChunkX) {
            for (cz in minChunkZ..maxChunkZ) {
                if (!world.isChunkLoaded(cx, cz)) {
                    log(door, "loading chunk $cx,$cz")
                    world.chunkManager.getChunk(cx, cz, ChunkStatus.FULL, true)
                }
            }
        }
    }

    private fun ensureSameWorld(world: ServerWorld, door: TrackedDoor): Boolean {
        val same = door.dimension == world.registryKey
        if (!same) {
            log(door, "world mismatch expected=${door.dimension.value} actual=${world.registryKey.value}")
        }
        return same
    }

    private fun executeOnMainThread(world: ServerWorld, action: () -> Boolean): Boolean {
        val server = world.server
        return if (server.isOnThread) {
            action()
        } else {
            val future = CompletableFuture<Boolean>()
            server.execute { future.complete(action()) }
            future.join()
        }
    }

    private fun TrackedDoor.toPublic(): Door {
        return Door(key, key.type, markerA to markerB, plane, interior.toList(), state)
    }

    private fun inside(pos: BlockPos, min: BlockPos, max: BlockPos): Boolean {
        return pos.x in min.x..max.x && pos.y in min.y..max.y && pos.z in min.z..max.z
    }

    private fun isReplaceable(state: BlockState): Boolean {
        if (state.isAir) return true
        if (!state.fluidState.isEmpty) return true
        val block = state.block
        return block is VineBlock ||
                block is TorchBlock ||
                block is WallTorchBlock ||
                block is ButtonBlock ||
                block is LeverBlock ||
                block is TripwireBlock ||
                block is TripwireHookBlock
    }

    private fun isDeniedBlock(state: BlockState): Boolean {
        val block = state.block
        return state.hasBlockEntity() || block in DENIED_BLOCKS
    }

    private fun logCloseSummary(door: TrackedDoor, placed: Int, skipped: Int, denied: Int) {
        println("[Dungeons] Closed ${door.type.name.lowercase()} door: placed=$placed skipped=$skipped denied=$denied")
        log(door, "closed placed=$placed skipped=$skipped denied=$denied")
    }

    private fun logOpenSummary(door: TrackedDoor, restored: Int, missing: Int) {
        println("[Dungeons] Opened ${door.type.name.lowercase()} door: restored=$restored missing=$missing")
        log(door, "opened restored=$restored missing=$missing")
    }

    private fun logSkipping(roomKey: RoomKey, type: DoorType, reason: String) {
        println("[Dungeons] Skipping ${type.name.lowercase()} door for ${roomKey.sessionId}#${roomKey.roomIndex}: $reason")
    }

    private fun log(door: TrackedDoor, message: String) {
        val key = door.key
        println("[Dungeons][DoorManager] room=${key.roomKey.sessionId}#${key.roomKey.roomIndex} type=${key.type} $message")
    }

    private fun formatPos(pos: BlockPos): String {
        return "${pos.x},${pos.y},${pos.z}"
    }
}
