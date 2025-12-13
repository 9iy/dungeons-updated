package me.bloo.whosthatpokemon2.dungeons

import net.minecraft.util.Identifier

/**
 * Shared logging helpers so dungeon scan failures shout with context.
 */
object DungeonScanLogger {
    private const val LOG_PREFIX = "[Dungeons][ScanError]"

    fun logError(context: String, dungeon: Dungeon, error: DungeonScanner.ScanResult.Error) {
        val expected = formatExpectedBlocks(error.expectedBlocks)
        val found = formatFoundCounts(error)
        val chunkInfo = if (error.missingChunks.isEmpty()) {
            ""
        } else {
            " missingChunks=" + error.missingChunks.joinToString { "(${it.first},${it.second})" }
        }
        val origin = "${error.origin.x},${error.origin.y},${error.origin.z}"
        val sizeLabel = "${error.bounds.max.x - error.bounds.min.x + 1}x" +
            "${error.bounds.max.y - error.bounds.min.y + 1}x" +
            "${error.bounds.max.z - error.bounds.min.z + 1}"
        val typeLabel = dungeon.type.ifEmpty { "(unset)" }

        println(
            "$LOG_PREFIX context=$context reason=${error.reason} dungeon=${dungeon.name} type=$typeLabel " +
                "world=${error.worldKey} origin=$origin bounds=${error.bounds} size=$sizeLabel " +
                "expected=$expected found=$found details=${error.details}$chunkInfo"
        )
    }

    private fun formatExpectedBlocks(expected: Map<String, Identifier>): String {
        if (expected.isEmpty()) return "[]"
        return expected.entries
            .sortedBy { it.key }
            .joinToString(prefix = "[", postfix = "]") { (key, id) -> "$key=$id" }
    }

    private fun formatFoundCounts(error: DungeonScanner.ScanResult.Error): String {
        if (error.expectedBlocks.isEmpty()) {
            if (error.foundCounts.isEmpty()) return "[]"
            return error.foundCounts.entries
                .sortedBy { it.key.toString() }
                .joinToString(prefix = "[", postfix = "]") { (id, count) -> "$id=$count" }
        }
        val parts = error.expectedBlocks.entries
            .sortedBy { it.key }
            .map { (key, id) -> "$key($id)=${error.foundCounts[id] ?: 0}" }
        return parts.joinToString(prefix = "[", postfix = "]")
    }
}
