package me.bloo.whosthatpokemon2.dungeons

import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.entity.Entity
import net.minecraft.server.command.ServerCommandSource

fun Entity.hasPermission(node: String): Boolean = Permissions.check(this, node)

fun ServerCommandSource.hasPermission(node: String): Boolean {
    return entity?.hasPermission(node) ?: true
}
