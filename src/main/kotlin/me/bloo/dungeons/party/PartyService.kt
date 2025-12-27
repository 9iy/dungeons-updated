package me.bloo.dungeons.party

import me.bloo.dungeons.config.DungeonConfig
import me.bloo.dungeons.config.DungeonTypeDisplayConfig
import me.bloo.dungeons.dungeon.manager.DungeonManager
import me.bloo.dungeons.dungeon.runtime.DungeonRuntime
import me.bloo.dungeons.dungeon.runtime.DungeonStartGate
import me.bloo.dungeons.dungeon.runtime.StartPath
import me.bloo.dungeons.dungeon.scan.DungeonScanLogger
import me.bloo.dungeons.dungeon.scan.DungeonScanner
import me.bloo.dungeons.dungeon.scan.DungeonWorldResolver
import me.bloo.dungeons.economy.DungeonEconomy
import me.bloo.dungeons.message.DungeonMessages
import me.bloo.dungeons.message.DungeonMessageType
import me.bloo.dungeons.message.sendDungeonMessage
import me.bloo.dungeons.player.PlayerEffects
import me.bloo.dungeons.sound.SoundService
import me.bloo.dungeons.ui.Guis
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import java.time.Instant
import java.util.*
import kotlin.math.abs
import kotlin.math.max

object PartyService {
    var server: MinecraftServer? = null

    private const val MAX_PARTIES = 15
    private const val MAX_MEMBERS = 4
    private const val START_DEBOUNCE_MS = 5000L
    private const val LOG_PREFIX = "[Dungeons][Party]"

    enum class PartyState { PRE_START, ACTIVE, ENDED }

    enum class PartyEndReason {
        BOSS_DEFEATED,
        LIVES_DEPLETED,
        MANUAL_ABORT,
        LEADER_DISCONNECT,
        MEMBER_DISCONNECT,
        TIMEOUT,
        ERROR
    }

    data class Party(
        val id: Int,
        val leader: UUID,
        var name: String,
        var isPublic: Boolean = false,
        val createdAt: Long = System.currentTimeMillis(),
        val order: MutableList<UUID> = mutableListOf(),
        val ready: MutableMap<UUID, Boolean> = mutableMapOf(),
        var selectedType: String? = null,
        var activeDungeon: String? = null,
        var state: PartyState = PartyState.PRE_START,
        var startLockUntil: Long = 0L
    )

    data class PartyInvite(val partyId: Int, val from: UUID, val to: UUID, val createdAt: Long = System.currentTimeMillis())
    data class Result(val success: Boolean, val message: String, val playClickSound: Boolean = true)

    private val parties = mutableMapOf<Int, Party>()
    private val playerPartyId = mutableMapOf<UUID, Int>()
    private val invitesTo = mutableMapOf<UUID, MutableList<PartyInvite>>()

    private data class DisconnectKey(val dungeonId: String, val partyId: Int)

    private val disconnectIndex = mutableMapOf<DisconnectKey, MutableSet<UUID>>()
    private data class ScanErrorCooldown(val reason: DungeonScanner.ErrorReason, var expiresAt: Long)
    private val scanErrorCooldowns = mutableMapOf<UUID, ScanErrorCooldown>()

    private fun nextPartyId(): Int? = (1..MAX_PARTIES).firstOrNull { it !in parties }

    fun getParty(id: Int): Party? = parties[id]

    private fun disconnectKey(party: Party): DisconnectKey? {
        val dungeonId = party.activeDungeon ?: return null
        return DisconnectKey(dungeonId, party.id)
    }

    fun getDisconnectedMembers(dungeonId: String, partyId: Int): Set<UUID> {
        return disconnectIndex[DisconnectKey(dungeonId, partyId)]?.toSet() ?: emptySet()
    }

    private fun markDisconnected(party: Party, uuid: UUID) {
        disconnectKey(party)?.let { key ->
            val set = disconnectIndex.getOrPut(key) { mutableSetOf() }
            if (set.add(uuid)) {
                println("[Dungeons][Party] Disconnected ${lookupName(uuid) ?: uuid} from party ${party.id} (${key.dungeonId}).")
            }
        }
    }

    private fun clearDisconnected(party: Party, uuid: UUID) {
        disconnectKey(party)?.let { key ->
            val set = disconnectIndex[key]
            if (set != null && set.remove(uuid)) {
                println("[Dungeons][Party] Reconnected ${lookupName(uuid) ?: uuid} to party ${party.id} (${key.dungeonId}).")
                if (set.isEmpty()) {
                    disconnectIndex.remove(key)
                }
            }
        }
    }

    private fun clearDisconnects(party: Party) {
        disconnectKey(party)?.let { key -> disconnectIndex.remove(key) }
    }

