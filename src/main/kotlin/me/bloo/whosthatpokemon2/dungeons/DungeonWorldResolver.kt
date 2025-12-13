package me.bloo.whosthatpokemon2.dungeons

import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier

object DungeonWorldResolver {
    fun resolve(
        server: MinecraftServer,
        dungeon: Dungeon,
        fallback: ServerWorld? = null
    ): ServerWorld? {
        val configuredDimension = dungeon.dimension
        if (configuredDimension.isNullOrBlank()) {
            return fallback
        }
        val identifier = Identifier.tryParse(configuredDimension) ?: return null
        val key = RegistryKey.of(RegistryKeys.WORLD, identifier)
        return server.getWorld(key)
    }
}
