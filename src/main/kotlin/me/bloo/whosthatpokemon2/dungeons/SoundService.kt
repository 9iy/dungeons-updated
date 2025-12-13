package me.bloo.whosthatpokemon2.dungeons

import net.minecraft.registry.Registries
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket
import java.util.Locale

/** Quick helpers to blast vanilla sounds straight to specific players. */
object SoundService {
    private const val UI_SOUND_LABEL = "uiSound"

    fun playLocal(
        player: ServerPlayerEntity,
        event: SoundEvent,
        category: SoundCategory = SoundCategory.PLAYERS,
        volume: Float = 1.0f,
        pitch: Float = 1.0f,
        logLabel: String? = null
    ) {
        val soundEntry = Registries.SOUND_EVENT.getEntry(event)
        if (soundEntry != null) {
            player.networkHandler.sendPacket(
                PlaySoundS2CPacket(
                    soundEntry,
                    category,
                    player.x,
                    player.y,
                    player.z,
                    volume,
                    pitch,
                    player.world.random.nextLong()
                )
            )
        } else {
            player.playSound(event, volume, pitch)
        }
        logLabel?.let { label ->
            val id = Registries.SOUND_EVENT.getId(event)?.toString() ?: "unknown"
            println(
                "[Dungeons] $label to=${player.gameProfile.name} sound=$id " +
                    "cat=${category.name.lowercase(Locale.ROOT)} vol=${volume.format2()} pitch=${pitch.format2()} side=server"
            )
        }
    }

    fun playLocal(
        player: ServerPlayerEntity,
        event: RegistryEntry<SoundEvent>,
        category: SoundCategory = SoundCategory.PLAYERS,
        volume: Float = 1.0f,
        pitch: Float = 1.0f,
        logLabel: String? = null
    ) {
        playLocal(player, event.value(), category, volume, pitch, logLabel)
    }

    fun playUiSound(
        player: ServerPlayerEntity,
        event: SoundEvent,
        volume: Float = 1.0f,
        pitch: Float = 1.0f
    ) {
        playLocal(player, event, SoundCategory.MASTER, volume, pitch, UI_SOUND_LABEL)
    }

    fun playUiSound(
        player: ServerPlayerEntity,
        event: RegistryEntry<SoundEvent>,
        volume: Float = 1.0f,
        pitch: Float = 1.0f
    ) {
        playLocal(player, event, SoundCategory.MASTER, volume, pitch, UI_SOUND_LABEL)
    }

    fun playToPlayers(
        players: Collection<ServerPlayerEntity>,
        event: SoundEvent,
        category: SoundCategory = SoundCategory.PLAYERS,
        volume: Float = 1.0f,
        pitch: Float = 1.0f
    ) {
        players.forEach { playLocal(it, event, category, volume, pitch, null) }
    }

    fun playToPlayers(
        players: Collection<ServerPlayerEntity>,
        event: RegistryEntry<SoundEvent>,
        category: SoundCategory = SoundCategory.PLAYERS,
        volume: Float = 1.0f,
        pitch: Float = 1.0f
    ) {
        players.forEach { playLocal(it, event, category, volume, pitch, null) }
    }

    private fun Float.format2(): String = String.format(Locale.ROOT, "%.2f", this)
}
