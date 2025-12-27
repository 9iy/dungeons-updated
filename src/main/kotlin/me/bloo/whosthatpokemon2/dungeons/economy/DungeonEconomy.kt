package me.bloo.whosthatpokemon2.dungeons.economy

import me.bloo.whosthatpokemon2.dungeons.config.DungeonGameplayConfig
import me.bloo.whosthatpokemon2.dungeons.message.DungeonMessageType
import me.bloo.whosthatpokemon2.dungeons.message.sendDungeonMessage
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import java.util.UUID
import java.util.concurrent.CompletableFuture

object DungeonEconomy {
    private const val LOG_PREFIX = "[Dungeons][Economy]"

    data class ChargeResult(
        val success: Boolean,
        val message: String? = null,
        val chargedPlayers: List<UUID> = emptyList(),
        val usedCommands: Boolean = false,
        val currencyId: String? = null,
        val amount: Long = 0L,
        val failure: Failure? = null,
        val playerNames: Map<UUID, String> = emptyMap()
    ) {
        data class Failure(val playerId: UUID?, val message: String)
    }

    fun chargeForStart(
        server: MinecraftServer,
        players: List<ServerPlayerEntity>
    ): CompletableFuture<ChargeResult> {
        val config = DungeonGameplayConfig.instance()
        val economy = config.economy
        if (!economy.enabled || !economy.chargeOnStart || economy.entryFee <= 0) {
            return CompletableFuture.completedFuture(ChargeResult(success = true))
        }
        val normalizedCurrency = normalizeCurrencyId(economy.currency)
        val serviceAvailable = Economy.service() != null
        val currencyAvailable = Economy.currency(normalizedCurrency) != null
        val chargeAmount = economy.entryFee
        val names = players.associate { it.uuid to it.gameProfile.name }
        if (serviceAvailable && currencyAvailable) {
            return CompletableFuture.supplyAsync({
                val charged = mutableListOf<UUID>()
                for (player in players) {
                    val uuid = player.uuid
                    val withdrew = EconomyHooks.tryWithdraw(uuid, normalizedCurrency, chargeAmount).join()
                    if (!withdrew) {
                        // double-check if missing funds was the real blocker.
                        val hasFunds = EconomyHooks.hasBalance(uuid, normalizedCurrency, chargeAmount).join()
                        if (charged.isNotEmpty()) {
                            charged.forEach { refunded ->
                                EconomyHooks.tryDeposit(refunded, normalizedCurrency, chargeAmount).join()
                            }
                        }
                        val name = names[uuid] ?: uuid.toString()
                        val failureMessage = if (!hasFunds) {
                            "You need $chargeAmount ${economy.currency} to start this dungeon."
                        } else {
                            "Failed to charge $name for the dungeon entry fee."
                        }
                        return@supplyAsync ChargeResult(
                            success = false,
                            message = failureMessage,
                            chargedPlayers = charged.toList(),
                            usedCommands = false,
                            currencyId = normalizedCurrency,
                            amount = chargeAmount,
                            failure = ChargeResult.Failure(uuid, failureMessage),
                            playerNames = names
                        )
                    }
                    charged.add(uuid)
                }
                ChargeResult(
                    success = true,
                    chargedPlayers = charged.toList(),
                    usedCommands = false,
                    currencyId = normalizedCurrency,
                    amount = chargeAmount,
                    playerNames = names
                )
            }, EconomyHooks.executor).whenComplete { result, error ->
                if (error != null) {
                    println("$LOG_PREFIX Failed to process entry fee via API: ${error.message}")
                } else if (result != null && !result.success) {
                    println("$LOG_PREFIX Entry fee processing aborted: ${result.message}")
                }
            }
        }

        if (!economy.useCommandFallback) {
            val reason = if (!serviceAvailable) "Economy service unavailable" else "Currency $normalizedCurrency not found"
            println("$LOG_PREFIX $reason and command fallback disabled; aborting start.")
            return CompletableFuture.completedFuture(
                ChargeResult(
                    success = false,
                    message = "Dungeon economy unavailable: $reason",
                    usedCommands = false,
                    currencyId = normalizedCurrency,
                    amount = chargeAmount,
                    failure = ChargeResult.Failure(null, "Dungeon economy unavailable: $reason"),
                    playerNames = names
                )
            )
        }

        val template = config.economyFallback.chargeCmd
        if (template.isBlank()) {
            println("$LOG_PREFIX Command fallback enabled but no charge command configured.")
            return CompletableFuture.completedFuture(
                ChargeResult(
                    success = false,
                    message = "Economy fallback command missing.",
                    usedCommands = true,
                    currencyId = normalizedCurrency,
                    amount = chargeAmount,
                    failure = ChargeResult.Failure(null, "Economy fallback command missing."),
                    playerNames = names
                )
            )
        }

        players.forEach { player ->
            val name = player.gameProfile.name
            val command = template.replace("{player}", name).replace("%player%", name)
            server.execute {
                server.commandManager.executeWithPrefix(server.commandSource, command)
            }
        }
        println("$LOG_PREFIX Charged entry fee using command fallback for ${players.size} players.")
        return CompletableFuture.completedFuture(
            ChargeResult(
                success = true,
                chargedPlayers = players.map { it.uuid },
                usedCommands = true,
                currencyId = normalizedCurrency,
                amount = chargeAmount,
                playerNames = names
            )
        )
    }

