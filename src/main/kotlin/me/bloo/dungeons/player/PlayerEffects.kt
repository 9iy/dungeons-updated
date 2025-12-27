package me.bloo.dungeons.player

import me.bloo.dungeons.sound.SoundService
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents

/** Utility helpers for blasting titles and sounds to dungeon participants. */
object PlayerEffects {
    fun sendTitle(
        player: ServerPlayerEntity,
        titleJson: String?,
        subtitleJson: String?,
        fadeIn: Int,
        stay: Int,
        fadeOut: Int
    ) {
        val server = player.server ?: return
        val source = server.commandSource
        val target = player.gameProfile.name
        server.commandManager.executeWithPrefix(source, "title $target times $fadeIn $stay $fadeOut")
        titleJson?.let { server.commandManager.executeWithPrefix(source, "title $target title $it") }
        subtitleJson?.let { server.commandManager.executeWithPrefix(source, "title $target subtitle $it") }
        println("[Dungeons] titleSent to=${player.gameProfile.name}")
        SoundService.playLocal(player, SoundEvents.UI_TOAST_IN, SoundCategory.MASTER)
    }
}

