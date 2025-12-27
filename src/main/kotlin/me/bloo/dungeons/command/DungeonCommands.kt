package me.bloo.dungeons.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.command.argument.BlockPosArgumentType
import net.minecraft.command.argument.EntityArgumentType
import net.minecraft.command.argument.IdentifierArgumentType
import net.minecraft.registry.Registries
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import me.bloo.dungeons.config.DungeonConfig
import me.bloo.dungeons.config.DungeonConfigPaths
import me.bloo.dungeons.config.DungeonGameplayConfig
import me.bloo.dungeons.config.DungeonLootConfig
import me.bloo.dungeons.config.DungeonTypeConfig
import me.bloo.dungeons.config.DungeonTypeDisplay
import me.bloo.dungeons.config.DungeonTypeDisplayConfig
import me.bloo.dungeons.config.GoodChestLootConfig
import me.bloo.dungeons.dungeon.manager.DungeonManager
import me.bloo.dungeons.dungeon.model.BlockPosDto
import me.bloo.dungeons.dungeon.model.Dungeon
import me.bloo.dungeons.dungeon.model.toBlockPos
import me.bloo.dungeons.dungeon.model.toDto
import me.bloo.dungeons.dungeon.runtime.DungeonRuntime
import me.bloo.dungeons.dungeon.scan.DungeonScanCache
import me.bloo.dungeons.dungeon.scan.DungeonScanLogger
import me.bloo.dungeons.dungeon.scan.DungeonScanner
import me.bloo.dungeons.dungeon.scan.DungeonWorldResolver
import me.bloo.dungeons.dungeon.secret.SecretRoomDefinition
import me.bloo.dungeons.dungeon.secret.SecretRoomManager
import me.bloo.dungeons.dungeon.world.ChunkPin
import me.bloo.dungeons.integration.hookshot.HookshotServer
import me.bloo.dungeons.message.DungeonMessageType
import me.bloo.dungeons.message.sendDungeonError
import me.bloo.dungeons.message.sendDungeonFeedback
import me.bloo.dungeons.message.sendDungeonMessage
import me.bloo.dungeons.party.PartyService
import me.bloo.dungeons.player.PlayerCooldownStore
import me.bloo.dungeons.player.hasPermission
import me.bloo.dungeons.ui.Guis

private const val ROOT_MENU_PERMISSION = "eclipse.command.dungeons"
private const val MENU_PERMISSION = "eclipse.command.dungeons.menu"

