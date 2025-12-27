package me.bloo.whosthatpokemon2.dungeons

import me.bloo.whosthatpokemon2.dungeons.economy.DungeonPasses
import me.bloo.whosthatpokemon2.dungeons.config.DungeonGameplayConfig
import java.time.Duration
import java.time.Instant
import java.util.UUID

sealed class StartPath {
    data object TimerOk : StartPath()
    data object UsingPass : StartPath()
    data class Blocked(val reason: String) : StartPath()
}

data class CommitResult(
    val success: Boolean,
    val timersStarted: List<UUID> = emptyList(),
    val passesConsumed: List<UUID> = emptyList(),
    val error: String? = null
)

data class StartEvaluation(
    val paths: Map<UUID, StartPath>
)

object DungeonStartGate {
    private const val LOG_PREFIX = "[Dungeons][StartGate]"
    private val debounceDuration: Duration
        get() = DungeonGameplayConfig.instance().cooldowns.duration()

    fun evaluatePartyStartPaths(
        party: PartyService.Party,
        now: Instant
    ): StartEvaluation {
        val results = mutableMapOf<UUID, StartPath>()
        val members = party.membersOrdered()
        for (uuid in members) {
            val running = PlayerCooldownStore.isDungeonTimerRunning(uuid, now)
            val remaining = PlayerCooldownStore.getDungeonTimerRemaining(uuid, now)
            println("$LOG_PREFIX [DEBUG] Evaluating ${label(uuid)} timerRunning=$running remaining=${formatDuration(remaining)}")
            val path = when {
                !running -> StartPath.TimerOk
                DungeonPasses.canBypassCooldown(uuid) -> StartPath.UsingPass
                else -> StartPath.Blocked("On cooldown; please wait for your timer to expire.")
            }
            results[uuid] = path
        }

        return StartEvaluation(results)
    }

    fun commitStart(
        party: PartyService.Party,
        paths: Map<UUID, StartPath>,
        now: Instant
    ): CommitResult {
        val server = PartyService.server ?: return CommitResult(success = false, error = "Server unavailable.")
        val members = party.membersOrdered()
        val offline = members.firstOrNull { server.playerManager.getPlayer(it) == null }
        if (offline != null) {
            val name = label(offline)
            return CommitResult(success = false, error = "Player $name went offline; try again.")
        }

        val timerPlayers = members.filter { paths[it] is StartPath.TimerOk }
        val passPlayers = members.filter { paths[it] is StartPath.UsingPass }
        val consumedPasses = mutableListOf<UUID>()

        if (passPlayers.isNotEmpty()) {
            val failed = mutableListOf<UUID>()
            for (uuid in passPlayers) {
                val consumed = DungeonPasses.consumePass(uuid)
                if (!consumed) {
                    failed += uuid
                } else {
                    consumedPasses += uuid
                }
            }
            if (failed.isNotEmpty()) {
                val names = formatNameList(failed)
                return CommitResult(
                    success = false,
                    error = "Failed to use dungeon pass for: $names."
                )
            }
        }

        val duration = debounceDuration
        val startedTimers = mutableListOf<UUID>()

        if (!duration.isZero && !duration.isNegative) {
            for (uuid in timerPlayers) {
                PlayerCooldownStore.startDungeonTimer(uuid, now, duration)
                startedTimers.add(uuid)
            }
        }
        println("$LOG_PREFIX [DEBUG] Timers started=${startedTimers.size}")
        return CommitResult(
            success = true,
            timersStarted = startedTimers,
            passesConsumed = consumedPasses
        )
    }

    fun rollbackStart(result: CommitResult, reason: String) {
        if (!result.success) return
        println("$LOG_PREFIX [WARN] Rolling back dungeon start commit: $reason")
        rollbackTimers(result.timersStarted)
        refundPasses(result.passesConsumed)
    }

    fun formatBlockedReasons(party: PartyService.Party, blocked: Map<UUID, StartPath.Blocked>): String {
        return blocked.entries.joinToString("\n") { (uuid, path) ->
            "⛔ Player ${label(uuid)}: ${path.reason}"
        }
    }

    fun formatSuccessSummary(party: PartyService.Party, timers: List<UUID>): String {
        val timerNames = formatNameList(timers)
        return "Started dungeon. Timers started for: $timerNames."
    }

    private fun rollbackTimers(uuids: List<UUID>) {
        for (uuid in uuids) {
            PlayerCooldownStore.resetDungeonTimer(uuid)
        }
    }

    private fun refundPasses(uuids: List<UUID>) {
        for (uuid in uuids) {
            DungeonPasses.refundPass(uuid)
        }
    }

    private fun formatDuration(duration: Duration): String {
        if (duration.isZero || duration.isNegative) {
            return "00:00"
        }
        val hours = duration.toHours()
        val minutes = duration.minusHours(hours).toMinutes()
        return String.format("%02d:%02d", hours, minutes)
    }

    private fun formatNameList(uuids: List<UUID>): String {
        if (uuids.isEmpty()) return "None"
        return uuids.joinToString(", ") { label(it) }
    }

    private fun PartyService.Party.membersOrdered(): List<UUID> = this.order.toList()

    private fun label(uuid: UUID): String = PartyService.lookupName(uuid) ?: uuid.toString()
}
