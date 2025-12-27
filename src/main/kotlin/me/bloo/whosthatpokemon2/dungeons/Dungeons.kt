package me.bloo.whosthatpokemon2.dungeons

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.ActionResult
import net.minecraft.util.TypedActionResult
import me.bloo.dungeons.hookshot.HookshotServer
import me.bloo.whosthatpokemon2.dungeons.command.DungeonCommands
import me.bloo.whosthatpokemon2.dungeons.config.DungeonConfigPaths
import me.bloo.whosthatpokemon2.dungeons.config.DungeonGameplayConfig
import me.bloo.whosthatpokemon2.dungeons.economy.Economy
import me.bloo.whosthatpokemon2.dungeons.world.ChunkPin
import java.util.UUID

class Dungeons : ModInitializer {
    override fun onInitialize() {
        CommandRegistrationCallback.EVENT.register { dispatcher, registryAccess, _ ->
            DungeonCommands.register(dispatcher, registryAccess)
        }
        ServerLifecycleEvents.SERVER_STARTED.register {
            DungeonConfigPaths.ensureBaseDirectory()
            DungeonGameplayConfig.instance()
            val economyConfig = DungeonGameplayConfig.instance().economy
            if (economyConfig.enabled) {
                val currencyId = economyConfig.normalizedCurrency()
                val service = Economy.service()
                if (service == null) {
                    println("[Dungeons] Economy service unavailable; command fallbacks will be used if configured.")
                } else {
                    val currency = Economy.currency(currencyId)
                    if (currency == null) {
                        println("[Dungeons] Economy currency '$currencyId' not found; command fallbacks will be used if configured.")
                    } else {
                        println("[Dungeons] Economy ready with currency $currencyId")
                    }
                }
            }
            PartyService.server = it
            PlayerCooldownStore.onServerStarted(it)
            DungeonStatsStore.onServerStarted(it)
            resetDungeonsOnStart(it)
            println("Dungeons loaded.")
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            server.worlds.forEach { world -> ChunkPin.releaseAllForWorld(world) }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { server ->
            server.worlds.forEach { world -> ChunkPin.releaseAllForWorld(world) }
            PartyService.server = null
            PlayerCooldownStore.onServerStopped()
            DungeonStatsStore.onServerStopped()
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> PartyService.onLogin(handler.player) }
        ServerPlayConnectionEvents.DISCONNECT.register { h, _ ->
            PartyService.onDisconnect(h.player)
            DungeonRuntime.onDisconnect(h.player)
        }
        ServerTickEvents.END_WORLD_TICK.register { world -> DungeonRuntime.onTick(world) }
        ServerPlayerEvents.AFTER_RESPAWN.register { oldPlayer, newPlayer, _ ->
            DungeonRuntime.onRespawn(oldPlayer, newPlayer)
        }
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, _ ->
            if (entity is ServerPlayerEntity) {
                DungeonRuntime.onPlayerDeath(entity)
            }
        }
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, _ ->
            if (entity is ServerPlayerEntity) {
                DungeonRuntime.onBeforePlayerDamaged(entity, source)
            }
            true
        }
        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, _, _ ->
            if (entity is ServerPlayerEntity) {
                DungeonRuntime.onPlayerDamaged(entity, source)
            }
        }
        UseBlockCallback.EVENT.register(UseBlockCallback { player, world, hand, hitResult ->
            if (world.isClient) return@UseBlockCallback ActionResult.PASS
            val serverPlayer = player as? ServerPlayerEntity ?: return@UseBlockCallback ActionResult.PASS
            val serverWorld = world as? ServerWorld ?: return@UseBlockCallback ActionResult.PASS

            val doorResult = DungeonRuntime.handleRaidDoorInteraction(
                player = serverPlayer,
                world = serverWorld,
                hand = hand,
                pos = hitResult.blockPos
            )
            if (doorResult != ActionResult.PASS) {
                return@UseBlockCallback doorResult
            }

            DungeonRuntime.handleGildedChestInteraction(serverPlayer, serverWorld, hitResult.blockPos)
        })
        UseItemCallback.EVENT.register(UseItemCallback { player, world, hand ->
            val stack = player.getStackInHand(hand)
            if (world.isClient) return@UseItemCallback TypedActionResult.pass(stack)
            val serverPlayer = player as? ServerPlayerEntity ?: return@UseItemCallback TypedActionResult.pass(stack)
            val serverWorld = world as? ServerWorld ?: return@UseItemCallback TypedActionResult.pass(stack)
            return@UseItemCallback when (DungeonRuntime.handleItemUse(serverPlayer, serverWorld, hand)) {
                ActionResult.SUCCESS -> TypedActionResult.success(player.getStackInHand(hand))
                ActionResult.FAIL -> TypedActionResult.fail(player.getStackInHand(hand))
                ActionResult.CONSUME -> TypedActionResult.consume(player.getStackInHand(hand))
                else -> TypedActionResult.pass(player.getStackInHand(hand))
            }
        })
        UseEntityCallback.EVENT.register(UseEntityCallback { player, world, hand, entity, _ ->
            if (world.isClient) return@UseEntityCallback ActionResult.PASS
            val serverPlayer = player as? ServerPlayerEntity ?: return@UseEntityCallback ActionResult.PASS
            val serverWorld = world as? ServerWorld ?: return@UseEntityCallback ActionResult.PASS
            val target = entity as? ServerPlayerEntity ?: return@UseEntityCallback ActionResult.PASS
            DungeonRuntime.handleEntityInteraction(serverPlayer, target, serverWorld, hand)
        })
        HookshotServer.init()
        CobblemonFlameBodyIntegration.init()
    }

    private fun resetDungeonsOnStart(server: MinecraftServer) {
        DungeonManager.list().forEach { dungeon ->
            DungeonRuntime.endSession(dungeon.name)
            if (dungeon.taken) {
                DungeonManager.markTaken(dungeon.name, false)
            }

            val world = DungeonWorldResolver.resolve(server, dungeon, server.overworld)
            if (world == null) {
                println("[Dungeons] Skipping reset for '${dungeon.name}': world '${dungeon.dimension ?: "unspecified"}' could not be resolved.")
                return@forEach
            }

            val corner1 = dungeon.corner1?.toBlockPos()
            val corner2 = dungeon.corner2?.toBlockPos()
            if (corner1 == null || corner2 == null) {
                println("[Dungeons] Skipping reset for '${dungeon.name}': dungeon corners are not defined.")
                return@forEach
            }

            val pinId = UUID.randomUUID()
            ChunkPin.pinArea(world, corner1, corner2, pinId)
            try {
                DungeonRuntime.restoreDungeonActivatorBlocks(world, dungeon)
                DungeonRuntime.clearDungeonDoorBlocks(world, dungeon)
                println("[Dungeons] Reset dungeon '${dungeon.name}' on startup.")
            } catch (ex: Exception) {
                println("[Dungeons] Failed to reset dungeon '${dungeon.name}' on startup: ${ex.message ?: ex::class.simpleName}.")
                ex.printStackTrace()
            } finally {
                ChunkPin.releaseAll(world, pinId)
            }
        }
    }
}