object DungeonCommands {
    fun register(
            dispatcher: CommandDispatcher<ServerCommandSource>,
            registryAccess: CommandRegistryAccess
        ) {
            val root = literal("dungeons")
                .executes {
                    val player = it.source.playerOrThrow
                    Guis.openMainMenu(player)
                    1
                }
                .then(
                    literal("open")
                        .requires(::hasMenuPermission)
                        .executes { val p = it.source.playerOrThrow; Guis.openMainMenu(p); 1 }
                )
                .then(
                    literal("party")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.party") }
                        .executes { val p = it.source.playerOrThrow; Guis.openPartyGui(p); 1 }
                        .then(literal("create")
                            .requires { source -> source.hasPermission("eclipse.command.dungeons.party.create") }
                            .executes {
                                val p = it.source.playerOrThrow
                                val res = PartyService.createParty(p, isPublic = false)
                                p.sendDungeonMessage(
                                    res.message,
                                    if (res.success) DungeonMessageType.NORMAL else DungeonMessageType.ERROR
                                )
                                Guis.openPartyGui(p); 1
                            }
                            .then(literal("public").requires { source -> source.hasPermission("eclipse.command.dungeons.party.create.public") }.executes {
                                val p = it.source.playerOrThrow
                                val res = PartyService.createParty(p, isPublic = true)
                                p.sendDungeonMessage(
                                    res.message,
                                    if (res.success) DungeonMessageType.NORMAL else DungeonMessageType.ERROR
                                )
                                Guis.openPartyGui(p); 1
                            })
                            .then(literal("private").requires { source -> source.hasPermission("eclipse.command.dungeons.party.create.private") }.executes {
                                val p = it.source.playerOrThrow
                                val res = PartyService.createParty(p, isPublic = false)
                                p.sendDungeonMessage(
                                    res.message,
                                    if (res.success) DungeonMessageType.NORMAL else DungeonMessageType.ERROR
                                )
                                Guis.openPartyGui(p); 1
                            })
                        )
                        .then(literal("disband").requires { source -> source.hasPermission("eclipse.command.dungeons.party.disband") }.executes {
                            val p = it.source.playerOrThrow
                            val res = PartyService.disbandCommand(p)
                            p.sendDungeonMessage(
                                res.message,
                                if (res.success) DungeonMessageType.NORMAL else DungeonMessageType.ERROR
                            ); 1
                        })
                        .then(literal("leave").requires { source -> source.hasPermission("eclipse.command.dungeons.party.leave") }.executes {
                            val p = it.source.playerOrThrow
                            val res = PartyService.leaveParty(p)
                            p.sendDungeonMessage(
                                res.message,
                                if (res.success) DungeonMessageType.NORMAL else DungeonMessageType.ERROR
                            )
                            1
                        })
                        .then(literal("invite").then(
                            argument("player", EntityArgumentType.player())
                                .requires { source -> source.hasPermission("eclipse.command.dungeons.party.invite") }
                                .executes { ctx ->
                                    val inviter = ctx.source.playerOrThrow
                                    val target = EntityArgumentType.getPlayer(ctx, "player")
                                    val res = PartyService.invite(inviter, target)
                                    inviter.sendDungeonMessage(
                                        res.message,
                                        if (res.success) DungeonMessageType.NORMAL else DungeonMessageType.ERROR
                                    )
                                    if (res.success) target.sendDungeonMessage(
                                        "You have a party invite from ${inviter.gameProfile.name}. Use /dungeon party invites."
                                    )
                                    1
                                }
                        ))
                        .then(literal("invites").requires { source -> source.hasPermission("eclipse.command.dungeons.party.invites") }.executes { val p = it.source.playerOrThrow; Guis.openInvitesGui(p); 1 })
                )
                .then(literal("list").requires { source -> source.hasPermission("eclipse.command.dungeons.list") }.executes { ctx ->
                    DungeonManager.list().forEach { d ->
                        ctx.source.sendDungeonFeedback(
                            "${d.name} (${d.type}) ${if (d.taken) "taken" else "free"}",
                            DungeonMessageType.NORMAL
                        )
                    }
                    1
                })
                .then(
                    literal("legendchance")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.legendchance") }
                        .then(literal("cleanup")
                            .requires { source -> source.hasPermission("eclipse.command.dungeons.legendchance.cleanup") }
                            .executes { ctx ->
                                val removed = DungeonRuntime.cleanupLegendaryGuarantees(ctx.source.server)
                                ctx.source.sendDungeonFeedback(
                                    "Cleared $removed stale legendary guarantees.",
                                    DungeonMessageType.NORMAL,
                                    true
                                )
                                1
                            }
                        )
                        .then(argument("player", EntityArgumentType.player())
                            .requires { source -> source.hasPermission("eclipse.command.dungeons.legendchance.player") }
                            .executes { ctx ->
                                val target = EntityArgumentType.getPlayer(ctx, "player")
                                DungeonRuntime.grantLegendaryGuarantee(target.uuid)
                                val windowMinutes = DungeonConfig.instance.legendaryGuaranteeGraceMinutes
                                val windowText = if (windowMinutes > 0) {
                                    val unit = if (windowMinutes == 1L) "minute" else "minutes"
                                    "$windowMinutes $unit"
                                } else null
                                val expiryText = if (windowText != null) {
                                    "Guarantee expires after $windowText or when the player logs out."
                                } else {
                                    "Guarantee expires when the player logs out."
                                }
                                ctx.source.sendDungeonFeedback(
                                    "${target.gameProfile.name}'s next dungeon will be legendary. $expiryText"
                                )
                                val playerExpiry = if (windowText != null) {
                                    "Use it within $windowText or it will expire."
                                } else {
                                    "It will expire if you log out before using it."
                                }
                                target.sendDungeonMessage("Your next dungeon run will be legendary. $playerExpiry")
                                1
                            }
                        )
                )
                .then(
                    literal("give_fireshield")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.give_fireshield") }
                        .executes { ctx ->
                            val executor = ctx.source.playerOrThrow
                            DungeonRuntime.grantFlameShield(executor)
                            ctx.source.sendDungeonFeedback(
                                "Gave Flame Shield to ${executor.gameProfile.name}."
                            )
                            1
                        }
                        .then(
                            argument("player", EntityArgumentType.player())
                                .requires { source -> source.hasPermission("eclipse.command.dungeons.give_fireshield.player") }
                                .executes { ctx ->
                                    val target = EntityArgumentType.getPlayer(ctx, "player")
                                    DungeonRuntime.grantFlameShield(target)
                                    ctx.source.sendDungeonFeedback(
                                        "Gave Flame Shield to ${target.gameProfile.name}."
                                    )
                                    1
                                }
                        )
                )
                .then(
                    literal("give_hookshot")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.give_hookshot") }
                        .then(
                            argument("player", EntityArgumentType.player())
                                .requires { source -> source.hasPermission("eclipse.command.dungeons.give_hookshot.player") }
                                .executes { ctx ->
                                    val target = EntityArgumentType.getPlayer(ctx, "player")
                                    HookshotServer.giveHookshotTo(target)
                                    ctx.source.sendFeedback({
                                        Text.literal("Gave hookshot to ${target.gameProfile.name}.")
                                    }, false)
                                    1
                                }
                        )
                )
                .then(
                    literal("hookshot_cancel")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.hookshot_cancel") }
                        .executes { ctx ->
                            val executor = ctx.source.playerOrThrow
                            HookshotServer.forceCancel(executor)
                            ctx.source.sendFeedback({
                                Text.literal("Cancelled hookshot for ${executor.gameProfile.name}.")
                            }, false)
                            1
                        }
                        .then(
                            argument("player", EntityArgumentType.player())
                                .requires { source -> source.hasPermission("eclipse.command.dungeons.hookshot_cancel.player") }
                                .executes { ctx ->
                                    val target = EntityArgumentType.getPlayer(ctx, "player")
                                    HookshotServer.forceCancel(target)
                                    ctx.source.sendFeedback({
                                        Text.literal("Cancelled hookshot for ${target.gameProfile.name}.")
                                    }, false)
                                    1
                                }
                        )
                )
                .then(
                    literal("timerall")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.timerall") }
                        .executes { ctx ->
                            val players = ctx.source.server.playerManager.playerList
                            val cleared = PlayerCooldownStore.resetAll(players.map { player -> player.uuid })
                            ctx.source.sendDungeonFeedback("Reset dungeon timers for $cleared online players.")
                            return@executes 1
                        }
                )
                .then(
                    literal("timercheck")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.timercheck") }
                        .executes { ctx ->
                            val executor = ctx.source.entity as? ServerPlayerEntity
                            if (executor == null) {
                                ctx.source.sendDungeonError("The console must specify a player.")
                                return@executes 0
                            }
                            return@executes sendTimerStatus(ctx.source, executor)
                        }
                        .then(
                            argument("player", EntityArgumentType.player())
                                .requires { source -> source.hasPermission("eclipse.command.dungeons.timercheck.player") }
                                .executes { ctx ->
                                    val target = EntityArgumentType.getPlayer(ctx, "player")
                                    val executor = ctx.source.entity as? ServerPlayerEntity
                                    val isSelf = executor?.uuid == target.uuid
                                    if (!isSelf && executor != null && !hasTimerCheckOtherPermission(ctx.source)) {
                                        ctx.source.sendDungeonError("You do not have permission to check other players' dungeon timers.")
                                        return@executes 0
                                    }
                                    return@executes sendTimerStatus(ctx.source, target)
                                }
                        )
                )
                .then(literal("rescan")
                    .requires { source -> source.hasPermission("eclipse.command.dungeons.rescan") }
                    .then(argument("name", StringArgumentType.word())
                        .executes { ctx ->
                            val name = StringArgumentType.getString(ctx, "name")
                            val dungeon = DungeonManager.getDungeon(name)
                            if (dungeon == null) {
                                ctx.source.sendDungeonFeedback("Unknown dungeon '$name'.", DungeonMessageType.ERROR)
                                0
                            } else {
                                val world = ctx.source.world
                                DungeonScanCache.clear()
                                val scan = DungeonScanner.scan(world, dungeon)
                                if (scan is DungeonScanner.ScanResult.Success) {
                                    DungeonScanCache.scan(world, dungeon)
                                    val (activatorBlocks, gildedChests) = extractActivatorStorage(scan)
                                    DungeonManager.addDungeon(
                                        dungeon.copy(
                                            activatorBlocks = activatorBlocks,
                                            gildedChestPositions = gildedChests
                                        )
                                    )
                                    val activatorCount = activatorBlocks.values.sumOf { it.size }
                                    val chestCount = gildedChests.size
                                    ctx.source.sendDungeonFeedback(
                                        "Stored $activatorCount activator blocks and $chestCount gilded chest markers for '$name'."
                                    )
                                    ctx.source.sendDungeonFeedback("Dungeon '$name' rescanned.")
                                    1
                                } else {
                                    val error = scan as DungeonScanner.ScanResult.Error
                                    DungeonScanLogger.logError("rescan", dungeon, error)
                                    ctx.source.sendDungeonFeedback(
                                        "Rescan failed: ${error.playerMessage}",
                                        DungeonMessageType.ERROR
                                    )
                                    0
                                }
                            }
                        }
                    )
                )
                .then(
                    literal("secretroom")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.secretroom") }
                        .then(
                            argument("dungeonType", StringArgumentType.word())
                                .then(
                                    argument("pos1", BlockPosArgumentType.blockPos())
                                        .then(
                                            argument("pos2", BlockPosArgumentType.blockPos())
                                                .executes { ctx -> defineSecretRoom(ctx) }
                                        )
                                )
                        )
                )
                .then(
                    literal("progressready")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.progressready") }
                        .then(argument("player", EntityArgumentType.player())
                            .executes { ctx ->
                                val target = EntityArgumentType.getPlayer(ctx, "player")
                                val result = DungeonRuntime.progressReady(target)
                                ctx.source.sendFeedback({ result.message }, false)
                                if (result.success) 1 else 0
                            }
                        )
                )
                .then(
                    literal("activedungeons")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.activedungeons") }
                        .executes { ctx ->
                            val active = DungeonRuntime.listActiveDungeons()
                            ctx.source.sendDungeonFeedback("ACTIVE DUNGEONS")
                            if (active.isEmpty()) {
                                ctx.source.sendDungeonFeedback("None")
                            } else {
                                active.forEach { info ->
                                    val legendLabel = if (info.legendary) "Legendary" else "Not Legendary"
                                    val line = "${info.leader}, ${info.type}, ${info.name}, ${info.partySize}, $legendLabel"
                                    ctx.source.sendDungeonFeedback(line)
                                }
                            }
                            1
                        }
                )
                .then(literal("reload")
                    .requires { source -> source.hasPermission("eclipse.command.dungeons.reload") }
                    .executes { ctx -> reloadConfigs(ctx.source) }
                )
                .then(
                    literal("type")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.type") }
                        .then(buildTypeCommand("create", TypeCommandMode.CREATE))
                        .then(buildTypeCommand("edit", TypeCommandMode.EDIT))
                        .then(buildTypeCommand("modify", TypeCommandMode.EDIT))
                )
                .then(
                    literal("dcreate")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.dcreate") }
                        .then(argument("name", StringArgumentType.word())
                            .then(argument("type", StringArgumentType.string())
                                .suggests { _, builder -> suggestDungeonTypeNames(builder) }
                                .then(argument("corner1", BlockPosArgumentType.blockPos())
                                    .then(argument("corner2", BlockPosArgumentType.blockPos())
                                        .executes { ctx ->
                                            val name = StringArgumentType.getString(ctx, "name")
                                            val typeInput = StringArgumentType.getString(ctx, "type")
                                            val resolvedType = resolveDungeonTypeId(typeInput)
                                            if (resolvedType == null) {
                                                ctx.source.sendDungeonFeedback(
                                                    "Unknown dungeon type '$typeInput'.",
                                                    DungeonMessageType.ERROR
                                                )
                                                return@executes 0
                                            }
                                            val c1 = BlockPosArgumentType.getBlockPos(ctx, "corner1")
                                            val c2 = BlockPosArgumentType.getBlockPos(ctx, "corner2")
                                            val icon = DungeonTypeDisplayConfig.get(resolvedType)?.icon ?: "minecraft:stone"
                                            val commandWorld = ctx.source.world
                                            val dimension = commandWorld.registryKey.value.toString()
                                            val baseDungeon = Dungeon(
                                                name = name,
                                                type = resolvedType,
                                                corner1 = c1.toDto(),
                                                corner2 = c2.toDto(),
                                                block = icon,
                                                dimension = dimension
                                            )
                                            val scan = DungeonScanner.scan(commandWorld, baseDungeon)
                                            val (activatorBlocks, gildedChests) = if (scan is DungeonScanner.ScanResult.Success) {
                                                extractActivatorStorage(scan)
                                            } else {
                                                emptyMap<String, List<BlockPosDto>>() to emptyList()
                                            }
                                            val dungeonToSave = baseDungeon.copy(
                                                activatorBlocks = activatorBlocks,
                                                gildedChestPositions = gildedChests
                                            )
                                            DungeonManager.addDungeon(dungeonToSave)
                                            ctx.source.sendDungeonFeedback("Dungeon '$name' created.")
                                            if (scan is DungeonScanner.ScanResult.Success) {
                                                val activatorCount = activatorBlocks.values.sumOf { it.size }
                                                val chestCount = gildedChests.size
                                                ctx.source.sendDungeonFeedback(
                                                    "Recorded $activatorCount activator blocks and $chestCount gilded chest markers."
                                                )
                                            } else if (scan is DungeonScanner.ScanResult.Error) {
                                                DungeonScanLogger.logError("dcreate", baseDungeon, scan)
                                                ctx.source.sendDungeonFeedback(
                                                    "Warning: Failed to record activator blocks - ${scan.playerMessage}",
                                                    DungeonMessageType.ERROR
                                                )
                                            }
                                            1
                                        }
                                    )
                                )
                            )
                        )
                )
                .then(
                    literal("doors")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.doors") }
                        .then(literal("open").requires { source -> source.hasPermission("eclipse.command.dungeons.doors.open") }.executes { ctx ->
                            val p = ctx.source.playerOrThrow
                            if (DungeonRuntime.openDoors(p)) {
                                p.sendDungeonMessage("Doors opened.")
                                1
                            } else {
                                p.sendDungeonMessage("No doors to open.", DungeonMessageType.ERROR)
                                0
                            }
                        })
                )
                .then(
                    literal("end")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.end") }
                        .then(literal("set")
                            .requires { source -> source.hasPermission("eclipse.command.dungeons.end.set") }
                            .executes { ctx ->
                                val player = ctx.source.playerOrThrow
                                val world = player.serverWorld
                                val endWorldId = world.registryKey.value.toString()
                                val pos = player.blockPos
                                val updated = DungeonConfig.instance.copy(
                                    endWorld = endWorldId,
                                    endPos = BlockPosDto(pos.x, pos.y, pos.z)
                                )
                                DungeonConfig.save(updated)
                                ctx.source.sendDungeonFeedback(
                                    "Dungeon end destination set to ${pos.x}, ${pos.y}, ${pos.z} in $endWorldId."
                                )
                                1
                            }
                        )
                        .then(argument("player", EntityArgumentType.player())
                            .requires { source -> source.hasPermission("eclipse.command.dungeons.end.player") }
                            .executes { ctx ->
                                val target = EntityArgumentType.getPlayer(ctx, "player")
                                val result = PartyService.endDungeon(target)
                                val message = if (result.success && result.message == "Dungeon ended.") {
                                    "Dungeon ended for ${target.gameProfile.name}."
                                } else {
                                    result.message
                                }
                                ctx.source.sendDungeonFeedback(
                                    message,
                                    if (result.success) DungeonMessageType.NORMAL else DungeonMessageType.ERROR
                                )
                                if (!result.success) 0 else 1
                            }
                        )
                )
                .then(
                    literal("debug")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.debug") }
                        .then(argument("name", StringArgumentType.word())
                            .executes { ctx ->
                                val name = StringArgumentType.getString(ctx, "name")
                                val dungeon = DungeonManager.getDungeon(name)
                                if (dungeon == null) {
                                    ctx.source.sendDungeonFeedback("Unknown dungeon '$name'.", DungeonMessageType.ERROR)
                                    return@executes 0
                                }
                                val world = ctx.source.world
                                val scan = DungeonScanCache.scan(world, dungeon)
                                if (scan !is DungeonScanner.ScanResult.Success) {
                                    ctx.source.sendDungeonFeedback(
                                        "Scan failed: ${(scan as? DungeonScanner.ScanResult.Error)?.reason ?: "unknown"}.",
                                        DungeonMessageType.ERROR
                                    )
                                    return@executes 0
                                }
                                val rooms = DungeonRuntime.computeRooms(scan)
                                rooms.forEachIndexed { idx, room ->
                                    ctx.source.sendDungeonFeedback(
                                        "Room ${idx + 1} ${room.min.x},${room.min.y},${room.min.z} -> ${room.max.x},${room.max.y},${room.max.z}"
                                    )
                                    scan.actionBlocks.forEach { (marker, positions) ->
                                        positions.filter { pos ->
                                            pos.x in room.min.x..room.max.x &&
                                            pos.y in room.min.y..room.max.y &&
                                            pos.z in room.min.z..room.max.z
                                        }.forEach { pos ->
                                            ctx.source.sendDungeonFeedback(" - $marker @ ${pos.x},${pos.y},${pos.z}")
                                        }
                                    }
                                }
                                1
                            }
                        )
                )
                .then(
                    literal("debug_banner")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.debug") }
                        .executes { ctx ->
                            val player = ctx.source.playerOrThrow
                            val repaired = DungeonRuntime.repairBannerItems(player)
                            player.giveItemStack(DungeonRuntime.createDebugBannerStack())
                            ctx.source.sendDungeonFeedback(
                                "Gave a safe banner and repaired $repaired stacks for ${player.gameProfile.name}."
                            )
                            1
                        }
                )
                .then(
                    literal("reset")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.reset") }
                        .then(argument("name", StringArgumentType.word())
                            .executes { ctx ->
                                val name = StringArgumentType.getString(ctx, "name")
                                val dungeon = DungeonManager.getDungeon(name)
                                if (dungeon == null) {
                                    ctx.source.sendDungeonFeedback("Unknown dungeon '$name'.", DungeonMessageType.ERROR)
                                    return@executes 0
                                }
                                val world = DungeonWorldResolver.resolve(
                                    ctx.source.server,
                                    dungeon,
                                    ctx.source.world
                                )
                                if (world == null) {
                                    val dimensionLabel = dungeon.dimension ?: "unspecified dimension"
                                    ctx.source.sendDungeonFeedback(
                                        "Unable to resolve world '$dimensionLabel' for dungeon '$name'.",
                                        DungeonMessageType.ERROR
                                    )
                                    return@executes 0
                                }
                                val corner1 = dungeon.corner1?.toBlockPos()
                                val corner2 = dungeon.corner2?.toBlockPos()
                                val chunkPinId = if (corner1 != null && corner2 != null) {
                                    val id = UUID.randomUUID()
                                    ChunkPin.pinArea(world, corner1, corner2, id)
                                    id
                                } else null
    
                                try {
                                    DungeonRuntime.endSession(name)
                                    DungeonRuntime.restoreDungeonActivatorBlocks(world, dungeon)
                                    DungeonRuntime.clearDungeonDoorBlocks(world, dungeon)
                                    DungeonManager.markTaken(name, false)
                                    ctx.source.sendDungeonFeedback("Dungeon '$name' reset.")
                                    1
                                } finally {
                                    chunkPinId?.let { ChunkPin.releaseAll(world, it) }
                                }
                            }
                        )
                )
    
                .then(
                    literal("debuglegendarycatch")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.debuglegendarycatch") }
                        .executes { ctx ->
                            val player = ctx.source.playerOrThrow
                            val hasMasterBall = DungeonRuntime.playerHasMasterBall(player)
                            val message = if (hasMasterBall) {
                                "Legendary capture preview for Palkia: Ancient Poké Ball in slot 21, Master Ball in slot 23."
                            } else {
                                "Legendary capture preview for Palkia: Ancient Poké Ball in slot 22 only."
                            }
                            player.sendDungeonMessage(message)
                            1
                        }
                )
    
            val rootNode = dispatcher.register(root)
            dispatcher.register(literal("dungeon").redirect(rootNode))
        }
    