    private fun removeFromDisconnectIndex(uuid: UUID) {
        val iterator = disconnectIndex.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.remove(uuid) && entry.value.isEmpty()) {
                iterator.remove()
            }
        }
    }

    fun createParty(leader: ServerPlayerEntity, isPublic: Boolean): Result {
        if (leader.uuid in playerPartyId) return Result(false, "You're already in a party.")
        val id = nextPartyId() ?: return Result(false, "Party limit reached ($MAX_PARTIES).")
        val leaderName = leader.gameProfile.name
        val party = Party(id = id, leader = leader.uuid, name = "$leaderName's Party", isPublic = isPublic).also {
            it.order.add(leader.uuid); it.ready[leader.uuid] = false
        }
        parties[id] = party
        playerPartyId[leader.uuid] = id
        runAs(leader, "party create DungeonParty($leaderName)")
        if (isPublic) Guis.refreshPublicPartiesGui()
        return Result(true, "Created ${party.name} (Party #$id) [${if (isPublic) "Public" else "Private"}].")
    }

    fun disbandCommand(leader: ServerPlayerEntity): Result {
        val party = getPartyOf(leader.uuid) ?: return Result(false, "You're not in a party.")
        if (party.leader != leader.uuid) return Result(false, "Only the leader can disband.")
        deleteParty(party, PartyEndReason.MANUAL_ABORT, leader)
        return Result(true, "Disbanded ${party.name}.")
    }

    fun getPartyOf(player: UUID): Party? = playerPartyId[player]?.let { parties[it] }
    fun Party.membersOrdered(): List<UUID> = order.toList()
    fun Party.leaderName(): String = lookupName(this.leader) ?: "Leader"
    fun lookupName(uuid: UUID): String? = server?.playerManager?.getPlayer(uuid)?.gameProfile?.name

    fun listPublicParties(): List<Party> = parties.values
        .filter { it.isPublic && it.state == PartyState.PRE_START }
        .sortedBy { it.createdAt }

    fun joinPublicParty(player: ServerPlayerEntity, partyId: Int): Result {
        if (player.uuid in playerPartyId) return Result(false, "Leave your current party first.")
        val party = parties[partyId] ?: return Result(false, "That party no longer exists.")
        if (party.state != PartyState.PRE_START) return Result(false, "That party is already running a dungeon.")
        if (!party.isPublic) return Result(false, "That party isn’t public.")
        if (party.order.size >= MAX_MEMBERS) return Result(false, "That party is full.")
        party.order.add(player.uuid); party.ready[player.uuid] = false; playerPartyId[player.uuid] = party.id
            notifyParty(
                party,
                "party.member_joined",
                "<yellow><player></yellow> joined <yellow><party></yellow>.",
                type = DungeonMessageType.NORMAL,
                DungeonMessages.placeholder("player", player.gameProfile.name),
                DungeonMessages.placeholder("party", party.name)
            )
        Guis.refreshPartyGui(party)
        Guis.refreshPublicPartiesGui()
        return Result(true, "Joined ${party.name}.")
    }

    fun invite(inviter: ServerPlayerEntity, target: ServerPlayerEntity): Result {
        val party = getPartyOf(inviter.uuid) ?: return Result(false, "Create a party first: /dungeon party create")
        if (party.state != PartyState.PRE_START) return Result(false, "That party is already running a dungeon.")
        if (party.leader != inviter.uuid) return Result(false, "Only the leader can invite.")
        if (party.order.size >= MAX_MEMBERS) return Result(false, "Party is full ($MAX_MEMBERS).")
        if (target.uuid in playerPartyId) return Result(false, "${target.gameProfile.name} is already in a party.")
        val list = invitesTo.getOrPut(target.uuid) { mutableListOf() }
        if (list.any { it.partyId == party.id }) return Result(false, "Invite already sent.")
        list.add(PartyInvite(partyId = party.id, from = inviter.uuid, to = target.uuid))
        Guis.refreshInvitesGui(target.uuid)
        return Result(true, "Invite sent to ${target.gameProfile.name}.")
    }

    fun getInvites(target: UUID): List<PartyInvite> = invitesTo[target]?.toList() ?: emptyList()

    fun acceptInvite(player: ServerPlayerEntity, invite: PartyInvite): Result {
        if (getPartyOf(player.uuid) != null) return Result(false, "You're already in a party.")
        val party = parties[invite.partyId] ?: return Result(false, "That party no longer exists.")
        if (party.state != PartyState.PRE_START) { declineInvite(player.uuid, invite); return Result(false, "That party has already started a dungeon.") }
        if (party.order.size >= MAX_MEMBERS) { declineInvite(player.uuid, invite); return Result(false, "That party is full.") }
        party.order.add(player.uuid); party.ready[player.uuid] = false; playerPartyId[player.uuid] = party.id
        declineInvite(player.uuid, invite)
            notifyParty(
                party,
                "party.member_joined",
                "<yellow><player></yellow> joined <yellow><party></yellow>.",
                type = DungeonMessageType.NORMAL,
                DungeonMessages.placeholder("player", player.gameProfile.name),
                DungeonMessages.placeholder("party", party.name)
            )
        Guis.refreshPartyGui(party)
        return Result(true, "Joined ${party.name}.")
    }

    fun leaveParty(player: ServerPlayerEntity): Result {
        val party = getPartyOf(player.uuid) ?: return Result(false, "You're not in a party.")
        return when (party.state) {
            PartyState.PRE_START -> {
                if (party.leader == player.uuid) {
                    deleteParty(party, PartyEndReason.MANUAL_ABORT, player)
                    Result(true, "You disbanded your party.")
                } else {
                    removeFromDisconnectIndex(player.uuid)
                    val name = player.gameProfile.name
                    party.order.remove(player.uuid)
                    party.ready.remove(player.uuid)
                    playerPartyId.remove(player.uuid)
                    notifyParty(
                        party,
                        "party.member_left",
                        "<yellow><player></yellow> left <yellow><party></yellow>.",
                        type = DungeonMessageType.NORMAL,
                        DungeonMessages.placeholder("player", name),
                        DungeonMessages.placeholder("party", party.name)
                    )
                    Guis.refreshPartyGui(party)
                    if (party.isPublic) Guis.refreshPublicPartiesGui()
                    if (party.order.isEmpty()) {
                        deleteParty(party, PartyEndReason.MEMBER_DISCONNECT)
                    }
                    Result(true, "You left ${party.name}.")
                }
            }
            PartyState.ACTIVE -> Result(false, "You can't leave during a dungeon.", playClickSound = false)
            PartyState.ENDED -> {
                playerPartyId.remove(player.uuid)
                removeFromDisconnectIndex(player.uuid)
                Result(true, "You left the party.")
            }
        }
    }

    fun declineInvite(player: UUID, invite: PartyInvite) {
        invitesTo[player]?.removeIf { it.partyId == invite.partyId }
        if (invitesTo[player]?.isEmpty() == true) invitesTo.remove(player)
        Guis.refreshInvitesGui(player)
    }

    fun selectDungeon(player: ServerPlayerEntity, type: String): Result {
        val party = getPartyOf(player.uuid) ?: return Result(false, "You're not in a party.")
        if (party.state != PartyState.PRE_START) return Result(false, "Dungeon already in progress.")
        if (party.leader != player.uuid) return Result(false, "Only the leader can select a dungeon.")
        if (!DungeonTypeDisplayConfig.isEnabled(type)) {
            return Result(false, "That dungeon type is disabled.")
        }
        val displayName = DungeonTypeDisplayConfig.get(type)?.name?.takeIf { it.isNotBlank() } ?: type
        party.selectedType = type
        val recipients = party.order.filter { it != player.uuid }
        if (recipients.isNotEmpty()) {
            notifyAll(
                recipients,
                "party.dungeon.selected",
                "<yellow><player></yellow> selected <aqua><dungeon></aqua>.",
                DungeonMessageType.NORMAL,
                DungeonMessages.placeholder("player", player.gameProfile.name),
                DungeonMessages.placeholder("dungeon", displayName)
            )
        }
        Guis.refreshPartyGui(party)
        return Result(true, "Selected $displayName")
    }

    fun startDungeon(player: ServerPlayerEntity): Result {
        return startDungeonInternal(player, skipStartLock = false)
    }

    private fun startDungeonInternal(
        player: ServerPlayerEntity,
        skipStartLock: Boolean
    ): Result {
        val party = getPartyOf(player.uuid) ?: return Result(false, "You're not in a party.")
        if (party.state != PartyState.PRE_START) return Result(false, "Your party is already in a dungeon.")
        if (party.leader != player.uuid) return Result(false, "Only the leader can start the dungeon.")
        if (!party.order.all { party.ready[it] == true }) return Result(false, "Not everyone is ready.")
        val type = party.selectedType ?: return Result(false, "No dungeon selected.")

        val nowMillis = System.currentTimeMillis()
        if (!skipStartLock && nowMillis < party.startLockUntil) {
            return Result(false, "Dungeon start already in progress. Please wait a moment.")
        }
        party.startLockUntil = nowMillis + START_DEBOUNCE_MS

        val serverInstance = server ?: return Result(false, "Server unavailable.")
        val evaluation = DungeonStartGate.evaluatePartyStartPaths(party, Instant.now())

        var chargeResult: DungeonEconomy.ChargeResult? = null
        val result = try {
            run outer@{
                val initialBlocked = evaluation.paths.mapNotNull { (uuid, path) ->
                    (path as? StartPath.Blocked)?.let { uuid to it }
                }.toMap()
                if (initialBlocked.isNotEmpty()) {
                    val summary = DungeonStartGate.formatBlockedReasons(party, initialBlocked)
                    notifyParty(
                        party,
                        "party.ready.summary",
                        "<yellow><summary></yellow>",
                        DungeonMessageType.ERROR,
                        DungeonMessages.placeholder("summary", summary)
                    )
                    return@outer Result(false, summary)
                }

                val dungeon = DungeonManager.findAvailableDungeon(type)
                    ?: return@outer Result(false, "All $type dungeons are currently full.")

                var releaseDungeon = true
                fun releaseAndReturn(result: Result): Result {
                    if (releaseDungeon) {
                        DungeonManager.markTaken(dungeon.name, false)
                        releaseDungeon = false
                    }
                    return result
                }

                val world = DungeonWorldResolver.resolve(serverInstance, dungeon, player.serverWorld)
                    ?: run {
                        val dimensionLabel = dungeon.dimension ?: "configured dimension"
                        val message = "Failed to start dungeon: World '$dimensionLabel' is unavailable."
                        notifyParty(party, "party.type.select", message, DungeonMessageType.ERROR)
                        return@outer releaseAndReturn(Result(false, message))
                    }

                val scanResult = DungeonScanner.scan(world, dungeon, DungeonConfig.instance)
                val scan = when (scanResult) {
                    is DungeonScanner.ScanResult.Success -> {
                        scanErrorCooldowns.remove(player.uuid)
                        scanResult
                    }
                    is DungeonScanner.ScanResult.Error -> {
                        DungeonScanLogger.logError("partyStart", dungeon, scanResult)
                        val playSound = shouldPlaySoundForScanError(player, scanResult.reason)
                        return@outer releaseAndReturn(Result(false, scanResult.playerMessage, playSound))
                    }
                }

                val commitEvaluation = DungeonStartGate.evaluatePartyStartPaths(party, Instant.now())
                val commitBlocked = commitEvaluation.paths.mapNotNull { (uuid, path) ->
                    (path as? StartPath.Blocked)?.let { uuid to it }
                }.toMap()
                if (commitBlocked.isNotEmpty()) {
                    val summary = DungeonStartGate.formatBlockedReasons(party, commitBlocked)
                    notifyParty(
                        party,
                        "party.start.requirements",
                        "<red><summary></red>",
                        DungeonMessageType.ERROR,
                        DungeonMessages.placeholder("summary", summary)
                    )
                    return@outer releaseAndReturn(Result(false, summary))
                }

                val outcome = DungeonStartGate.commitStart(
                    party,
                    commitEvaluation.paths,
                    Instant.now()
                )
                if (!outcome.success) {
                    val message = outcome.error ?: "Failed to validate start requirements."
                    val display = "Failed to start dungeon: $message"
                    notifyParty(
                        party,
                        "party.start.blocked",
                        "<red>⛔ <reason></red>",
                        DungeonMessageType.ERROR,
                        DungeonMessages.placeholder("reason", message)
                    )
                    return@outer releaseAndReturn(Result(false, display))
                }

                val appliedCommit = outcome

                val spawn = scan.spawn
                if (spawn == null) {
                    DungeonStartGate.rollbackStart(appliedCommit, "Missing spawn block")
                    return@outer releaseAndReturn(Result(false, "Spawn block not found in dungeon."))
                }

                val members = party.order.mapNotNull { serverInstance.playerManager.getPlayer(it) }

                val chargeOutcome = DungeonEconomy.chargeForStart(serverInstance, members).join()
                if (!chargeOutcome.success) {
                    DungeonStartGate.rollbackStart(appliedCommit, "Entry fee processing failed")
                    val failure = chargeOutcome.failure
                    if (failure?.playerId != null) {
                        serverInstance.execute {
                            serverInstance.playerManager.getPlayer(failure.playerId)
                                ?.sendDungeonMessage(failure.message, DungeonMessageType.ERROR)
                        }
                    }
                    val failureMessage = chargeOutcome.message ?: "Failed to process dungeon entry fee."
                    notifyParty(
                        party,
                        "party.start.fail",
                        "<red>⛔ <reason></red>",
                        DungeonMessageType.ERROR,
                        DungeonMessages.placeholder("reason", failureMessage)
                    )
                    return@outer releaseAndReturn(Result(false, "Failed to start dungeon: $failureMessage"))
                }
                chargeResult = chargeOutcome

                try {
                    DungeonRuntime.startSession(party, dungeon, scan, world)
                } catch (error: DungeonRuntime.BossRoomInitializationException) {
                    DungeonStartGate.rollbackStart(appliedCommit, "Boss room initialization failed")
                    chargeResult?.let { DungeonEconomy.refundStart(serverInstance, it) }
                    return@outer releaseAndReturn(
                        Result(false, error.message ?: "Failed to initialize boss room.")
                    )
                } catch (error: Exception) {
                    DungeonStartGate.rollbackStart(appliedCommit, "Dungeon session initialization failed: ${error.message}")
                    chargeResult?.let { DungeonEconomy.refundStart(serverInstance, it) }
                    println("$LOG_PREFIX Failed to start ${dungeon.name}: ${error.message}")
                    return@outer releaseAndReturn(
                        Result(false, "Failed to start dungeon: ${error.message ?: "unknown error"}.")
                    )
                }

                party.activeDungeon = dungeon.name
                party.state = PartyState.ACTIVE
                party.ready.keys.forEach { party.ready[it] = false }

                val startTitle = "[\"\",{\"text\":\"!! The Dungeon Awakens !!\",\"bold\":true,\"color\":\"gold\"},{\"text\":\" \"}]"
                val startSubtitle = "[\"\",{\"text\":\"Your\",\"color\":\"red\"},{\"text\":\" courage\",\"color\":\"yellow\"},{\"text\":\" will be tested within these walls...\",\"color\":\"red\"},{\"text\":\" \"}]"

                try {
                    members.forEach { member ->
                        member.closeHandledScreen()
                        member.teleport(world, spawn.x + 0.5, (spawn.y + 1).toDouble(), spawn.z + 0.5, member.yaw, member.pitch)
                    }
                    SoundService.playToPlayers(members, SoundEvents.ENTITY_ENDER_DRAGON_GROWL)
                    val introDurationTicks = 3 * 20
                    val introAmplifier = 4
                    members.forEach { member ->
                        PlayerEffects.sendTitle(member, startTitle, startSubtitle, 20, 80, 20)
                        member.addStatusEffect(
                            StatusEffectInstance(StatusEffects.BLINDNESS, introDurationTicks, introAmplifier, false, false, true)
                        )
                        member.addStatusEffect(
                            StatusEffectInstance(StatusEffects.SLOWNESS, introDurationTicks, introAmplifier, false, false, true)
                        )
                    }
                } catch (error: Exception) {
                    println("$LOG_PREFIX Failed to teleport party ${party.id}: ${error.message}")
                    DungeonRuntime.endSession(dungeon.name)
                    party.activeDungeon = null
                    party.state = PartyState.PRE_START
                    DungeonStartGate.rollbackStart(appliedCommit, "Teleport failure: ${error.message}")
                    chargeResult?.let { DungeonEconomy.refundStart(serverInstance, it) }
                    return@outer releaseAndReturn(
                        Result(false, "Failed to teleport party: ${error.message ?: "unknown error"}.")
                    )
                }

                val summary = DungeonStartGate.formatSuccessSummary(party, appliedCommit.timersStarted)
                notifyParty(
                    party,
                    "party.ready.summary",
                    "<yellow><summary></yellow>",
                    type = DungeonMessageType.NORMAL,
                    DungeonMessages.placeholder("summary", summary)
                )
                val timerNames = appliedCommit.timersStarted.joinToString(", ") { lookupName(it) ?: it.toString() }
                println("[Dungeons][Start] Party ${party.id} (${party.leaderName()}) started ${dungeon.name}. Timers=$timerNames")
                println("$LOG_PREFIX Party ${party.id} entered dungeon ${dungeon.name} with ${members.size} members.")
                releaseDungeon = false
                Result(true, "Dungeon started!")
            }
        } finally {
            party.startLockUntil = 0L
        }
        return result
    }

    fun endDungeon(player: ServerPlayerEntity, reason: PartyEndReason = PartyEndReason.MANUAL_ABORT): Result {
        val party = getPartyOf(player.uuid) ?: return Result(false, "You're not in a party.")
        return concludeDungeon(party, reason)
    }

    fun concludeDungeonByName(dungeonName: String, reason: PartyEndReason): Result? {
        val party = parties.values.firstOrNull { it.activeDungeon == dungeonName } ?: return null
        return concludeDungeon(party, reason)
    }

    private fun concludeDungeon(party: Party, reason: PartyEndReason): Result {
        val onlineMembers = gatherOnlineMembers(party)
        val dungeonName = party.activeDungeon ?: return Result(false, "No active dungeon.")
        val teleported = teleportPartyToEnd(party)
        DungeonManager.markTaken(dungeonName, false)
        DungeonRuntime.endSession(dungeonName)
        if (reason == PartyEndReason.BOSS_DEFEATED) {
            val srv = server
            if (srv != null) {
                party.order.mapNotNull { srv.playerManager.getPlayer(it) }
                    .forEach { player -> DungeonEconomy.rewardOnClear(srv, player) }
            }
        }
        deleteParty(party, reason)
        runExternalLeaveCommands(onlineMembers)
        return if (teleported) {
            Result(true, "Dungeon ended.")
        } else {
            Result(false, "Dungeon ended, but the configured end destination could not be found.")
        }
    }


    fun toggleReady(player: ServerPlayerEntity): Boolean {
        val party = getPartyOf(player.uuid) ?: return false
        if (party.state != PartyState.PRE_START) return false
        val current = party.ready[player.uuid] ?: false
        party.ready[player.uuid] = !current
        Guis.refreshPartyGui(party)
        return !current
    }

    private fun shouldPlaySoundForScanError(player: ServerPlayerEntity, reason: DungeonScanner.ErrorReason): Boolean {
        val cooldownTicks = max(0L, DungeonConfig.instance.scanSettings().errorCooldownTicks)
        val now = player.serverWorld.time
        val existing = scanErrorCooldowns[player.uuid]
        if (existing != null && existing.reason == reason && existing.expiresAt > now) {
            return false
        }
        val expiresAt = if (cooldownTicks <= 0) now else now + cooldownTicks
        scanErrorCooldowns[player.uuid] = ScanErrorCooldown(reason, expiresAt)
        return true
    }

    fun kick(leader: ServerPlayerEntity, target: UUID): Result {
        val party = getPartyOf(leader.uuid) ?: return Result(false, "You're not in a party.")
        if (party.state != PartyState.PRE_START) return Result(false, "Cannot kick members during a dungeon.")
        if (party.leader != leader.uuid) return Result(false, "Only the leader can kick.")
        if (target == leader.uuid) return Result(false, "You can't kick yourself.")
        if (target !in party.order) return Result(false, "That player isn't in your party.")
        removeFromDisconnectIndex(target)
        party.order.remove(target); party.ready.remove(target); playerPartyId.remove(target)
        notifyParty(
            party,
            "party.member_removed",
            "<yellow><player></yellow> was removed from <yellow><party></yellow>.",
            type = DungeonMessageType.NORMAL,
            DungeonMessages.placeholder("player", lookupName(target) ?: "Player"),
            DungeonMessages.placeholder("party", party.name)
        )
        server?.playerManager?.getPlayer(target)?.sendDungeonMessage(
            "You were removed from ${party.name}.",
            DungeonMessageType.ERROR
        )
        Guis.refreshPartyGui(party)
        if (party.isPublic) Guis.refreshPublicPartiesGui()
        return Result(true, "Removed from party.")
    }

    fun onDisconnect(player: ServerPlayerEntity) {
        val party = getPartyOf(player.uuid)
        if (party == null) {
            removeFromDisconnectIndex(player.uuid)
            return
        }
        when (party.state) {
            PartyState.PRE_START -> {
                if (party.leader == player.uuid) {
                    deleteParty(party, PartyEndReason.LEADER_DISCONNECT, player)
                } else {
                    party.order.remove(player.uuid)
                    party.ready.remove(player.uuid)
                    playerPartyId.remove(player.uuid)
                    notifyParty(
                        party,
                        "party.member_left",
                        "<yellow><player></yellow> left <yellow><party></yellow>.",
                        type = DungeonMessageType.NORMAL,
                        DungeonMessages.placeholder("player", player.gameProfile.name),
                        DungeonMessages.placeholder("party", party.name)
                    )
                    if (party.order.isEmpty()) {
                        deleteParty(party, PartyEndReason.MEMBER_DISCONNECT)
                    } else {
                        Guis.refreshPartyGui(party)
                        if (party.isPublic) Guis.refreshPublicPartiesGui()
                    }
                }
            }
            PartyState.ACTIVE -> {
                markDisconnected(party, player.uuid)
                notifyParty(
                    party,
                    "party.member_disconnected",
                    "<yellow><player></yellow> disconnected from <yellow><party></yellow>.",
                    type = DungeonMessageType.NORMAL,
                    DungeonMessages.placeholder("player", player.gameProfile.name),
                    DungeonMessages.placeholder("party", party.name)
                )
                val srv = server
                val allOffline = srv?.let { instance ->
                    party.order.all { member -> instance.playerManager.getPlayer(member) == null }
                } ?: false
                if (allOffline) {
                    val dungeonName = party.activeDungeon
                    if (dungeonName != null) {
                        DungeonRuntime.getSession(dungeonName)?.let { session ->
                            val now = session.world.time
                            if (session.idleSince == null) {
                                session.idleSince = now
                            }
                            DungeonRuntime.maybeTerminateIdleSession(session, now)
                        }
                        concludeDungeonByName(dungeonName, PartyEndReason.TIMEOUT)
                    }
                    return
                }
            }
            PartyState.ENDED -> {
                playerPartyId.remove(player.uuid)
            }
        }
    }

    fun onLogin(player: ServerPlayerEntity) {
        val party = getPartyOf(player.uuid)
        if (party == null) {
            removeFromDisconnectIndex(player.uuid)
            return
        }
        when (party.state) {
            PartyState.PRE_START -> {
                party.ready.putIfAbsent(player.uuid, false)
                Guis.refreshPartyGui(party)
            }
            PartyState.ACTIVE -> {
                handleActiveRejoin(player, party)
            }
            PartyState.ENDED -> {
                playerPartyId.remove(player.uuid)
            }
        }
    }

    private fun handleActiveRejoin(player: ServerPlayerEntity, party: Party) {
        val dungeonName = party.activeDungeon
        if (dungeonName == null) {
            teleportToConfiguredEnd(player)
            clearDisconnected(party, player.uuid)
            deleteParty(party, PartyEndReason.ERROR)
            return
        }
        val session = DungeonRuntime.getSession(dungeonName)
        if (session == null) {
            teleportToConfiguredEnd(player)
            clearDisconnected(party, player.uuid)
            deleteParty(party, PartyEndReason.ERROR)
            return
        }
        val world = session.world
        val srv = server ?: return
        val leader = srv.playerManager.getPlayer(party.leader)
        val preferred = leader?.blockPos ?: session.partySpawn
        val yaw = leader?.yaw ?: player.yaw
        val pitch = leader?.pitch ?: player.pitch
        val safe = findSafeTeleport(world, preferred) ?: findSafeTeleport(world, session.partySpawn)
        if (safe != null) {
            player.teleport(world, safe.x, safe.y, safe.z, yaw, pitch)
            println("[Dungeons][Party] Reconnected ${player.gameProfile.name} to party ${party.id} in ${dungeonName}.")
        } else {
            player.sendDungeonMessage(
                "Unable to find a safe place near your party. Please try again soon.",
                DungeonMessageType.ERROR
            )
            println("[Dungeons][Party] Failed to find safe teleport for ${player.gameProfile.name} in ${dungeonName}.")
        }
        clearDisconnected(party, player.uuid)
    }

    fun cleanupOrphanedParty(dungeonName: String, members: Collection<UUID>) {
        val party = parties.values.firstOrNull { it.activeDungeon == dungeonName }
        if (party != null) {
            DungeonManager.markTaken(dungeonName, false)
            deleteParty(party, PartyEndReason.ERROR)
            return
        }
        members.forEach { uuid ->
            playerPartyId.remove(uuid)
            removeFromDisconnectIndex(uuid)
        }
    }

    private fun deleteParty(
        party: Party,
        reason: PartyEndReason,
        initiator: ServerPlayerEntity? = null
    ) {
        initiator?.let { runAs(it, "party disband") }
        val members = party.order.toList()
        val partyName = party.name
        parties.remove(party.id)
        members.forEach { playerPartyId.remove(it) }
        cleanupInvitesForParty(party.id)
        clearDisconnects(party)
        party.state = PartyState.ENDED
        party.activeDungeon = null
        party.selectedType = null
        party.order.clear()
        party.ready.clear()
        if (party.isPublic) Guis.refreshPublicPartiesGui()
        val srv = server
        if (srv != null && members.isNotEmpty()) {
            val message = "Party ended (${formatEndReason(reason)})."
            members.forEach { uuid ->
                srv.playerManager.getPlayer(uuid)?.sendDungeonMessage(
                    message,
                    if (reason == PartyEndReason.BOSS_DEFEATED) DungeonMessageType.NORMAL else DungeonMessageType.ERROR
                )
            }
        }
        println("[Dungeons][Party] Deleted party ${party.id} ($partyName) reason=${reason.name} members=${members.size}")
    }

    private fun gatherOnlineMembers(party: Party): List<ServerPlayerEntity> {
        val srv = server ?: return emptyList()
        return party.order.mapNotNull { srv.playerManager.getPlayer(it) }
    }

    private fun runExternalLeaveCommands(players: List<ServerPlayerEntity>) {
        players.forEach { player ->
            runAs(player, "dungeon party leave")
            runAs(player, "party leave")
        }
    }

    private fun cleanupInvitesForParty(partyId: Int) {
        val iterator = invitesTo.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.removeIf { it.partyId == partyId }
            if (entry.value.isEmpty()) {
                iterator.remove()
            }
        }
    }

    private fun formatEndReason(reason: PartyEndReason): String = when (reason) {
        PartyEndReason.BOSS_DEFEATED -> "Boss defeated"
        PartyEndReason.LIVES_DEPLETED -> "All lives lost"
        PartyEndReason.MANUAL_ABORT -> "Run aborted"
        PartyEndReason.LEADER_DISCONNECT -> "Leader disconnected"
        PartyEndReason.MEMBER_DISCONNECT -> "Party disbanded"
        PartyEndReason.TIMEOUT -> "Run timed out"
        PartyEndReason.ERROR -> "Run ended due to an error"
    }

    private fun teleportToConfiguredEnd(player: ServerPlayerEntity) {
        val srv = server ?: return
        val cfg = DungeonConfig.instance
        val destWorldKey = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(cfg.endWorld))
        val destWorld = srv.getWorld(destWorldKey) ?: return
        val basePos = BlockPos(cfg.endPos.x, cfg.endPos.y, cfg.endPos.z)
        val safe = findSafeTeleport(destWorld, basePos) ?: Vec3d(basePos.x + 0.5, basePos.y.toDouble(), basePos.z + 0.5)
        player.teleport(destWorld, safe.x, safe.y, safe.z, player.yaw, player.pitch)
    }

    private fun findSafeTeleport(
        world: ServerWorld,
        origin: BlockPos,
        horizontalRadius: Int = 4,
        verticalRadius: Int = 4
    ): Vec3d? {
        val candidates = mutableListOf<BlockPos>()
        for (dy in -verticalRadius..verticalRadius) {
            val y = origin.y + dy
            if (y < world.bottomY + 1 || y >= world.topY - 1) continue
            for (r in 0..horizontalRadius) {
                for (dx in -r..r) {
                    for (dz in -r..r) {
                        if (max(abs(dx), abs(dz)) != r) continue
                        candidates.add(BlockPos(origin.x + dx, y, origin.z + dz))
                    }
                }
            }
        }
        for (pos in candidates) {
            if (!world.isChunkLoaded(pos.x shr 4, pos.z shr 4)) {
                world.getChunk(pos.x shr 4, pos.z shr 4)
            }
            if (isSafeDestination(world, pos)) {
                return Vec3d(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
            }
        }
        return null
    }

    private fun isSafeDestination(world: ServerWorld, pos: BlockPos): Boolean {
        if (pos.y < world.bottomY + 1 || pos.y >= world.topY - 1) return false
        val below = pos.down()
        val belowState = world.getBlockState(below)
        if (!belowState.isSolidBlock(world, below)) return false
        val feetState = world.getBlockState(pos)
        val headState = world.getBlockState(pos.up())
        if (!feetState.fluidState.isEmpty || !headState.fluidState.isEmpty) return false
        if (!feetState.getCollisionShape(world, pos).isEmpty) return false
        if (!headState.getCollisionShape(world, pos.up()).isEmpty) return false
        return true
    }

    private fun notifyParty(
        party: Party,
        key: String,
        fallback: String,
        type: DungeonMessageType = DungeonMessageType.NORMAL,
        vararg placeholders: TagResolver
    ) = notifyAll(party.order, key, fallback, type, *placeholders)

    private fun notifyAll(
        uuids: List<UUID>,
        key: String,
        fallback: String,
        type: DungeonMessageType = DungeonMessageType.NORMAL,
        vararg placeholders: TagResolver
    ) {
        val s = server ?: return
        uuids.forEach { s.playerManager.getPlayer(it)?.sendDungeonMessage(key, fallback, type, *placeholders) }
    }

    private fun teleportPartyToEnd(party: Party): Boolean {
        val srv = server ?: return false
        val cfg = DungeonConfig.instance
        val destWorldKey = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(cfg.endWorld))
        val destWorld = srv.getWorld(destWorldKey) ?: return false
        val basePos = BlockPos(cfg.endPos.x, cfg.endPos.y, cfg.endPos.z)
        party.order.forEach { uuid ->
            srv.playerManager.getPlayer(uuid)?.let { member ->
                member.closeHandledScreen()
                val safe = findSafeTeleport(destWorld, basePos)
                    ?: Vec3d(basePos.x + 0.5, basePos.y.toDouble(), basePos.z + 0.5)
                member.teleport(destWorld, safe.x, safe.y, safe.z, member.yaw, member.pitch)
            }
        }
        return true
    }

    fun runAs(player: ServerPlayerEntity, raw: String): Boolean {
        player.server.commandManager.executeWithPrefix(player.commandSource, raw)
        return true
    }
}
