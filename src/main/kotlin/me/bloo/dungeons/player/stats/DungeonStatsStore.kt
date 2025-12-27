package me.bloo.dungeons.player.stats

import java.util.UUID
import me.bloo.dungeons.party.PartyService
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import net.minecraft.world.PersistentStateManager

/**
 * Persist dungeon completion stats per player using the overworld persistent state manager.
 */
object DungeonStatsStore {
    private const val STATE_ID = "dungeon_stats"
    private val lock = Any()

    data class PlayerStats(
        var completions: Int = 0,
        var legendaryRuns: Int = 0,
        val dungeonTypesCompleted: MutableSet<String> = mutableSetOf()
    )

    @Volatile
    private var cachedState: DungeonStatsState? = null

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

    fun incrementLegendary(players: Collection<UUID>) {
        if (players.isEmpty()) return
        synchronized(lock) {
            val state = ensureState() ?: return
            players.forEach { uuid ->
                val stats = state.stats.getOrPut(uuid) { PlayerStats() }
                stats.legendaryRuns += 1
            }
            state.markDirty()
        }
    }

    fun recordCompletion(players: Collection<UUID>, dungeonType: String) {
        if (players.isEmpty()) return
        val normalizedType = dungeonType.trim().lowercase()
        synchronized(lock) {
            val state = ensureState() ?: return
            players.forEach { uuid ->
                val stats = state.stats.getOrPut(uuid) { PlayerStats() }
                stats.completions += 1
                if (normalizedType.isNotEmpty()) {
                    stats.dungeonTypesCompleted.add(normalizedType)
                }
            }
            state.markDirty()
        }
    }

    fun getStats(uuid: UUID): PlayerStats {
        synchronized(lock) {
            return currentState()?.stats?.get(uuid)?.copy() ?: PlayerStats()
        }
    }

    private fun ensureState(): DungeonStatsState? {
        val server = PartyService.server ?: return null
        synchronized(lock) {
            val existing = cachedState
            if (existing != null) return existing
            val state = loadState(server.overworld.persistentStateManager)
            cachedState = state
            return state
        }
    }

    private fun currentState(): DungeonStatsState? {
        val cached = cachedState
        if (cached != null) return cached
        val server = PartyService.server ?: return null
        synchronized(lock) {
            val refreshed = cachedState
            if (refreshed != null) return refreshed
            val state = loadState(server.overworld.persistentStateManager)
            cachedState = state
            return state
        }
    }

    private fun loadState(manager: PersistentStateManager): DungeonStatsState {
        return manager.getOrCreate(STATE_TYPE, STATE_ID)
    }

    private class DungeonStatsState : PersistentState() {
        val stats: MutableMap<UUID, PlayerStats> = mutableMapOf()

        override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
            val list = NbtList()
            for ((uuid, entry) in stats) {
                val compound = NbtCompound()
                compound.putUuid("uuid", uuid)
                compound.putInt("completions", entry.completions)
                compound.putInt("legendaryRuns", entry.legendaryRuns)
                val types = NbtList()
                entry.dungeonTypesCompleted.forEach { type ->
                    types.add(NbtCompound().apply { putString("id", type) })
                }
                compound.put("types", types)
                list.add(compound)
            }
            nbt.put("entries", list)
            return nbt
        }

        companion object {
            fun fromNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): DungeonStatsState {
                val state = DungeonStatsState()
                val entries = nbt.getList("entries", NbtElement.COMPOUND_TYPE.toInt())
                for (index in 0 until entries.size) {
                    val compound = entries.getCompound(index)
                    if (!compound.containsUuid("uuid")) continue
                    val uuid = compound.getUuid("uuid")
                    val completions = compound.getInt("completions").coerceAtLeast(0)
                    val legendary = compound.getInt("legendaryRuns").coerceAtLeast(0)
                    val typesList = compound.getList("types", NbtElement.COMPOUND_TYPE.toInt())
                    val types = mutableSetOf<String>()
                    for (typeIndex in 0 until typesList.size) {
                        val typeCompound = typesList.getCompound(typeIndex)
                        val id = typeCompound.getString("id").trim()
                        if (id.isNotEmpty()) {
                            types.add(id.lowercase())
                        }
                    }
                    state.stats[uuid] = PlayerStats(completions, legendary, types)
                }
                return state
            }
        }
    }

    private val STATE_TYPE = PersistentState.Type(::DungeonStatsState, DungeonStatsState::fromNbt, null)
}
