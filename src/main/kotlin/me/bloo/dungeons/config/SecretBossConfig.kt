package me.bloo.dungeons.config

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.util.concurrent.ThreadLocalRandom

/**
 * Load secret boss definitions so secret rooms know who spawns in.
 */
data class SecretBossEntry(
    val type: String = "ice",
    val pokemon: String = "articuno",
    val size: Double = 1.5
)

data class SecretBossConfig(
    val bosses: List<SecretBossEntry> = listOf(SecretBossEntry())
)

object SecretBossConfigManager {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path = DungeonConfigPaths.resolve("secret_bosses.json")

    @Volatile
    private var config: SecretBossConfig = load() ?: SecretBossConfig()

    private fun load(createIfMissing: Boolean = true): SecretBossConfig? {
        return try {
            if (Files.exists(path)) {
                Files.newBufferedReader(path).use { reader ->
                    gson.fromJson(reader, SecretBossConfig::class.java) ?: SecretBossConfig()
                }
            } else if (createIfMissing) {
                val fallback = SecretBossConfig()
                Files.createDirectories(path.parent)
                Files.newBufferedWriter(path).use { writer -> gson.toJson(fallback, writer) }
                fallback
            } else {
                null
            }
        } catch (error: Exception) {
            error.printStackTrace()
            null
        }
    }

    fun reload() {
        load(createIfMissing = false)?.let { config = it }
    }

    fun randomEntryForType(type: String): SecretBossEntry? {
        val matching = config.bosses.filter { it.type.equals(type, ignoreCase = true) }
            .takeIf { it.isNotEmpty() }
            ?: SecretBossConfig().bosses.filter { it.type.equals(type, ignoreCase = true) }
                .takeIf { it.isNotEmpty() }
        val bosses = matching ?: return null
        val index = ThreadLocalRandom.current().nextInt(bosses.size)
        return bosses[index]
    }

    fun all(): List<SecretBossEntry> = config.bosses
}
