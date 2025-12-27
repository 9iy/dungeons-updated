package me.bloo.whosthatpokemon2.dungeons.message

import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text

enum class DungeonMessageType {
    NORMAL,
    ERROR
}

private const val PREFIX = "§6§l[ DUNGEONS ] "

fun dungeonText(message: String, type: DungeonMessageType = DungeonMessageType.NORMAL): Text {
    val colorCode = if (type == DungeonMessageType.ERROR) "§c" else "§e"
    return Text.literal("$PREFIX$colorCode$message")
}

fun ServerPlayerEntity.sendDungeonMessage(
    message: String,
    type: DungeonMessageType = DungeonMessageType.NORMAL
) {
    sendMessage(dungeonText(message, type), false)
}

fun ServerCommandSource.sendDungeonFeedback(
    message: String,
    type: DungeonMessageType = DungeonMessageType.NORMAL,
    broadcastToOps: Boolean = false
) {
    sendFeedback({ dungeonText(message, type) }, broadcastToOps)
}

fun ServerCommandSource.sendDungeonError(message: String) {
    sendFeedback({ dungeonText(message, DungeonMessageType.ERROR) }, false)
}
