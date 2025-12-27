package me.bloo.whosthatpokemon2.dungeons

import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnReason
import me.bloo.whosthatpokemon2.dungeons.message.sendDungeonMessage

interface RoomAction {
    fun run(ctx: ActionContext)
}

data class ActionContext(
    val server: MinecraftServer,
    val player: ServerPlayerEntity,
    val dungeon: Dungeon,
    val room: DungeonRuntime.Room
)

class CommandAction(private val commands: List<String>) : RoomAction {
    override fun run(ctx: ActionContext) {
        commands.forEach { cmd ->
            ctx.server.commandManager.executeWithPrefix(ctx.server.commandSource, cmd)
        }
    }
}

class SayAction(private val message: String) : RoomAction {
    override fun run(ctx: ActionContext) {
        ctx.player.sendDungeonMessage(message)
    }
}

class SpawnAction(private val entityId: String, private val count: Int = 1) : RoomAction {
    override fun run(ctx: ActionContext) {
        val type = EntityType.get(entityId).orElse(null) ?: return
        val world = ctx.player.serverWorld
        repeat(count) { type.spawn(world, ctx.room.center, SpawnReason.COMMAND) }
    }
}
