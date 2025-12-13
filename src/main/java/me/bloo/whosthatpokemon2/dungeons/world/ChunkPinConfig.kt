package me.bloo.whosthatpokemon2.dungeons.world

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Standalone config for ChunkPin.
 * File: config/whosthatpokemon2/chunkpin.json
 */
data class ChunkPinConfig(
    /** If true, use ServerWorld.setChunkForced (ticking). If false, use non-ticking tickets. */
    val forceTickingChunks: Boolean = false
) {
    companion object {
        private val GSON = GsonBuilder().setPrettyPrinting().create()
        private val CONFIG_PATH: Path = Paths.get("config", "whosthatpokemon2", "chunkpin.json")

        @Volatile private var cached: ChunkPinConfig? = null

        fun get(): ChunkPinConfig {
            cached?.let { return it }
            return loadOrDefault().also { cached = it }
        }

        fun reload() { cached = null }

        private fun loadOrDefault(): ChunkPinConfig {
            return try {
                Files.createDirectories(CONFIG_PATH.parent)
                if (Files.notExists(CONFIG_PATH)) {
                    val def = ChunkPinConfig()
                    Files.writeString(CONFIG_PATH, GSON.toJson(def), StandardCharsets.UTF_8)
                    def
                } else {
                    val text = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)
                    GSON.fromJson(text, ChunkPinConfig::class.java) ?: ChunkPinConfig()
                }
            } catch (_: IOException) {
                ChunkPinConfig()
            } catch (_: JsonSyntaxException) {
                val def = ChunkPinConfig()
                try { Files.writeString(CONFIG_PATH, GSON.toJson(def), StandardCharsets.UTF_8) } catch (_: IOException) {}
                def
            }
        }
    }
}
