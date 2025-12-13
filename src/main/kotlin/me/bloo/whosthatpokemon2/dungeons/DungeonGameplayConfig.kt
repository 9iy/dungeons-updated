package me.bloo.whosthatpokemon2.dungeons

import com.google.gson.GsonBuilder
import me.bloo.whosthatpokemon2.dungeons.economy.normalizeCurrencyId
import java.nio.file.Files
import java.time.Duration
import kotlin.math.max

/**
 * Lightweight gameplay config covering cooldowns, economy hooks, and other meta knobs.
 */
data class DungeonGameplayConfig(
    val cooldowns: CooldownSettings = CooldownSettings(),
    val economy: EconomySettings = EconomySettings(),
    val economyFallback: EconomyFallbackSettings = EconomyFallbackSettings(),
    val idle: IdleSettings = IdleSettings(),
    val fire: FireSettings = FireSettings()
) {
    data class CooldownSettings(
        val dungeonStartHours: Int = 24
    ) {
        fun duration(): Duration {
            val clamped = max(0, dungeonStartHours)
            return if (clamped == 0) Duration.ZERO else Duration.ofHours(clamped.toLong())
        }
    }

    data class EconomySettings(
        val enabled: Boolean = false,
        val currency: String = "event_points",
        val entryFee: Long = 0,
        val chargeOnStart: Boolean = false,
        val refundOnFailStart: Boolean = true,
        val rewardOnClear: Long = 0,
        val useCommandFallback: Boolean = false
    ) {
        fun normalizedCurrency(): String = normalizeCurrencyId(currency)
    }

    data class EconomyFallbackSettings(
        val chargeCmd: String = "",
        val refundCmd: String = "",
        val rewardCmd: String = ""
    )

    data class IdleSettings(
        val terminateAfterMinutes: Int = 5
    ) {
        fun terminationTicks(): Long {
            val minutes = max(0, terminateAfterMinutes)
            if (minutes == 0) return 0
            return minutes.toLong() * 60L * 20L
        }
    }

    data class FireSettings(
        val flameShieldChestChance: Double = 0.02
    )

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()
        private val path = DungeonConfigPaths.resolve("dungeon_gameplay.json")

        @Volatile
        private var config: DungeonGameplayConfig = load() ?: default()

        private fun default(): DungeonGameplayConfig = DungeonGameplayConfig()

        private fun load(createIfMissing: Boolean = true): DungeonGameplayConfig? {
            return try {
                if (Files.exists(path)) {
                    Files.newBufferedReader(path).use { reader ->
                        gson.fromJson(reader, DungeonGameplayConfig::class.java) ?: default()
                    }
                } else if (createIfMissing) {
                    val cfg = default()
                    Files.createDirectories(path.parent)
                    Files.newBufferedWriter(path).use { writer -> gson.toJson(cfg, writer) }
                    cfg
                } else {
                    null
                }
            } catch (error: Exception) {
                error.printStackTrace()
                null
            }
        }

        fun instance(): DungeonGameplayConfig = config

        fun reload() {
            load(createIfMissing = false)?.let { config = it }
        }

        fun save(updated: DungeonGameplayConfig) {
            try {
                Files.createDirectories(path.parent)
                Files.newBufferedWriter(path).use { writer -> gson.toJson(updated, writer) }
                config = updated
            } catch (error: Exception) {
                error.printStackTrace()
            }
        }
    }
}
