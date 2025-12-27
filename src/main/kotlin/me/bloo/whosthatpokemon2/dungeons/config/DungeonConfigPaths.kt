package me.bloo.whosthatpokemon2.dungeons.config

import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Central spot for dungeon config paths, plus a quick legacy migration shim.
 */
object DungeonConfigPaths {
    private val configDir = FabricLoader.getInstance().configDir
    private val baseDir: Path = configDir.resolve("dungeons")

    fun ensureBaseDirectory() {
        try {
            Files.createDirectories(baseDir)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun resolve(fileName: String): Path {
        val target = baseDir.resolve(fileName)
        if (Files.exists(target)) {
            return target
        }

        val legacy = configDir.resolve(fileName)
        if (Files.exists(legacy)) {
            try {
                Files.createDirectories(baseDir)
                Files.move(legacy, target)
                return target
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                    Files.createDirectories(baseDir)
                    Files.copy(legacy, target, StandardCopyOption.REPLACE_EXISTING)
                    return target
                } catch (copyException: Exception) {
                    copyException.printStackTrace()
                    return legacy
                }
            }
        }

        try {
            Files.createDirectories(baseDir)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return target
    }
}