        private fun extractActivatorStorage(
            scan: DungeonScanner.ScanResult.Success
        ): Pair<Map<String, List<BlockPosDto>>, List<BlockPosDto>> {
            val config = DungeonConfig.instance
            val comparator = compareBy<BlockPosDto>({ it.x }, { it.y }, { it.z })
            val byBlockId = mutableMapOf<String, MutableSet<BlockPosDto>>()
            scan.actionBlocks.forEach { (key, positions) ->
                val blockId = config.actionBlocks[key] ?: return@forEach
                val target = byBlockId.getOrPut(blockId) { mutableSetOf() }
                positions.mapTo(target) { pos -> pos.toDto() }
            }
            val sorted = byBlockId.keys.sorted().associateWith { blockId ->
                byBlockId[blockId]?.sortedWith(comparator) ?: emptyList()
            }
            val gilded = scan.gildedChestMarkers
                .map { pos -> pos.toDto() }
                .sortedWith(comparator)
            return sorted to gilded
        }
    
        private fun defineSecretRoom(ctx: CommandContext<ServerCommandSource>): Int {
            val source = ctx.source
            val world = source.world
            val dungeonType = StringArgumentType.getString(ctx, "dungeonType")
            val pos1 = BlockPosArgumentType.getBlockPos(ctx, "pos1")
            val pos2 = BlockPosArgumentType.getBlockPos(ctx, "pos2")
            val minX = min(pos1.x, pos2.x)
            val maxX = max(pos1.x, pos2.x)
            val minY = min(pos1.y, pos2.y)
            val maxY = max(pos1.y, pos2.y)
            val minZ = min(pos1.z, pos2.z)
            val maxZ = max(pos1.z, pos2.z)
    
            val trapdoors = mutableListOf<BlockPos>()
            val analyzers = mutableListOf<BlockPos>()
            val blueTerracotta = mutableListOf<BlockPos>()
            val crafters = mutableListOf<BlockPos>()
            val mutable = BlockPos.Mutable()
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    for (z in minZ..maxZ) {
                        mutable.set(x, y, z)
                        val state = world.getBlockState(mutable)
                        if (state.isAir) continue
                        val id = Registries.BLOCK.getId(state.block)
                        when (id) {
                            Identifier.of("minecraft:waxed_oxidized_copper_trapdoor") -> trapdoors.add(mutable.toImmutable())
                            Identifier.of("cobblemon:fossil_analyzer") -> analyzers.add(mutable.toImmutable())
                            Identifier.of("minecraft:blue_glazed_terracotta") -> blueTerracotta.add(mutable.toImmutable())
                            Identifier.of("minecraft:crafter") -> crafters.add(mutable.toImmutable())
                        }
                    }
                }
            }
    
            if (trapdoors.isEmpty()) {
                source.sendDungeonFeedback("No waxed oxidized copper trapdoor found in region.", DungeonMessageType.ERROR)
                return 0
            }
            if (analyzers.size != 2) {
                source.sendDungeonFeedback(
                    "Secret room requires exactly two cobblemon:fossil_analyzer blocks.",
                    DungeonMessageType.ERROR
                )
                return 0
            }
            if (blueTerracotta.isEmpty()) {
                source.sendDungeonFeedback(
                    "Secret room boss spawn pads (blue glazed terracotta) missing.",
                    DungeonMessageType.ERROR
                )
                return 0
            }
            if (crafters.size != 1) {
                source.sendDungeonFeedback(
                    "Secret room must contain exactly one minecraft:crafter.",
                    DungeonMessageType.ERROR
                )
                return 0
            }
    
            val analyzerA = analyzers[0]
            val analyzerB = analyzers[1]
            if (analyzerA.y != analyzerB.y) {
                source.sendDungeonFeedback("Fossil analyzers must be placed at the same height.", DungeonMessageType.ERROR)
                return 0
            }
    
            val dx = abs(analyzerA.x - analyzerB.x)
            val dz = abs(analyzerA.z - analyzerB.z)
            val readyMin: BlockPos
            val readyMax: BlockPos
            val length = 14
            val width = 3
            val height = 4
            when {
                dx == length - 1 && dz == width - 1 -> {
                    val minX = min(analyzerA.x, analyzerB.x)
                    val minZ = min(analyzerA.z, analyzerB.z)
                    readyMin = BlockPos(minX, analyzerA.y, minZ)
                    readyMax = BlockPos(minX + length - 1, analyzerA.y + height - 1, minZ + width - 1)
                }
                dz == length - 1 && dx == width - 1 -> {
                    val minX = min(analyzerA.x, analyzerB.x)
                    val minZ = min(analyzerA.z, analyzerB.z)
                    readyMin = BlockPos(minX, analyzerA.y, minZ)
                    readyMax = BlockPos(minX + width - 1, analyzerA.y + height - 1, minZ + length - 1)
                }
                else -> {
                    source.sendDungeonFeedback(
                        "Fossil analyzers must mark opposite corners of a 14x3x4 area.",
                        DungeonMessageType.ERROR
                    )
                    return 0
                }
            }
    
            val withinBounds =
                readyMin.x in minX..maxX && readyMax.x in minX..maxX &&
                    readyMin.y in minY..maxY && readyMax.y in minY..maxY &&
                    readyMin.z in minZ..maxZ && readyMax.z in minZ..maxZ
            if (!withinBounds) {
                source.sendDungeonFeedback("Derived ready area extends outside the provided region.", DungeonMessageType.ERROR)
                return 0
            }
    
            val definition = SecretRoomDefinition(
                id = UUID.randomUUID().toString(),
                dungeonType = dungeonType,
                world = world.registryKey.value.toString(),
                corner1 = pos1.toDto(),
                corner2 = pos2.toDto(),
                trapdoors = trapdoors.map { it.toDto() },
                readyAreaMin = readyMin.toDto(),
                readyAreaMax = readyMax.toDto(),
                bossSpawn = crafters.first().toDto(),
                bossTeleportTargets = blueTerracotta.map { it.toDto() }
            )
    
            val saved = SecretRoomManager.add(definition)
            source.sendDungeonFeedback("Secret room '${saved.id}' stored for type '$dungeonType'.")
            println(
                "[Dungeons][SecretRoom] Stored room ${saved.id} for type=$dungeonType world=${saved.world} trapdoors=${trapdoors.size} ready=" +
                    "${saved.readyAreaMin}..${saved.readyAreaMax} bluePads=${blueTerracotta.size}"
            )
            return 1
        }
    
        private fun reloadConfigs(source: ServerCommandSource): Int {
            DungeonConfigPaths.ensureBaseDirectory()
            DungeonConfig.reload()
            DungeonTypeConfig.reload()
            DungeonTypeDisplayConfig.reload()
            DungeonLootConfig.reload()
            GoodChestLootConfig.reload()
            DungeonManager.reload()
            DungeonScanCache.clear()
            source.sendDungeonFeedback("Dungeon configs reloaded.", broadcastToOps = true)
            return 1
        }
    
        private fun buildTypeCommand(name: String, mode: TypeCommandMode): LiteralArgumentBuilder<ServerCommandSource> {
            val base = literal(name)
                .requires { source -> source.hasPermission("eclipse.command.dungeons.type.$name") }
            val nameArgument = argument("name", StringArgumentType.string())
                .then(argument("icon", IdentifierArgumentType.identifier())
                    .then(literal("enabled")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.type.$name.enabled") }
                        .then(argument("description", StringArgumentType.greedyString())
                            .executes { ctx -> handleTypeCommand(ctx, mode, true) }
                        )
                    )
                    .then(literal("disabled")
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.type.$name.disabled") }
                        .then(argument("description", StringArgumentType.greedyString())
                            .executes { ctx -> handleTypeCommand(ctx, mode, false) }
                        )
                    )
                    .then(argument("description", StringArgumentType.greedyString())
                        .requires { source -> source.hasPermission("eclipse.command.dungeons.type.$name.description") }
                        .executes { ctx -> handleTypeCommand(ctx, mode, null) }
                    )
                )
    
            return if (mode == TypeCommandMode.CREATE) {
                base.then(nameArgument)
            } else {
                base.then(
                    argument("type", StringArgumentType.word())
                        .then(nameArgument)
                )
            }
        }
    
        private fun handleTypeCommand(
            ctx: CommandContext<ServerCommandSource>,
            mode: TypeCommandMode,
            enabledOverride: Boolean?
        ): Int {
            val displayName = StringArgumentType.getString(ctx, "name")
            val typeId = if (mode == TypeCommandMode.CREATE) {
                deriveTypeId(displayName)
            } else {
                StringArgumentType.getString(ctx, "type")
            }
            val iconId = IdentifierArgumentType.getIdentifier(ctx, "icon").toString()
            val description = StringArgumentType.getString(ctx, "description")
            val existing = DungeonTypeDisplayConfig.get(typeId)
            if (mode == TypeCommandMode.CREATE && existing != null) {
                ctx.source.sendDungeonFeedback("Dungeon type '$typeId' already exists.", DungeonMessageType.ERROR)
                return 0
            }
            if (mode == TypeCommandMode.EDIT && existing == null) {
                ctx.source.sendDungeonFeedback(
                    "Dungeon type '$typeId' has no display entry yet.",
                    DungeonMessageType.ERROR
                )
                return 0
            }
            val enabled = enabledOverride ?: existing?.enabled ?: true
            DungeonTypeConfig.ensureType(typeId)
            val display = DungeonTypeDisplay(
                name = displayName,
                icon = iconId,
                enabled = enabled,
                description = description
            )
            DungeonTypeDisplayConfig.set(typeId, display)
            val action = if (mode == TypeCommandMode.CREATE) "created" else "updated"
            ctx.source.sendDungeonFeedback("Dungeon type '$typeId' $action.")
            return 1
        }
    
        private fun deriveTypeId(displayName: String): String {
            val normalized = displayName.trim().lowercase(Locale.ROOT)
            val sanitized = normalized.replace(Regex("[^a-z0-9]+"), "_").trim('_')
            return sanitized.ifBlank { normalized.ifBlank { "dungeon_type" } }
        }
    
        private fun resolveDungeonTypeId(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val entries = DungeonTypeDisplayConfig.all()
            entries.keys.firstOrNull { it.equals(trimmed, ignoreCase = true) }?.let { return it }
            return entries.entries.firstOrNull { (_, display) ->
                display.name.isNotBlank() && display.name.equals(trimmed, ignoreCase = true)
            }?.key
        }
    
        private fun suggestDungeonTypeNames(builder: SuggestionsBuilder): java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
            DungeonTypeDisplayConfig.all().flatMap { (id, display) ->
                val suggestions = mutableListOf(id)
                if (display.name.isNotBlank()) {
                    suggestions += display.name
                }
                suggestions
            }.forEach { builder.suggest(it) }
            return builder.buildFuture()
        }
    
        private fun sendTimerStatus(source: ServerCommandSource, target: ServerPlayerEntity): Int {
            val viewer = source.entity as? ServerPlayerEntity
            val now = Instant.now()
            val running = PlayerCooldownStore.isDungeonTimerRunning(target.uuid, now)
            val remaining = PlayerCooldownStore.getDungeonTimerRemaining(target.uuid, now)
            val runningTimer = running && !remaining.isZero && !remaining.isNegative
            val message = if (runningTimer) {
                val formatted = formatTimerDuration(remaining)
                if (viewer?.uuid == target.uuid) {
                    "You have $formatted remaining on your dungeon timer."
                } else {
                    "${target.gameProfile.name} has $formatted remaining on their dungeon timer."
                }
            } else {
                if (viewer?.uuid == target.uuid) {
                    "You do not currently have an active dungeon timer."
                } else {
                    "${target.gameProfile.name} does not currently have an active dungeon timer."
                }
            }
            val messageType = if (runningTimer) DungeonMessageType.NORMAL else DungeonMessageType.ERROR
            source.sendDungeonFeedback(message, messageType)
            return 1
        }
    
        private fun formatTimerDuration(duration: Duration): String {
            if (duration.isZero || duration.isNegative) {
                return "00:00"
            }
            val totalSeconds = duration.seconds.coerceAtLeast(0)
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
            }
        }
    
        private enum class TypeCommandMode { CREATE, EDIT }
    
        private fun hasMenuPermission(source: ServerCommandSource): Boolean {
            val entity = source.entity ?: return false
            return entity.hasPermission(MENU_PERMISSION) || entity.hasPermission(ROOT_MENU_PERMISSION)
        }

    private fun hasTimerCheckOtherPermission(source: ServerCommandSource): Boolean {
        return source.hasPermission("eclipse.command.dungeons.timercheck.player")
    }
}
