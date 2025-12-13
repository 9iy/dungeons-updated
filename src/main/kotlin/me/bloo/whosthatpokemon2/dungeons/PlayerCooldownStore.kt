package me.bloo.whosthatpokemon2.dungeons

import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import net.minecraft.world.PersistentStateManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.max

/**
 * Persist per-player dungeon start cooldowns using the overworld persistent state manager.
 */
object PlayerCooldownStore {
    private const val STATE_ID = "dungeon_cooldowns"
    private val lock = Any()

    @Volatile
    private var cachedState: DungeonCooldownState? = null

    fun onServerStarted(server: MinecraftServer) {
        synchronized(lock) {
            cachedState = loadState(server.overworld.persistentStateManager)
        }
    }

    fun onServerStopped() {
        synchronized(lock) {
            cachedState = null
        }
    }

    fun isDungeonTimerRunning(uuid: UUID, now: Instant): Boolean {
        synchronized(lock) {
            val state = currentState() ?: return false
            val expires = state.cooldowns[uuid] ?: return false
            if (expires <= 0L) {
                state.cooldowns.remove(uuid)
                state.markDirty()
                return false
            }
            val endInstant = Instant.ofEpochMilli(expires)
            return if (now.isBefore(endInstant)) {
                true
            } else {
                state.cooldowns.remove(uuid)
                state.markDirty()
                false
            }
        }
    }

    fun getDungeonTimerRemaining(uuid: UUID, now: Instant): Duration {
        synchronized(lock) {
            val state = currentState() ?: return Duration.ZERO
            val expires = state.cooldowns[uuid] ?: return Duration.ZERO
            if (expires <= 0L) {
                state.cooldowns.remove(uuid)
                state.markDirty()
                return Duration.ZERO
            }
            val endInstant = Instant.ofEpochMilli(expires)
            return if (now.isBefore(endInstant)) {
                Duration.between(now, endInstant)
            } else {
                state.cooldowns.remove(uuid)
                state.markDirty()
                Duration.ZERO
            }
        }
    }

    fun startDungeonTimer(uuid: UUID, now: Instant, duration: Duration) {
        synchronized(lock) {
            val state = ensureState() ?: return
            if (duration.isZero || duration.isNegative) {
                state.cooldowns.remove(uuid)
            } else {
                val safeDuration = duration.coerceAtMost(Duration.ofDays(365))
                val endInstant = now.plus(safeDuration)
                state.cooldowns[uuid] = endInstant.toEpochMilli()
            }
            state.markDirty()
        }
    }

    fun resetDungeonTimer(uuid: UUID): Boolean {
        synchronized(lock) {
            val state = currentState() ?: return false
            val removed = state.cooldowns.remove(uuid) != null
            if (removed) {
                state.markDirty()
            }
            return removed
        }
    }

    fun resetAll(players: Collection<UUID>): Int {
        var count = 0
        players.forEach { uuid -> if (resetDungeonTimer(uuid)) count++ }
        return count
    }

    private fun ensureState(): DungeonCooldownState? {
        val server = PartyService.server ?: return null
        synchronized(lock) {
            val existing = cachedState
            if (existing != null) {
                return existing
            }
            val manager = server.overworld.persistentStateManager
            val state = loadState(manager)
            cachedState = state
            return state
        }
    }

    private fun currentState(): DungeonCooldownState? {
        val cached = cachedState
        if (cached != null) {
            return cached
        }
        val server = PartyService.server ?: return null
        synchronized(lock) {
            val refreshed = cachedState
            if (refreshed != null) {
                return refreshed
            }
            val manager = server.overworld.persistentStateManager
            val loaded = loadState(manager)
            cachedState = loaded
            return loaded
        }
    }

    private fun loadState(manager: PersistentStateManager): DungeonCooldownState {
        return manager.getOrCreate(STATE_TYPE, STATE_ID)
    }

    private class DungeonCooldownState : PersistentState() {
        val cooldowns: MutableMap<UUID, Long> = mutableMapOf()

        override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
            val list = NbtList()
            for ((uuid, timestamp) in cooldowns) {
                val entry = NbtCompound()
                entry.putUuid("uuid", uuid)
                entry.putLong("cooldownEnd", timestamp)
                list.add(entry)
            }
            nbt.put("entries", list)
            return nbt
        }

        companion object {
            fun fromNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): DungeonCooldownState {
                val state = DungeonCooldownState()
                val list = nbt.getList("entries", NbtElement.COMPOUND_TYPE.toInt())
                for (index in 0 until list.size) {
                    val compound = list.getCompound(index)
                    if (!compound.containsUuid("uuid")) continue
                    val uuid = compound.getUuid("uuid")
                    val timestamp = compound.getLong("cooldownEnd")
                    state.cooldowns[uuid] = max(0L, timestamp)
                }
                return state
            }
        }
    }

    private val STATE_TYPE = PersistentState.Type(::DungeonCooldownState, DungeonCooldownState::fromNbt, null)
}