    fun refundStart(
        server: MinecraftServer,
        charge: ChargeResult,
        notifyPlayers: Boolean = true
    ): CompletableFuture<Void> {
        if (!charge.success || charge.chargedPlayers.isEmpty()) {
            return CompletableFuture.completedFuture(null)
        }
        val config = DungeonGameplayConfig.instance()
        val economy = config.economy
        if (!economy.enabled || !economy.refundOnFailStart) {
            return CompletableFuture.completedFuture(null)
        }
        if (charge.usedCommands) {
            val template = config.economyFallback.refundCmd
            if (template.isBlank()) {
                println("$LOG_PREFIX Refund requested but no fallback refund command configured.")
                return CompletableFuture.completedFuture(null)
            }
            charge.chargedPlayers.forEach { uuid ->
                val player = server.playerManager.getPlayer(uuid)
                val name = player?.gameProfile?.name ?: charge.playerNames[uuid]
                if (name.isNullOrBlank()) {
                    println("$LOG_PREFIX Skipping refund command for $uuid due to missing player name.")
                    return@forEach
                }
                val command = template.replace("{player}", name).replace("%player%", name)
                server.execute {
                    server.commandManager.executeWithPrefix(server.commandSource, command)
                    if (notifyPlayers && player != null) {
                        player.sendDungeonMessage(
                            "Entry fee refunded due to setup error.",
                            DungeonMessageType.ERROR
                        )
                    }
                }
            }
            println("$LOG_PREFIX Refunded entry fee using command fallback for ${charge.chargedPlayers.size} players.")
            return CompletableFuture.completedFuture(null)
        }
        val currencyId = charge.currencyId ?: return CompletableFuture.completedFuture(null)
        val amount = charge.amount
        return CompletableFuture.runAsync({
            charge.chargedPlayers.forEach { uuid ->
                EconomyHooks.tryDeposit(uuid, currencyId, amount).join()
            }
        }, EconomyHooks.executor).whenComplete { _, error ->
            if (error != null) {
                println("$LOG_PREFIX Failed to refund entry fee via API: ${error.message}")
            } else if (notifyPlayers) {
                charge.chargedPlayers.forEach { uuid ->
                    val player = server.playerManager.getPlayer(uuid) ?: return@forEach
                    server.execute {
                        player.sendDungeonMessage(
                            "Entry fee refunded due to setup error.",
                            DungeonMessageType.ERROR
                        )
                    }
                }
            }
        }
    }

    fun rewardOnClear(server: MinecraftServer, player: ServerPlayerEntity) {
        val config = DungeonGameplayConfig.instance()
        val economy = config.economy
        if (!economy.enabled || economy.rewardOnClear <= 0) {
            return
        }
        val normalizedCurrency = normalizeCurrencyId(economy.currency)
        val serviceAvailable = Economy.service() != null
        val currencyAvailable = Economy.currency(normalizedCurrency) != null
        val rewardAmount = economy.rewardOnClear
        if (serviceAvailable && currencyAvailable) {
            CompletableFuture.supplyAsync({
                EconomyHooks.tryDeposit(player.uuid, normalizedCurrency, rewardAmount).join()
            }, EconomyHooks.executor).thenAccept { success ->
                if (success) {
                    server.execute {
                        player.sendDungeonMessage("You received $rewardAmount ${economy.currency}!")
                    }
                } else {
                    println("$LOG_PREFIX Failed to reward ${player.gameProfile.name} via API.")
                }
            }
            return
        }
        if (!economy.useCommandFallback) {
            println("$LOG_PREFIX Unable to reward ${player.gameProfile.name}; economy unavailable and fallback disabled.")
            return
        }
        val template = config.economyFallback.rewardCmd
        if (template.isBlank()) {
            println("$LOG_PREFIX Reward command fallback missing; unable to reward ${player.gameProfile.name}.")
            return
        }
        val playerName = player.gameProfile.name
        val line = template.replace("{player}", playerName).replace("%player%", playerName)
        server.execute {
            server.commandManager.executeWithPrefix(server.commandSource, line)
            player.sendDungeonMessage("You received $rewardAmount ${economy.currency}!")
        }
    }
}
