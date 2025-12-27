package me.bloo.whosthatpokemon2.dungeons

import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.CandleBlock
import net.minecraft.block.CandleCakeBlock
import net.minecraft.block.DoorBlock
import net.minecraft.block.enums.DoubleBlockHalf
import net.minecraft.block.entity.DispenserBlockEntity
import net.minecraft.block.CampfireBlock
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.CustomModelDataComponent
import net.minecraft.component.type.LoreComponent
import net.minecraft.component.type.NbtComponent
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.effect.StatusEffect
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.entity.boss.BossBar
import net.minecraft.entity.boss.ServerBossBar
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.fluid.Fluids
import me.bloo.whosthatpokemon2.dungeons.BlockPosDto
import me.bloo.whosthatpokemon2.dungeons.config.DungeonConfig
import me.bloo.whosthatpokemon2.dungeons.config.DungeonGameplayConfig
import me.bloo.whosthatpokemon2.dungeons.config.DungeonLootConfig
import me.bloo.whosthatpokemon2.dungeons.config.DungeonLootTable
import me.bloo.whosthatpokemon2.dungeons.config.DungeonTypeConfig
import me.bloo.whosthatpokemon2.dungeons.config.GoodChestLootConfig
import me.bloo.whosthatpokemon2.dungeons.config.LootItemConfig
import me.bloo.whosthatpokemon2.dungeons.config.SecretBossConfigManager
import me.bloo.whosthatpokemon2.dungeons.config.SecretBossEntry
import me.bloo.whosthatpokemon2.dungeons.message.DungeonMessageType
import me.bloo.whosthatpokemon2.dungeons.message.sendDungeonMessage
import net.minecraft.inventory.Inventory
import net.minecraft.inventory.SimpleInventory
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.screen.NamedScreenHandlerFactory
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.ActionResult
import net.minecraft.util.Formatting
import net.minecraft.util.Hand
import net.minecraft.particle.ParticleTypes
import net.minecraft.util.DyeColor
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.state.property.Property
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtList
import kotlin.random.asKotlinRandom
import me.bloo.whosthatpokemon2.dungeons.world.ChunkLease
import me.bloo.whosthatpokemon2.dungeons.world.ChunkPin

/**
 * Keep tabs on live dungeon runs for door handling and debug chatter.
 */
object DungeonRuntime {
    data class Door(val min: BlockPos, val max: BlockPos, val center: BlockPos)
    data class Room(
        val min: BlockPos,
        val max: BlockPos,
        val center: BlockPos,
        val boundingBox: Box = createRoomBounds(min, max),
        val doors: MutableList<Door> = mutableListOf(),
        val trackedAlphaUuids: MutableSet<UUID> = mutableSetOf(),
        val firstEntrants: MutableList<UUID> = mutableListOf(),
        var isBossCleanupRunning: Boolean = false,
        var hasBossCleanupCompleted: Boolean = false
    )

    private fun createRoomBounds(min: BlockPos, max: BlockPos): Box {
        return Box(
            min.x.toDouble(),
            min.y.toDouble(),
            min.z.toDouble(),
            (max.x + 1).toDouble(),
            (max.y + 1).toDouble(),
            (max.z + 1).toDouble()
        )
    }
    data class RaidState(
        val participants: MutableSet<UUID> = mutableSetOf(),
        val dead: MutableSet<UUID> = mutableSetOf(),
        var active: Boolean = false,
        var currentWave: Int = 0,
        var waveInProgress: Boolean = false,
        var completed: Boolean = false,
        var nextCheckTick: Long = 0L,
        var hardSpawned: Int = 0,
        var mediumSpawned: Int = 0,
        var maxWaves: Int = WAVE_CONFIG.size
    )
    data class GildedChestState(
        val roomIdx: Int,
        private var resolving: Boolean = false,
        var resolved: Boolean = false
    ) {
        fun beginResolution(): Boolean {
            if (resolved || resolving) return false
            resolving = true
            return true
        }

        fun markResolved() {
            resolved = true
            resolving = false
        }

        fun cancelResolution() {
            resolving = false
        }
    }

    private fun clearInventory(world: ServerWorld, pos: BlockPos) {
        val blockEntity = world.getBlockEntity(pos) ?: return
        val inventory = blockEntity as? Inventory ?: return
        for (slot in 0 until inventory.size()) {
            inventory.setStack(slot, ItemStack.EMPTY)
        }
        blockEntity.markDirty()
    }

    private data class BossRoomData(
        val roomIndex: Int,
        val spawnPos: BlockPos?,
        val goldSpawnPoints: List<BlockPos>,
        val anchorPos: BlockPos
    )

    data class StoredBlockState(
        val pos: BlockPos,
        val state: BlockState
    )

    enum class SecretRoomPhase { WAITING_READY, IN_BOSS_ROOM, CAPTURE, COMPLETED }

    private const val SECRET_GUI_DELAY_TICKS: Long = 10 * 20L

    data class SecretRoomState(
        val definition: SecretRoomDefinition,
        val world: ServerWorld,
        val trapdoorPos: BlockPos,
        val readyArea: Box,
        val bossSpawnPos: BlockPos,
        val bossTeleportTargets: List<BlockPos>,
        var phase: SecretRoomPhase = SecretRoomPhase.WAITING_READY,
        var crafterState: StoredBlockState? = null,
        var bossEntry: SecretBossEntry? = null,
        var bossEntityUuid: UUID? = null,
        var bossBar: ServerBossBar? = null,
        var bossDefeatedTick: Long = 0L,
        var bossSpawnTick: Long = 0L,
        var bossSpawnConfirmed: Boolean = false,
        val completedPlayers: MutableSet<UUID> = mutableSetOf(),
        val attempts: MutableMap<UUID, Int> = mutableMapOf(),
        val successes: MutableSet<UUID> = mutableSetOf(),
        val guiOpenPlayers: MutableSet<UUID> = mutableSetOf(),
        val forcedGuiClosures: MutableSet<UUID> = mutableSetOf(),
        val pendingGuiPlayers: MutableSet<UUID> = mutableSetOf(),
        val waitingForMasterBall: MutableSet<UUID> = mutableSetOf(),
        var nextTeleportIndex: Int = 0
    )

    class EmbrynsChestPlan {
        private var chestCounter: Int = 0

        fun reset() {
            chestCounter = 0
        }

        fun nextEmbrynsCount(random: ThreadLocalRandom): Int {
            chestCounter += 1
            return if (chestCounter % 2 == 0) {
                random.nextInt(1, 3)
            } else {
                0
            }
        }
    }

    class SoothingIceChestPlan {
        private var chestCounter: Int = 0

        fun reset() {
            chestCounter = 0
        }

        fun nextSoothingIceCount(random: ThreadLocalRandom): Int {
            chestCounter += 1
            return if (chestCounter % 2 == 0) {
                random.nextInt(1, 3)
            } else {
                0
            }
        }
    }

    class BossRoomInitializationException(message: String) : RuntimeException(message)

    enum class DungeonStatus { RUNNING, AWAITING_PROGRESS, SECRET_ROOM, ENDED }

    data class Session(
        val dungeon: Dungeon,
        val partyId: Int,
        val world: ServerWorld,
        val rooms: List<Room>,
        val players: MutableSet<UUID>,
        val playerRooms: MutableMap<UUID, Room?> = mutableMapOf(),
        val actions: MutableMap<Int, MutableList<RoomAction>> = mutableMapOf(),
        val cooldowns: MutableMap<UUID, MutableMap<Int, Long>> = mutableMapOf(),
        val spawn: BlockPos,
        var partySpawn: BlockPos,
        val difficulties: DifficultySet,
        var lives: Int = 5,
        val raidStates: MutableMap<Int, RaidState> = mutableMapOf(),
        val playerRaidRooms: MutableMap<UUID, Int> = mutableMapOf(),
        val raidDoorCooldowns: MutableMap<UUID, Long> = mutableMapOf(),
        val raidSpawnPoints: Map<Int, List<BlockPos>> = emptyMap(),
        val removedGoldBlocks: Set<BlockPos> = emptySet(),
        val replacedEmeraldBlocks: Set<BlockPos> = emptySet(),
        val gildedChests: MutableMap<BlockPos, GildedChestState> = mutableMapOf(),
        val dungeonBossBar: ServerBossBar,
        val raidBossBars: MutableMap<Int, ServerBossBar> = mutableMapOf(),
        val raidEntityUuids: MutableMap<Int, MutableSet<UUID>> = mutableMapOf(),
        val bossRoomIndex: Int? = null,
        val bossSpawnPos: BlockPos? = null,
        val bossCrafterState: StoredBlockState? = null,
        val bossGoldSpawnPoints: List<BlockPos> = emptyList(),
        val bossRoomKey: DoorManager.RoomKey? = null,
        val bossDoors: Map<DoorManager.DoorType, DoorManager.DoorKey> = emptyMap(),
        val bossAnchorPos: BlockPos? = null,
        val defaultBossBarTitle: String,
        val defaultBossBarColor: BossBar.Color,
        var bossEntityUuid: UUID? = null,
        var bossStarted: Boolean = false,
        var bossCompleted: Boolean = false,
        var bossEntitySpawned: Boolean = false,
        var bossNextSummonTick: Long = 0L,
        var bossWavesSpawned: Int = 0,
        var bossTotalWaves: Int = 0,
        var bossPaused: Boolean = false,
        var bossPauseStartTick: Long? = null,
        var bossFailAtTick: Long? = null,
        var lastBossRoomCount: Int = -1,
        val freezeStates: MutableMap<UUID, FreezeState> = mutableMapOf(),
        val heatStates: MutableMap<UUID, HeatState> = mutableMapOf(),
        val isIceDungeon: Boolean,
        val isFireDungeon: Boolean,
        val dungeonBounds: Box,
        var nextFreezeTick: Long = 0L,
        var nextHeatTick: Long = 0L,
        var idleSince: Long? = null,
        val embrynsChestPlan: EmbrynsChestPlan? = null,
        val soothingIceChestPlan: SoothingIceChestPlan? = null,
        val pendingDoorClosures: MutableMap<Int, Long> = mutableMapOf(),
        val freezeRateModifiers: MutableMap<UUID, MutableMap<String, Double>> = mutableMapOf(),
        val flameBodyPokemon: MutableMap<UUID, UUID> = mutableMapOf(),
        val gildedChestPositions: Set<BlockPos> = emptySet(),
        val flameShieldChance: Double = 0.0,
        val legendary: Boolean = false,
        val soloRun: Boolean = false,
        var secretRoomState: SecretRoomState? = null,
        val instanceId: UUID,
        val leaderId: UUID,
        var leaderName: String,
        val dimension: Identifier,
        var chunkLease: ChunkLease? = null,
        var status: DungeonStatus = DungeonStatus.RUNNING,
        var partySizeSnapshot: Int = 0,
        val readyPlayers: MutableSet<UUID> = mutableSetOf(),
        val readyCooldowns: MutableMap<UUID, Long> = mutableMapOf(),
        val breadcrumbUses: MutableMap<UUID, Int> = mutableMapOf(),
        val breadcrumbs: MutableList<Breadcrumb> = mutableListOf(),
        var lastBreadcrumbParticleTick: Long = 0L,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val sessions = mutableMapOf<String, Session>()
    private val legendaryGuarantees = mutableMapOf<UUID, Long>()
    private var nextLegendaryGuaranteeCleanupTick: Long = 0L

    fun grantLegendaryGuarantee(playerId: UUID) {
        legendaryGuarantees[playerId] = System.currentTimeMillis()
    }

    fun cleanupLegendaryGuarantees(server: MinecraftServer): Int {
        val playerManager = server.playerManager
        val now = System.currentTimeMillis()
        val config = DungeonConfig.instance
        val graceMinutes = config.legendaryGuaranteeGraceMinutes
        val graceMillis = if (graceMinutes > 0) graceMinutes * 60_000 else 0

        var removed = 0
        val iterator = legendaryGuarantees.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val expired = graceMillis > 0 && now - entry.value >= graceMillis
            if (expired) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    private fun consumeLegendaryGuarantees(members: Collection<UUID>): Boolean {
        if (members.isEmpty()) return false
        val guaranteedMembers = members.filter { legendaryGuarantees.containsKey(it) }.toSet()
        if (guaranteedMembers.isEmpty()) return false
        guaranteedMembers.forEach { legendaryGuarantees.remove(it) }
        return true
    }

    data class FreezeState(
        var freezeElapsedSeconds: Int = 0,
        var isFrozen: Boolean = false,
        var pauseUntilGameTime: Long = 0L,
        var thawUntilGameTime: Long = 0L,
        var thawImmunityUntil: Long = 0L,
        var hasFreezeClock: Boolean = false,
        var embrynsCooldownUntil: Long = 0L,
        var lastMilestoneOrdinal: Int = FreezeMilestone.NONE.ordinal,
        var lastClockDropWarningTick: Long = 0L,
        var accumulationRemainder: Double = 0.0
    )

    data class HeatState(
        var heatElapsedSeconds: Int = 0,
        var pauseUntilGameTime: Long = 0L,
        var immunityUntil: Long = 0L,
        var soothingCooldownUntil: Long = 0L,
        var lastMilestoneOrdinal: Int = HeatMilestone.NONE.ordinal,
        var hasHeatClock: Boolean = false,
        var accumulationRemainder: Double = 0.0
    )

    private enum class FreezeStage { HEATED, WARM, NEUTRAL, COLD, FREEZING, FROZEN }

    private enum class FreezeMilestone { NONE, SLOWNESS_I, SLOWNESS_II, FROZEN }

    private enum class HeatStage { COOL, NEUTRAL, WARM, HOT, BURNING }

    private enum class HeatMilestone { NONE, HEATED, BURNING }

    private const val FREEZE_TOTAL_SECONDS = 300
    private const val FREEZE_STAGE1_SECONDS = 150
    private const val FREEZE_STAGE2_SECONDS = 240
    private const val FREEZE_NEUTRAL_SECONDS = 120
    private const val FREEZE_HEAT_REGRESSION_PER_SECOND = 4
    private const val FREEZE_EMBRYNS_REGRESSION_SECONDS = 180
    private const val FREEZE_EMBRYNS_PAUSE_SECONDS = 120
    private const val FREEZE_EMBRYNS_COOLDOWN_SECONDS = 10
    private const val FREEZE_AUTO_THAW_SECONDS = 60
    private const val FREEZE_THAW_IMMUNITY_SECONDS = 4
    private const val HEAT_TOTAL_SECONDS = 300
    private const val HEAT_STAGE1_SECONDS = 60
    private const val HEAT_STAGE2_SECONDS = 150
    private const val HEAT_STAGE3_SECONDS = 240
    private const val HEAT_COOL_RATE_PER_SECOND = 1
    private const val HEAT_SOOTHING_REGRESSION_SECONDS = 180
    private const val HEAT_SOOTHING_PAUSE_SECONDS = 120
    private const val HEAT_SOOTHING_COOLDOWN_SECONDS = 10
    private const val ROOM_DOOR_CLOSE_DELAY_TICKS = 7L
    private const val RAID_ACTIVATION_TELEPORT_DELAY_TICKS = 8L
    private const val RAID_TELEPORT_DELAY_TICKS = 40L
    private const val RAID_DOOR_COOLDOWN_TICKS = 20L * 20
    private const val PARTY_SPAWN_UPDATE_DELAY_TICKS = 20L
    private const val SOLO_BUFF_DURATION_TICKS = 20 * 30
    private const val SOLO_BUFF_REFRESH_THRESHOLD_TICKS = 40
    private val HEAT_BLOCKS = setOf(
        Blocks.TORCH,
        Blocks.WALL_TORCH,
        Blocks.SOUL_TORCH,
        Blocks.SOUL_WALL_TORCH,
        Blocks.CAMPFIRE,
        Blocks.SOUL_CAMPFIRE,
        Blocks.SOUL_LANTERN,
        Blocks.LAVA,
        Blocks.BLACK_CANDLE,
        Blocks.BLACK_CANDLE_CAKE
    )
    private const val TITLE_STAGE1_JSON = "{\"text\":\"The cold creeps in...\",\"color\":\"aqua\"}"
    private const val TITLE_STAGE2_JSON = "{\"text\":\"Biting winds lash you\",\"color\":\"blue\",\"bold\":true}"
    private const val TITLE_FROZEN_JSON = "{\"text\":\"Frozen Solid!\",\"color\":\"white\",\"bold\":true}"
    private const val SUBTITLE_FREEZING_JSON = "{\"text\":\"Find a light source to heat you up!\"}"
    private const val SUBTITLE_FROZEN_JSON = "{\"text\":\"Have a teammate right click you to unfreeze you!\",\"color\":\"gray\"}"
    private const val TITLE_HEAT_STAGE1_JSON = "{\"text\":\"The heat rises...\",\"color\":\"gold\"}"
    private const val TITLE_HEAT_STAGE2_JSON = "{\"text\":\"Searing pain builds!\",\"color\":\"red\",\"bold\":true}"
    private const val TITLE_BURNING_JSON = "{\"text\":\"Burning!!\",\"color\":\"dark_red\",\"bold\":true}"
    private const val SUBTITLE_HEAT_JSON = "{\"text\":\"Cool off in water!\"}"
    private const val FREEZE_CLOCK_MARKER = "Dungeon Freeze Tracker"
    private const val EMBRYNS_DISPLAY_NAME = "Embryns"
    private const val EMBRYNS_MARKER = "Dungeon Embryns"
    private const val EMBRYNS_MODEL_DATA = 2003
    private const val SOOTHING_ICE_DISPLAY_NAME = "Soothing Ice"
    private const val SOOTHING_ICE_MARKER = "Dungeon Soothing Ice"
    private const val FLAME_SHIELD_DISPLAY_NAME = "Flame Shield"
    private const val FLAME_SHIELD_MARKER = "Dungeon Flame Shield"
    private const val CUSTOM_DATA_ROOT = "dungeons"
    private const val FLAME_SHIELD_TAG = "flame_shield"
    private const val BANNER_BLOCK_ENTITY_ID = "minecraft:banner"
    private const val BREADCRUMB_DISPLAY_NAME = "Enchanted Breadcrumbs"
    private const val BREADCRUMB_MARKER = "Dungeon Breadcrumbs"
    private const val BREADCRUMB_TAG = "breadcrumb"
    private const val BREADCRUMB_MAX_USES = 15
    private const val BREADCRUMB_LIFETIME_TICKS = 15 * 60 * 20L
    private const val BREADCRUMB_PARTICLE_INTERVAL_TICKS = 4L
    private const val MAX_SESSION_DURATION_MILLIS = 3 * 60 * 60 * 1000L
    private val FREEZE_CLOCK_NAMES = setOf(
        "Heated",
        "Warm",
        "Neutral",
        "Cold",
        "Freezing",
        "Frozen",
        "Cool",
        "Hot",
        "Burning!!"
    )
    private const val FREEZE_CLOCK_MODEL_DATA = 2004
    private const val FLAME_BODY_MODIFIER = "FLAME_BODY"
    private const val FLAME_BODY_ABILITY = "flamebody"

    private data class Scheduled(
        val world: ServerWorld,
        val executeAt: Long,
        val action: () -> Unit,
        val sessionId: String? = null
    )
    data class Breadcrumb(val standId: UUID, val expiresAt: Long)
    private val scheduled = mutableListOf<Scheduled>()

    fun getSession(name: String): Session? = sessions[name]

    private fun createDungeonBounds(dungeon: Dungeon): Box {
        val c1 = dungeon.corner1 ?: BlockPosDto(0, 0, 0)
        val c2 = dungeon.corner2 ?: c1
        val minX = min(c1.x, c2.x).toDouble()
        val minY = min(c1.y, c2.y).toDouble()
        val minZ = min(c1.z, c2.z).toDouble()
        val maxX = max(c1.x, c2.x) + 1.0
        val maxY = max(c1.y, c2.y) + 1.0
        val maxZ = max(c1.z, c2.z) + 1.0
        return Box(minX, minY, minZ, maxX, maxY, maxZ)
    }

    fun startSession(
        party: PartyService.Party,
        dungeon: Dungeon,
        scan: DungeonScanner.ScanResult.Success,
        world: ServerWorld
    ) {
        val rooms = computeRooms(scan)
        val players = party.order.toMutableSet()
        cleanupLegendaryGuarantees(world.server)
        val map = players.associateWith { null as Room? }.toMutableMap()
        val actions = mutableMapOf<Int, MutableList<RoomAction>>()
        rooms.forEachIndexed { idx, _ -> actions[idx] = mutableListOf() }
        val spawn = scan.spawn ?: BlockPos(0, 64, 0)
        val goldBlocks = scan.actionBlocks["gold"]?.distinct() ?: emptyList()
        val dispenserBlocks = scan.actionBlocks["dispenser"]?.distinct() ?: emptyList()
        val emeraldBlocks = scan.actionBlocks["emerald"]?.distinct() ?: emptyList()
        val bossData = locateBossRoom(dungeon, rooms, scan, goldBlocks)
        val spawnPoints = assignRaidSpawnPoints(rooms, goldBlocks, bossData?.roomIndex)
        val displayType = formatDungeonType(dungeon.type).ifBlank { dungeon.type }
        val defaultBossBarTitle = "$displayType Dungeon"
        val dungeonBossBar = createDungeonBossBar(dungeon)
        val defaultBossBarColor = dungeonBossBar.color
        val difficultySet = createDifficultySet(dungeon.type)
        val isIceDungeon = dungeon.type.equals("ice", ignoreCase = true)
        val isFireDungeon = dungeon.type.equals("fire", ignoreCase = true)
        val embrynsChestPlan = if (isIceDungeon) EmbrynsChestPlan().also { it.reset() } else null
        val soothingIceChestPlan = if (isFireDungeon) SoothingIceChestPlan().also { it.reset() } else null
        val lootTable = DungeonLootConfig.ensure(dungeon.type)
        val flameShieldChance = if (isFireDungeon) lootTable.flameShieldChance else 0.0
        val replacedEmeraldBlocks = replaceEmeraldsWithChests(
            world,
            emeraldBlocks,
            lootTable,
            embrynsChestPlan,
            soothingIceChestPlan,
            flameShieldChance
        )
        val (gildedChests, gildedChestPositions) = initializeGildedChests(world, rooms, scan.gildedChestMarkers)
        val bossCrafterState = removeBossCrafter(world, bossData?.spawnPos)
        val doorBlockState = resolveDoorBlockState(DungeonConfig.instance)
        val bossRoom = bossData?.roomIndex?.let { idx -> rooms.getOrNull(idx) }
        val bossRoomKey = bossData?.roomIndex?.let { DoorManager.RoomKey(dungeon.name, it) }
        val bossDoors = if (bossRoom != null && bossRoomKey != null) {
            val entranceMarkers = scan.actionBlocks["frontDoor"] ?: emptyList()
            val exitMarkers = scan.actionBlocks["finalDoor"] ?: emptyList()
            DoorManager.findDoorsInRoom(
                world = world,
                sessionId = dungeon.name,
                roomIndex = bossData.roomIndex,
                roomMin = bossRoom.min,
                roomMax = bossRoom.max,
                entranceMarkers = entranceMarkers,
                exitMarkers = exitMarkers,
                barrierState = doorBlockState
            ).associate { it.type to it.key }
        } else {
            emptyMap()
        }
        val lives = computeLivesForPartySize(players.size)
        val guaranteedLegendary = consumeLegendaryGuarantees(party.order)
        val legendaryRun = guaranteedLegendary || ThreadLocalRandom.current().nextDouble() < LEGENDARY_CHANCE
        val dungeonBounds = createDungeonBounds(dungeon)
        val leaderId = party.leader
        val session = Session(
            dungeon = dungeon,
            partyId = party.id,
            world = world,
            rooms = rooms,
            players = players,
            playerRooms = map,
            actions = actions,
            cooldowns = mutableMapOf(),
            spawn = spawn,
            partySpawn = spawn,
            difficulties = difficultySet,
            lives = lives,
            raidSpawnPoints = spawnPoints,
            removedGoldBlocks = goldBlocks.toSet(),
            replacedEmeraldBlocks = replacedEmeraldBlocks,
            gildedChests = gildedChests,
            dungeonBossBar = dungeonBossBar,
            bossRoomIndex = bossData?.roomIndex,
            bossSpawnPos = bossData?.spawnPos,
            bossCrafterState = bossCrafterState,
            bossGoldSpawnPoints = bossData?.goldSpawnPoints ?: emptyList(),
            bossRoomKey = bossRoomKey,
            bossDoors = bossDoors,
            bossAnchorPos = bossData?.anchorPos,
            defaultBossBarTitle = defaultBossBarTitle,
            defaultBossBarColor = defaultBossBarColor,
            isIceDungeon = isIceDungeon,
            isFireDungeon = isFireDungeon,
            dungeonBounds = dungeonBounds,
            nextHeatTick = world.time + 20,
            embrynsChestPlan = embrynsChestPlan,
            soothingIceChestPlan = soothingIceChestPlan,
            gildedChestPositions = gildedChestPositions,
            flameShieldChance = flameShieldChance,
            legendary = legendaryRun,
            soloRun = players.size == 1,
            instanceId = UUID.randomUUID(),
            leaderId = leaderId,
            leaderName = PartyService.lookupName(leaderId) ?: "Unknown",
            dimension = world.registryKey.value
        )
        val dungeonCorner1 = dungeon.corner1?.toBlockPos() ?: BlockPos(0, 0, 0)
        val dungeonCorner2 = dungeon.corner2?.toBlockPos() ?: dungeonCorner1
        session.chunkLease = ChunkPin.pinArea(world, dungeonCorner1, dungeonCorner2, session.instanceId)
        seedDispensers(world, dispenserBlocks)
        val server = world.server
        players.mapNotNull { server.playerManager.getPlayer(it) }.forEach { player ->
            dungeonBossBar.addPlayer(player)
            val command = "/runmolangscript cobblemon:dungeon_dev_reset ${player.gameProfile.name}"
            runCatching { server.commandManager.executeWithPrefix(server.commandSource, command) }
                .onFailure { error ->
                    println("[Dungeons] Failed to execute dungeon reset script for ${player.gameProfile.name}: ${error.message}")
                }
            session.breadcrumbUses[player.uuid] = BREADCRUMB_MAX_USES
            giveBreadcrumbs(player, BREADCRUMB_MAX_USES)
        }
        bossData?.spawnPos?.let { pos ->
            println("[Dungeons] Boss spawn for ${dungeon.name} located at ${pos.x},${pos.y},${pos.z} in room ${(bossData.roomIndex + 1)}.")
        }
        goldBlocks.forEach { pos -> world.setBlockState(pos, Blocks.AIR.defaultState) }
        sessions[dungeon.name] = session
        applySoloRunEffects(session)
        session.rooms.forEach { room ->
            openRoomDoors(world, room)
        }
        unlockBossDoor(world, session, DoorManager.DoorType.ENTRANCE)
        lockBossDoor(world, session, DoorManager.DoorType.EXIT)
        if (isIceDungeon) {
            players.mapNotNull { server.playerManager.getPlayer(it) }.forEach { member ->
                val state = session.freezeStates.getOrPut(member.uuid) { FreezeState() }
                ensureFreezeClock(member, state)
            }
        }
        if (isFireDungeon) {
            players.mapNotNull { server.playerManager.getPlayer(it) }.forEach { member ->
                val state = session.heatStates.getOrPut(member.uuid) { HeatState() }
                ensureHeatClock(member, state)
            }
        }
    }

    private fun computeLivesForPartySize(partySize: Int): Int {
        return when (partySize) {
            0, 1 -> 1
            2 -> 3
            else -> 5
        }
    }

    private fun applySoloRunEffects(session: Session) {
        if (!session.soloRun) return
        val server = session.world.server
        session.players
            .mapNotNull { server.playerManager.getPlayer(it) }
            .forEach { ensureSoloRunEffect(it) }
    }

    private fun updateSoloRunEffects(session: Session) {
        if (!session.soloRun || session.status == DungeonStatus.ENDED) return
        applySoloRunEffects(session)
    }

    private fun clearSoloRunEffects(session: Session) {
        if (!session.soloRun) return
        val server = session.world.server
        session.players
            .mapNotNull { server.playerManager.getPlayer(it) }
            .forEach { removeSoloRunEffects(it) }
    }

    private fun ensureSoloRunEffect(player: ServerPlayerEntity) {
        ensureSoloStatusEffect(player, StatusEffects.SPEED, 0)
        ensureSoloStatusEffect(player, StatusEffects.STRENGTH, 0)
    }

    private fun ensureSoloStatusEffect(
        player: ServerPlayerEntity,
        effect: RegistryEntry<StatusEffect>,
        amplifier: Int
    ) {
        val existing = player.getStatusEffect(effect)
        if (existing == null || existing.duration <= SOLO_BUFF_REFRESH_THRESHOLD_TICKS || existing.amplifier < amplifier) {
            player.addStatusEffect(
                StatusEffectInstance(effect, SOLO_BUFF_DURATION_TICKS, amplifier, false, false, true)
            )
        }
    }

    private fun removeSoloRunEffects(player: ServerPlayerEntity) {
        player.removeStatusEffect(StatusEffects.SPEED)
        player.removeStatusEffect(StatusEffects.STRENGTH)
    }

    fun endSession(name: String) {
        cancelScheduled(name)
        val session = sessions.remove(name) ?: return
        val releaseWorld = session.chunkLease?.let { lease ->
            session.world.server.getWorld(lease.worldKey) ?: session.world
        } ?: session.world
        session.status = DungeonStatus.ENDED
        clearSoloRunEffects(session)
        session.readyPlayers.clear()
        session.readyCooldowns.clear()
        clearTemperatureState(session)
        clearBreadcrumbs(session)
        resetSecretRoom(session)
        killAllPokemonInDungeon(session)
        session.rooms.forEachIndexed { index, room ->
            removeRaidEntities(session, session.world, index, room)
            openRoomDoors(session.world, room)
        }
        unlockBossDoor(session.world, session, DoorManager.DoorType.ENTRANCE)
        lockBossDoor(session.world, session, DoorManager.DoorType.EXIT)
        session.bossRoomKey?.let { DoorManager.forgetRoom(it) }
        restoreGoldBlocks(session)
        restoreBossCrafter(session)
        restoreEmeraldBlocks(session)
        cleanupUnresolvedGildedChests(session)
        clearBossBars(session)
        ChunkPin.releaseAll(releaseWorld, session.instanceId)
        session.chunkLease = null
    }

    private fun clearTemperatureState(session: Session) {
        val server = session.world.server
        session.players.forEach { uuid ->
            server.playerManager.getPlayer(uuid)?.let { player ->
                clearFrozenEffects(player)
                clearHeatEffects(player)
                removeThermometer(player)
            }
        }
        session.freezeStates.clear()
        session.heatStates.clear()
        session.freezeRateModifiers.clear()
        session.flameBodyPokemon.clear()
    }

    private fun clearBreadcrumbs(session: Session) {
        val world = session.world
        session.breadcrumbs.toList().forEach { crumb ->
            world.getEntity(crumb.standId)?.discard()
        }
        session.breadcrumbs.clear()
        val server = world.server
        session.players.mapNotNull { server.playerManager.getPlayer(it) }.forEach { player ->
            removeBreadcrumbs(player)
        }
        session.breadcrumbUses.clear()
    }

    private fun removeThermometer(player: ServerPlayerEntity) {
        val inventory = player.inventory
        for (slot in 0 until inventory.size()) {
            val stack = inventory.getStack(slot)
            if (isFreezeClock(stack)) {
                inventory.setStack(slot, ItemStack.EMPTY)
            }
        }
    }

    fun registerAction(
        dungeon: String,
        roomIndex: Int,
        action: RoomAction
    ) {
        sessions[dungeon]?.actions?.getOrPut(roomIndex) { mutableListOf() }?.add(action)
    }

    fun onTick(world: ServerWorld) {
        if (world.server.overworld == world && world.time >= nextLegendaryGuaranteeCleanupTick) {
            nextLegendaryGuaranteeCleanupTick = world.time + LEGENDARY_GUARANTEE_CLEANUP_INTERVAL_TICKS
            cleanupLegendaryGuarantees(world.server)
        }
        val cfg = DungeonConfig.instance
        val now = world.time
        val due = scheduled.filter { it.world == world && it.executeAt <= now }
        if (due.isNotEmpty()) {
            scheduled.removeAll(due)
            for (task in due) {
                task.action()
            }
        }
        val activeSessions = sessions.values.toList()
        for (session in activeSessions) {
            if (session.world == world) {
                if (maybeTerminateOvertimeSession(session)) {
                    continue
                }
                updateSoloRunEffects(session)
                val onlineParticipants = session.players
                    .mapNotNull { world.server.playerManager.getPlayer(it) }
                    .filter { it.serverWorld == world }
                if (onlineParticipants.isEmpty()) {
                    if (session.idleSince == null) {
                        session.idleSince = now
                    }
                    if (maybeTerminateIdleSession(session, now)) {
                        continue
                    }
                } else {
                    session.idleSince = null
                    onlineParticipants.forEach { player ->
                        checkPlayer(player, world, session)
                    }
                    handleFreezeTick(world, session, onlineParticipants)
                    handleHeatTick(world, session, onlineParticipants)
                    handleBreadcrumbs(session, world, now)
                    handleRaidWaves(world, session)
                    updateBossBars(world, session, onlineParticipants)
                }
                refreshTrackedAlphaz(session)
                handleBossEncounter(world, session)
            }
            val secretState = session.secretRoomState
            if (secretState != null && secretState.world == world) {
                handleSecretRoomTick(world, session, secretState)
            }
        }
    }

    fun maybeTerminateIdleSession(session: Session, now: Long): Boolean {
        val idleSince = session.idleSince ?: return false
        val timeoutTicks = DungeonGameplayConfig.instance().idle.terminationTicks()
        if (timeoutTicks <= 0) return false
        if (now - idleSince < timeoutTicks) return false
        endSession(session.dungeon.name)
        return true
    }

    private fun maybeTerminateOvertimeSession(session: Session): Boolean {
        if (session.status == DungeonStatus.ENDED) return false
        val elapsedMillis = System.currentTimeMillis() - session.createdAt
        if (elapsedMillis < MAX_SESSION_DURATION_MILLIS) return false
        val players = session.players
            .mapNotNull { session.world.server.playerManager.getPlayer(it) }
            .filter { it.serverWorld == session.world }
        val message = Text.literal("Dungeon run exceeded time limit. Resetting...").formatted(Formatting.RED)
        players.forEach { it.sendMessage(message, false) }
        val result = PartyService.concludeDungeonByName(session.dungeon.name, PartyService.PartyEndReason.TIMEOUT)
        if (result?.success != true) {
            endSession(session.dungeon.name)
        }
        return true
    }

    private fun refreshTrackedAlphaz(session: Session) {
        val world = session.world
        if (session.rooms.isEmpty()) return
        session.rooms.forEachIndexed { index, room ->
            val raidActive = session.raidStates[index]?.active == true
            val needsBossTracking = session.bossRoomIndex == index && session.bossStarted && !session.bossCompleted
            val hasTracked = room.trackedAlphaUuids.isNotEmpty()
            if (!raidActive && !needsBossTracking && !hasTracked) {
                return@forEachIndexed
            }
            val living = getTrackedAlphazInRoom(world, room)
            if (living.isEmpty()) {
                if (hasTracked) {
                    room.trackedAlphaUuids.clear()
                }
                if (raidActive) {
                    session.raidEntityUuids[index]?.clear()
                }
                if (needsBossTracking) {
                    session.bossEntityUuid = null
                }
                return@forEachIndexed
            }
            val uuids = living.mapTo(mutableSetOf()) { it.uuid }
            room.trackedAlphaUuids.retainAll(uuids)
            room.trackedAlphaUuids.addAll(uuids)
            if (raidActive) {
                val raidSet = session.raidEntityUuids.getOrPut(index) { mutableSetOf() }
                val raidUuids = living
                    .asSequence()
                    .filter { ALPHAZ_TAG in it.commandTags }
                    .map { it.uuid }
                    .toSet()
                raidSet.retainAll(raidUuids)
                raidSet.addAll(raidUuids)
            }
            if (needsBossTracking) {
                session.bossEntityUuid = living
                    .firstOrNull { ALPHAZ_BOSS_TAG in it.commandTags }
                    ?.uuid
            }
        }
    }

    private fun getTrackedAlphazInRoom(world: ServerWorld, room: Room): List<LivingEntity> {
        return world.getEntitiesByClass(LivingEntity::class.java, room.boundingBox) { living ->
            !living.isRemoved && living.isAlive &&
                    (ALPHAZ_TAG in living.commandTags || ALPHAZ_BOSS_TAG in living.commandTags)
        }
    }

    private fun handleBreadcrumbs(session: Session, world: ServerWorld, now: Long) {
        if (session.breadcrumbs.isEmpty()) return
        if (now - session.lastBreadcrumbParticleTick < BREADCRUMB_PARTICLE_INTERVAL_TICKS) return
        session.lastBreadcrumbParticleTick = now
        val iterator = session.breadcrumbs.iterator()
        while (iterator.hasNext()) {
            val crumb = iterator.next()
            val entity = world.getEntity(crumb.standId)
            if (entity == null || entity.isRemoved || !entity.isAlive) {
                iterator.remove()
                continue
            }
            if (now >= crumb.expiresAt) {
                entity.discard()
                iterator.remove()
                continue
            }
            world.spawnParticles(
                ParticleTypes.GLOW,
                entity.x,
                entity.y + 0.4,
                entity.z,
                1,
                0.05,
                0.0,
                0.05,
                0.0
            )
        }
    }

    private fun checkPlayer(player: ServerPlayerEntity, world: ServerWorld, session: Session) {
        val cfg = DungeonConfig.instance
        val pos = player.blockPos
        var found: Room? = null
        var idxFound = -1
        session.rooms.forEachIndexed { idx, room ->
            if (pos.x in room.min.x..room.max.x &&
                pos.y in room.min.y..room.max.y &&
                pos.z in room.min.z..room.max.z) {
                found = room
                idxFound = idx
                return@forEachIndexed
            }
        }
        if (found != null) {
            val current = session.playerRooms[player.uuid]
            if (current != found && canTrigger(session, player, idxFound, world.time)) {
                session.playerRooms[player.uuid] = found
                handleRoomEntry(session, world, player, idxFound)
            }
        } else {
            session.playerRooms[player.uuid] = null
        }
    }

    private fun handleRoomEntry(
        session: Session,
        world: ServerWorld,
        player: ServerPlayerEntity,
        roomIdx: Int
    ) {
        val room = session.rooms.getOrNull(roomIdx) ?: return
        if (!room.firstEntrants.contains(player.uuid)) {
            room.firstEntrants.add(player.uuid)
        }

        val raidState = session.raidSpawnPoints[roomIdx]
            ?.takeIf { it.isNotEmpty() }
            ?.let { session.raidStates.getOrPut(roomIdx) { RaidState() } }

        raidState?.let { state ->
            if (!state.active && !state.completed) {
                activateRaidRoom(session, world, roomIdx, room, player, state)
            } else if (state.active && state.participants.add(player.uuid)) {
                session.playerRaidRooms[player.uuid] = roomIdx
                state.maxWaves = determineRaidWaveLimit(state.participants.size)
            }
        }

        val isGimmighoulRoom = isGimmighoulRoom(session, roomIdx)
        val shouldCloseDoors = when {
            isGimmighoulRoom -> raidState?.active == true
            raidState != null -> raidState.completed == false
            else -> false
        }
        if (shouldCloseDoors) {
            scheduleRoomDoorClosure(session, world, roomIdx)
        } else {
            cancelPendingDoorClosure(session, roomIdx)
        }

        val ctx = ActionContext(world.server, player, session.dungeon, room)
        session.actions[roomIdx]?.forEach { it.run(ctx) }

        if (roomIdx == session.bossRoomIndex && !session.bossStarted) {
            handleBossRoomEntry(session, world, player)
        }
    }

    private fun activateRaidRoom(
        session: Session,
        world: ServerWorld,
        roomIdx: Int,
        room: Room,
        activator: ServerPlayerEntity,
        state: RaidState
    ) {
        val spawnPoints = session.raidSpawnPoints[roomIdx]
        if (!spawnPoints.isNullOrEmpty()) {
            scheduleRaidActivationTeleport(session, world, roomIdx, activator.uuid)
        }
        val previousParticipants = state.participants.toSet()
        val teleported = teleportNearbyRaidPlayers(session, world, activator, RAID_TELEPORT_DELAY_TICKS)
        val participants = mutableSetOf<UUID>()
        teleported.forEach { participants.add(it.uuid) }
        playersInRoom(session, roomIdx).forEach { participants.add(it.uuid) }
        if (participants.isEmpty()) {
            participants.add(activator.uuid)
        }

        state.participants.clear()
        state.participants.addAll(participants)
        state.dead.clear()
        state.active = true
        state.completed = false
        state.currentWave = 0
        state.waveInProgress = false
        state.nextCheckTick = world.time + GRACE_TICKS
        state.hardSpawned = 0
        state.mediumSpawned = 0
        state.maxWaves = determineRaidWaveLimit(state.participants.size)
        state.participants.forEach { uuid -> session.playerRaidRooms[uuid] = roomIdx }
        previousParticipants.forEach { uuid ->
            if (uuid !in state.participants && session.playerRaidRooms[uuid] == roomIdx) {
                session.playerRaidRooms.remove(uuid)
            }
        }

        schedulePartySpawnUpdate(
            session = session,
            world = world,
            roomIdx = roomIdx,
            preferredPlayerId = activator.uuid,
            fallbackPos = activator.blockPos
        )
    }

    private fun teleportNearbyRaidPlayers(
        session: Session,
        world: ServerWorld,
        activator: ServerPlayerEntity,
        delayTicks: Long = 0L
    ): List<ServerPlayerEntity> {
        val radiusSq = 13.0 * 13.0
        val activatorPos = activator.pos
        val yaw = activator.yaw
        val pitch = activator.pitch
        val activatorId = activator.uuid
        val server = world.server
        val result = mutableListOf<ServerPlayerEntity>()
        val targetIds = mutableListOf<UUID>()
        session.players.mapNotNull { server.playerManager.getPlayer(it) }
            .filter { it.serverWorld == world && it.isAlive && !it.isSpectator }
            .forEach { member ->
                if (member == activator || member.squaredDistanceTo(activator) <= radiusSq) {
                    result += member
                    targetIds += member.uuid
                }
            }
        if (targetIds.isEmpty()) return result
        if (delayTicks <= 0L) {
            result.forEach { member ->
                member.teleport(world, activatorPos.x, activatorPos.y, activatorPos.z, yaw, pitch)
            }
        } else {
            val sessionId = session.dungeon.name
            schedule(world, delayTicks, sessionId) {
                val current = sessions[sessionId] ?: return@schedule
                if (current !== session) return@schedule

                val activatorPlayer = server.playerManager.getPlayer(activatorId)
                    ?.takeIf { player ->
                        player.serverWorld == world && player.isAlive && !player.isSpectator
                    }
                val targetPos = activatorPlayer?.pos ?: activatorPos
                val targetYaw = activatorPlayer?.yaw ?: yaw
                val targetPitch = activatorPlayer?.pitch ?: pitch

                targetIds.mapNotNull { uuid -> server.playerManager.getPlayer(uuid) }
                    .filter { it.serverWorld == world && it.isAlive && !it.isSpectator }
                    .forEach { member ->
                        member.teleport(world, targetPos.x, targetPos.y, targetPos.z, targetYaw, targetPitch)
                    }
            }
        }
        return result
    }

    private fun selectRaidAnchor(session: Session, roomIdx: Int): BlockPos? {
        return session.raidSpawnPoints[roomIdx]
            ?.takeIf { it.isNotEmpty() }
            ?.random()
    }

    private fun teleportPlayersToRaidAnchor(
        world: ServerWorld,
        anchor: Vec3d,
        activator: ServerPlayerEntity?,
        playersAlreadyInRoom: List<ServerPlayerEntity>
    ) {
        val participants = mutableSetOf<ServerPlayerEntity>()
        if (activator != null) {
            participants += activator
        }
        participants += playersAlreadyInRoom
        participants.forEach { player ->
            if (player.serverWorld == world && player.isAlive && !player.isSpectator) {
                player.teleport(world, anchor.x, anchor.y, anchor.z, player.yaw, player.pitch)
            }
        }
    }

    private fun scheduleRaidActivationTeleport(
        session: Session,
        world: ServerWorld,
        roomIdx: Int,
        activatorId: UUID
    ) {
        val sessionId = session.dungeon.name
        schedule(world, RAID_ACTIVATION_TELEPORT_DELAY_TICKS, sessionId) {
            val currentSession = sessions[sessionId] ?: return@schedule
            if (currentSession !== session) return@schedule
            val anchor = selectRaidAnchor(currentSession, roomIdx) ?: return@schedule
            val anchorVec = Vec3d(anchor.x + 0.5, anchor.y + 1.0, anchor.z + 0.5)
            val activator = world.server.playerManager.getPlayer(activatorId)
            val players = playersInRoom(currentSession, roomIdx)
            teleportPlayersToRaidAnchor(world, anchorVec, activator, players)
        }
    }

    private fun schedulePartySpawnUpdate(
        session: Session,
        world: ServerWorld,
        roomIdx: Int,
        preferredPlayerId: UUID?,
        fallbackPos: BlockPos
    ) {
        val sessionId = session.dungeon.name
        schedule(world, PARTY_SPAWN_UPDATE_DELAY_TICKS, sessionId) {
            val current = sessions[sessionId] ?: return@schedule
            if (current !== session) return@schedule
            val room = current.rooms.getOrNull(roomIdx) ?: return@schedule
            val server = world.server
            val preferred = preferredPlayerId
                ?.let { server.playerManager.getPlayer(it) }
                ?.takeIf { player ->
                    player.serverWorld == world && isInsideRoom(player.blockPos, room)
                }
            val spawnPos = preferred?.blockPos ?: current.players
                .asSequence()
                .mapNotNull { uuid -> server.playerManager.getPlayer(uuid) }
                .firstOrNull { player ->
                    player.serverWorld == world && isInsideRoom(player.blockPos, room)
                }
                ?.blockPos ?: fallbackPos
            current.partySpawn = spawnPos
        }
    }

    private fun scheduleRoomDoorClosure(session: Session, world: ServerWorld, roomIdx: Int) {
        val room = session.rooms.getOrNull(roomIdx) ?: return
        if (room.doors.isEmpty()) return
        val executeAt = world.time + ROOM_DOOR_CLOSE_DELAY_TICKS
        session.pendingDoorClosures[roomIdx] = executeAt
        val sessionId = session.dungeon.name
        schedule(world, ROOM_DOOR_CLOSE_DELAY_TICKS, sessionId) {
            val currentSession = sessions[sessionId] ?: return@schedule
            val expected = currentSession.pendingDoorClosures[roomIdx]
            if (expected != executeAt) return@schedule
            if (!shouldKeepRoomDoorsClosed(currentSession, roomIdx)) {
                currentSession.pendingDoorClosures.remove(roomIdx)
                return@schedule
            }
            currentSession.pendingDoorClosures.remove(roomIdx)
            val targetRoom = currentSession.rooms.getOrNull(roomIdx) ?: return@schedule
            val doorState = resolveDoorBlockState(DungeonConfig.instance)
            val doorBlock = doorState.block
            targetRoom.doors.forEach { door ->
                applyDoorState(world, door, doorState) { current ->
                    current.isAir || current.isOf(Blocks.IRON_BARS) || current.isOf(doorBlock)
                }
            }
        }
    }

    private fun cancelPendingDoorClosure(session: Session, roomIdx: Int) {
        session.pendingDoorClosures.remove(roomIdx)
    }

    private fun shouldKeepRoomDoorsClosed(session: Session, roomIdx: Int): Boolean {
        val raidState = session.raidStates[roomIdx]
        val isGimmighoulRoom = isGimmighoulRoom(session, roomIdx)
        return when {
            isGimmighoulRoom -> raidState?.active == true
            raidState != null -> !raidState.completed
            else -> false
        }
    }

    private fun handleFreezeTick(
        world: ServerWorld,
        session: Session,
        onlinePlayers: List<ServerPlayerEntity>
    ) {
        if (!session.isIceDungeon) return
        if (onlinePlayers.isEmpty()) return
        val now = world.time
        if (session.nextFreezeTick > now) return
        session.nextFreezeTick = now + 20
        onlinePlayers.forEach { player ->
            val uuid = player.uuid
            val state = session.freezeStates.getOrPut(uuid) { FreezeState() }
            val inDungeon = isInsideDungeonBounds(player, session)
            if (!inDungeon) {
                if (state.isFrozen && state.thawUntilGameTime > 0 && now >= state.thawUntilGameTime) {
                    thawPlayer(world, player, state, now, passive = true)
                }
                clearFrozenEffects(player)
                updateClockName(player, state)
                validateFlameBodyModifier(session, player)
                return@forEach
            }
            ensureFreezeClock(player, state)
            val nearHeat = isNearHeatSource(world, player.blockPos)
            validateFlameBodyModifier(session, player)
            if (state.isFrozen) {
                if (nearHeat && state.freezeElapsedSeconds > 0) {
                    state.freezeElapsedSeconds = max(0, state.freezeElapsedSeconds - FREEZE_HEAT_REGRESSION_PER_SECOND)
                    state.accumulationRemainder = 0.0
                }
                if ((state.thawUntilGameTime > 0 && now >= state.thawUntilGameTime) || state.freezeElapsedSeconds < FREEZE_TOTAL_SECONDS) {
                    thawPlayer(world, player, state, now, passive = state.thawUntilGameTime > 0 && now >= state.thawUntilGameTime)
                } else {
                    applyFrozenEffects(player)
                    applyMilestoneEffects(world, player, state, now)
                    updateClockName(player, state)
                }
                return@forEach
            }
            val paused = now < state.pauseUntilGameTime || now < state.thawImmunityUntil
            if (nearHeat) {
                if (state.freezeElapsedSeconds > 0) {
                    state.freezeElapsedSeconds = max(0, state.freezeElapsedSeconds - FREEZE_HEAT_REGRESSION_PER_SECOND)
                    state.accumulationRemainder = 0.0
                }
            } else if (!paused && state.freezeElapsedSeconds < FREEZE_TOTAL_SECONDS) {
                accumulateFreeze(session, uuid, state)
            }
            enforceFlameBodyLimit(session, player, state)
            if (state.freezeElapsedSeconds >= FREEZE_TOTAL_SECONDS) {
                enterFrozenState(session, world, player, state, now)
            } else {
                applyMilestoneEffects(world, player, state, now)
                updateClockName(player, state)
            }
        }
    }

    private fun handleHeatTick(
        world: ServerWorld,
        session: Session,
        onlinePlayers: List<ServerPlayerEntity>
    ) {
        if (!session.isFireDungeon) return
        if (onlinePlayers.isEmpty()) return
        val now = world.time
        if (session.nextHeatTick > now) return
        session.nextHeatTick = now + 20
        onlinePlayers.forEach { player ->
            val uuid = player.uuid
            val state = session.heatStates.getOrPut(uuid) { HeatState() }
            val inDungeon = isInsideDungeonBounds(player, session)
            if (!inDungeon) {
                clearHeatEffects(player)
                updateHeatClockName(player, state)
                return@forEach
            }
            ensureHeatClock(player, state)
            val inCoolingWater = isInCoolingWater(world, player)
            val paused = now < state.pauseUntilGameTime || now < state.immunityUntil
            if (inCoolingWater) {
                if (state.heatElapsedSeconds > 0) {
                    state.heatElapsedSeconds = max(0, state.heatElapsedSeconds - HEAT_COOL_RATE_PER_SECOND)
                    state.accumulationRemainder = 0.0
                }
            } else if (!paused && state.heatElapsedSeconds < HEAT_TOTAL_SECONDS) {
                state.accumulationRemainder += 1.0
                val wholeSeconds = state.accumulationRemainder.toInt()
                if (wholeSeconds > 0) {
                    state.heatElapsedSeconds = min(HEAT_TOTAL_SECONDS, state.heatElapsedSeconds + wholeSeconds)
                    state.accumulationRemainder -= wholeSeconds
                }
            }
            if (state.heatElapsedSeconds >= HEAT_TOTAL_SECONDS) {
                applyBurningState(world, player, state, now)
            } else {
                applyHeatMilestoneEffects(world, player, state, now)
            }
            updateHeatClockName(player, state)
        }
    }

    private fun accumulateFreeze(session: Session, playerId: UUID, state: FreezeState) {
        val multiplier = freezeRateMultiplier(session, playerId).coerceAtLeast(0.0)
        if (multiplier <= 0.0) return
        state.accumulationRemainder += multiplier
        val wholeSeconds = state.accumulationRemainder.toInt()
        if (wholeSeconds > 0) {
            state.freezeElapsedSeconds = min(FREEZE_TOTAL_SECONDS, state.freezeElapsedSeconds + wholeSeconds)
            state.accumulationRemainder -= wholeSeconds
        }
    }

    private fun enforceFlameBodyLimit(session: Session, player: ServerPlayerEntity, state: FreezeState) {
        val pokemonId = session.flameBodyPokemon[player.uuid] ?: return
        if (state.freezeElapsedSeconds >= FREEZE_STAGE2_SECONDS) {
            state.freezeElapsedSeconds = min(state.freezeElapsedSeconds, FREEZE_STAGE2_SECONDS)
            state.accumulationRemainder = 0.0
            val removed = session.flameBodyPokemon.remove(player.uuid)
            val cleared = removeFreezeRateModifier(session, player.uuid, FLAME_BODY_MODIFIER)
            if (removed != null || cleared) {
                player.sendMessage(
                    Text.literal("Your Flame Body Pokémon retreats before you freeze solid!").formatted(Formatting.GOLD),
                    true
                )
            }
        }
    }

    private fun freezeRateMultiplier(session: Session, playerId: UUID): Double {
        val modifiers = session.freezeRateModifiers[playerId] ?: return 1.0
        return modifiers.values.fold(1.0) { acc, value -> acc * value }
    }

    private fun applyFreezeRateModifier(session: Session, playerId: UUID, key: String, multiplier: Double) {
        val clamped = multiplier.coerceAtLeast(0.0)
        val modifiers = session.freezeRateModifiers.getOrPut(playerId) { mutableMapOf() }
        modifiers[key] = clamped
    }

    private fun removeFreezeRateModifier(session: Session, playerId: UUID, key: String): Boolean {
        val modifiers = session.freezeRateModifiers[playerId] ?: return false
        val removed = modifiers.remove(key) != null
        if (modifiers.isEmpty()) {
            session.freezeRateModifiers.remove(playerId)
        }
        return removed
    }

    private fun validateFlameBodyModifier(session: Session, player: ServerPlayerEntity) {
        val pokemonId = session.flameBodyPokemon[player.uuid] ?: return
        val entity = session.world.getEntity(pokemonId)
        val stillValid = if (entity is LivingEntity) {
            entity.isAlive && !entity.isRemoved && ownsPokemon(entity, player.uuid)
        } else {
            false
        }
        if (!stillValid) {
            val removed = session.flameBodyPokemon.remove(player.uuid)
            val cleared = removeFreezeRateModifier(session, player.uuid, FLAME_BODY_MODIFIER)
            if (removed != null || cleared) {
                println("[Dungeons][FlameBody] removed on return/death player=${player.gameProfile.name}")
            }
        }
    }

    private fun ownsPokemon(entity: LivingEntity, ownerId: UUID): Boolean {
        val clazz = entity.javaClass
        val method = runCatching { clazz.getMethod("getOwnerUUID") }.getOrNull()
        val uuid = method?.let { runCatching { it.invoke(entity) as? UUID }.getOrNull() }
        return uuid == null || uuid == ownerId
    }

    fun onPokemonSent(player: ServerPlayerEntity, abilityName: String?, pokemonEntityId: UUID?) {
        val session = sessions.values.firstOrNull { it.players.contains(player.uuid) }
        if (session == null) {
            println("[Dungeons][FlameBody] ignored outside dungeon player=${player.gameProfile.name} reason=no_session")
            return
        }
        val inDungeon = player.serverWorld == session.world && isInsideDungeonBounds(player, session)
        if (!inDungeon) {
            val removedPokemon = session.flameBodyPokemon.remove(player.uuid)
            val cleared = removeFreezeRateModifier(session, player.uuid, FLAME_BODY_MODIFIER)
            if (removedPokemon != null || cleared) {
                println("[Dungeons][FlameBody] removed on return/death player=${player.gameProfile.name}")
            } else {
                println("[Dungeons][FlameBody] ignored outside dungeon player=${player.gameProfile.name} reason=out_of_bounds")
            }
            return
        }
        val normalized = abilityName?.lowercase()
        session.flameBodyPokemon.remove(player.uuid)
        removeFreezeRateModifier(session, player.uuid, FLAME_BODY_MODIFIER)
        if (normalized == FLAME_BODY_ABILITY && pokemonEntityId != null) {
            val multiplier = DungeonConfig.instance.flameBodyFreezeMultiplier
            applyFreezeRateModifier(session, player.uuid, FLAME_BODY_MODIFIER, multiplier)
            session.flameBodyPokemon[player.uuid] = pokemonEntityId
            println("[Dungeons][FlameBody] applied modifier player=${player.gameProfile.name} multiplier=$multiplier")
            player.sendMessage(
                Text.literal("The Flame Body from your Pokemon is slowing down your freezing!").formatted(Formatting.GOLD),
                true
            )
        } else {
            println(
                "[Dungeons][FlameBody] updated on switch player=${player.gameProfile.name} ability=${abilityName ?: "none"}"
            )
        }
    }

    private fun isInsideDungeonBounds(player: ServerPlayerEntity, session: Session): Boolean {
        if (player.serverWorld != session.world) return false
        return session.dungeonBounds.contains(player.pos)
    }

    private fun isNearHeatSource(world: ServerWorld, pos: BlockPos): Boolean {
        val radius = 3
        val mutable = BlockPos.Mutable()
        for (dx in -radius..radius) {
            for (dy in -1..1) {
                for (dz in -radius..radius) {
                    mutable.set(pos.x + dx, pos.y + dy, pos.z + dz)
                    val state = world.getBlockState(mutable)
                    val block = state.block
                    if (block !in HEAT_BLOCKS) continue
                    if (block is CampfireBlock && !state.get(CampfireBlock.LIT)) continue
                    if (block is CandleBlock && !state.get(CandleBlock.LIT)) continue
                    if (block is CandleCakeBlock && !state.get(CandleCakeBlock.LIT)) continue
                    return true
                }
            }
        }
        return false
    }

    private fun isInCoolingWater(world: ServerWorld, player: ServerPlayerEntity): Boolean {
        if (player.isTouchingWater || player.isSubmergedInWater || player.isInsideWaterOrBubbleColumn) return true
        val positions = listOf(player.blockPos, player.blockPos.down())
        positions.forEach { pos ->
            val state = world.getBlockState(pos)
            if (state.isOf(Blocks.WATER_CAULDRON)) return true
            val fluid = state.fluidState.fluid
            if (fluid === Fluids.WATER || fluid === Fluids.FLOWING_WATER) return true
        }

        val center = player.blockPos
        for (dx in -1..1) {
            for (dy in -1..1) {
                for (dz in -1..1) {
                    val checkPos = center.add(dx, dy, dz)
                    if (world.getBlockState(checkPos).isOf(Blocks.WATER_CAULDRON)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun enterFrozenState(
        session: Session,
        world: ServerWorld,
        player: ServerPlayerEntity,
        state: FreezeState,
        now: Long
    ) {
        state.freezeElapsedSeconds = FREEZE_TOTAL_SECONDS
        state.accumulationRemainder = 0.0
        state.isFrozen = true
        state.thawUntilGameTime = now + FREEZE_AUTO_THAW_SECONDS * 20
        state.lastMilestoneOrdinal = FreezeMilestone.FROZEN.ordinal
        applyFrozenEffects(player)
        updateClockName(player, state)
        PlayerEffects.sendTitle(player, TITLE_FROZEN_JSON, SUBTITLE_FROZEN_JSON, 10, 80, 10)
        player.sendMessage(Text.literal("You are frozen solid!").formatted(Formatting.RED, Formatting.BOLD), true)
        SoundService.playLocal(player, SoundEvents.BLOCK_GLASS_BREAK)
        val server = world.server
        val notification = Text.literal("${player.gameProfile.name} is frozen! Right click them to save them!")
            .formatted(Formatting.AQUA, Formatting.BOLD)
        session.players
            .mapNotNull { server.playerManager.getPlayer(it) }
            .filter { it.uuid != player.uuid }
            .forEach { member -> member.sendMessage(notification, false) }
    }

    private fun thawPlayer(
        world: ServerWorld,
        player: ServerPlayerEntity,
        state: FreezeState,
        now: Long,
        passive: Boolean,
        targetSeconds: Int = FREEZE_STAGE2_SECONDS
    ) {
        clearFrozenEffects(player)
        state.isFrozen = false
        state.thawUntilGameTime = 0L
        state.freezeElapsedSeconds = targetSeconds.coerceIn(0, FREEZE_TOTAL_SECONDS)
        state.accumulationRemainder = 0.0
        val immunityTicks = FREEZE_THAW_IMMUNITY_SECONDS * 20
        state.thawImmunityUntil = now + immunityTicks
        if (!passive) {
            state.pauseUntilGameTime = max(state.pauseUntilGameTime, now + immunityTicks)
        }
        state.lastMilestoneOrdinal = FreezeMilestone.SLOWNESS_II.ordinal
        SoundService.playLocal(player, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE)
        player.sendMessage(Text.literal("Warmth returns to your body!").formatted(Formatting.GREEN), true)
        applyMilestoneEffects(world, player, state, now)
        updateClockName(player, state)
    }

    private fun applyFrozenEffects(player: ServerPlayerEntity) {
        player.velocity = Vec3d.ZERO
        player.velocityModified = true
        player.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 120, 6, false, false, true))
        player.addStatusEffect(StatusEffectInstance(StatusEffects.WEAKNESS, 120, 9, false, false, true))
        player.addStatusEffect(StatusEffectInstance(StatusEffects.MINING_FATIGUE, 120, 5, false, false, true))
        player.addStatusEffect(StatusEffectInstance(StatusEffects.BLINDNESS, 120, 0, false, false, true))
        player.setFrozenTicks(140)
    }

    private fun clearFrozenEffects(player: ServerPlayerEntity) {
        player.removeStatusEffect(StatusEffects.SLOWNESS)
        player.removeStatusEffect(StatusEffects.WEAKNESS)
        player.removeStatusEffect(StatusEffects.MINING_FATIGUE)
        player.removeStatusEffect(StatusEffects.BLINDNESS)
        player.setFrozenTicks(0)
    }

    private fun clearHeatEffects(player: ServerPlayerEntity) {
        player.removeStatusEffect(StatusEffects.SLOWNESS)
        player.removeStatusEffect(StatusEffects.WEAKNESS)
        player.extinguish()
    }

    private fun applyMilestoneEffects(world: ServerWorld, player: ServerPlayerEntity, state: FreezeState, now: Long) {
        val previousMilestone = FreezeMilestone.values()[state.lastMilestoneOrdinal]
        val milestone = when {
            state.isFrozen || state.freezeElapsedSeconds >= FREEZE_TOTAL_SECONDS -> FreezeMilestone.FROZEN
            state.freezeElapsedSeconds >= FREEZE_STAGE2_SECONDS -> FreezeMilestone.SLOWNESS_II
            state.freezeElapsedSeconds >= FREEZE_STAGE1_SECONDS -> FreezeMilestone.SLOWNESS_I
            else -> FreezeMilestone.NONE
        }
        if (milestone != previousMilestone && milestone.ordinal > previousMilestone.ordinal) {
            when (milestone) {
                FreezeMilestone.SLOWNESS_I -> {
                    PlayerEffects.sendTitle(player, TITLE_STAGE1_JSON, null, 10, 60, 10)
                    player.sendMessage(Text.literal("You feel colder...").formatted(Formatting.AQUA), true)
                }
                FreezeMilestone.SLOWNESS_II -> {
                    PlayerEffects.sendTitle(player, TITLE_STAGE2_JSON, SUBTITLE_FREEZING_JSON, 10, 60, 10)
                    player.sendMessage(Text.literal("Your limbs stiffen...").formatted(Formatting.BLUE), true)
                }
                else -> Unit
            }
        }
        state.lastMilestoneOrdinal = milestone.ordinal
        when (milestone) {
            FreezeMilestone.NONE -> player.removeStatusEffect(StatusEffects.SLOWNESS)
            FreezeMilestone.SLOWNESS_I -> player.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 100, 0, false, false, true))
            FreezeMilestone.SLOWNESS_II -> player.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 100, 1, false, false, true))
            FreezeMilestone.FROZEN -> Unit
        }
    }

    private fun applyHeatMilestoneEffects(world: ServerWorld, player: ServerPlayerEntity, state: HeatState, now: Long) {
        val previousMilestone = HeatMilestone.values()[state.lastMilestoneOrdinal]
        val milestone = when {
            state.heatElapsedSeconds >= HEAT_TOTAL_SECONDS -> HeatMilestone.BURNING
            state.heatElapsedSeconds >= HEAT_STAGE3_SECONDS -> HeatMilestone.HEATED
            else -> HeatMilestone.NONE
        }
        if (milestone != previousMilestone && milestone.ordinal > previousMilestone.ordinal) {
            when (milestone) {
                HeatMilestone.HEATED -> {
                    PlayerEffects.sendTitle(player, TITLE_HEAT_STAGE1_JSON, SUBTITLE_HEAT_JSON, 10, 60, 10)
                    player.sendMessage(Text.literal("The air grows hotter...").formatted(Formatting.GOLD), true)
                }
                HeatMilestone.BURNING -> {
                    PlayerEffects.sendTitle(player, TITLE_BURNING_JSON, SUBTITLE_HEAT_JSON, 10, 60, 10)
                    player.sendMessage(Text.literal("You are burning up!").formatted(Formatting.RED), true)
                }
                else -> Unit
            }
        }
        state.lastMilestoneOrdinal = milestone.ordinal
        when (milestone) {
            HeatMilestone.NONE -> {
                player.removeStatusEffect(StatusEffects.SLOWNESS)
                player.removeStatusEffect(StatusEffects.WEAKNESS)
                player.extinguish()
            }
            HeatMilestone.HEATED -> {
                player.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 100, 0, false, false, true))
                player.addStatusEffect(StatusEffectInstance(StatusEffects.WEAKNESS, 100, 0, false, false, true))
                player.extinguish()
            }
            HeatMilestone.BURNING -> {
                player.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 100, 1, false, false, true))
                player.addStatusEffect(StatusEffectInstance(StatusEffects.WEAKNESS, 100, 1, false, false, true))
            }
        }
    }

    private fun applyBurningState(world: ServerWorld, player: ServerPlayerEntity, state: HeatState, now: Long) {
        state.heatElapsedSeconds = HEAT_TOTAL_SECONDS
        applyHeatMilestoneEffects(world, player, state, now)
        player.setOnFireFor(15.0f)
    }

    private fun ensureFreezeClock(player: ServerPlayerEntity, state: FreezeState) {
        removeExtraFreezeClocks(player)
        val entry = findFreezeClock(player)
        val inventory = player.inventory
        if (entry == null) {
            val stack = ItemStack(Items.PAPER)
            applyFreezeClockComponents(stack, "Heated")
            if (!inventory.insertStack(stack)) {
                player.giveItemStack(stack)
            }
        } else {
            val (slot, stack) = entry
            if (!stack.isOf(Items.PAPER) || !hasCustomModelData(stack, FREEZE_CLOCK_MODEL_DATA)) {
                val replacement = ItemStack(Items.PAPER)
                val display = freezeClockDisplayName(stack) ?: "Heated"
                applyFreezeClockComponents(replacement, display)
                inventory.setStack(slot, replacement)
            }
        }
        state.hasFreezeClock = true
        updateClockName(player, state)
    }

    private fun updateClockName(player: ServerPlayerEntity, state: FreezeState) {
        val entry = findFreezeClock(player)
        if (entry == null) {
            state.hasFreezeClock = false
            return
        }
        val stage = computeStage(state)
        val display = when (stage) {
            FreezeStage.HEATED -> "Heated"
            FreezeStage.WARM -> "Warm"
            FreezeStage.NEUTRAL -> "Neutral"
            FreezeStage.COLD -> "Cold"
            FreezeStage.FREEZING -> "Freezing"
            FreezeStage.FROZEN -> "Frozen"
        }
        val stack = entry.second
        applyFreezeClockComponents(stack, display)
    }

    private fun computeStage(state: FreezeState): FreezeStage {
        if (state.isFrozen) return FreezeStage.FROZEN
        return when {
            state.freezeElapsedSeconds >= FREEZE_TOTAL_SECONDS -> FreezeStage.FROZEN
            state.freezeElapsedSeconds >= FREEZE_STAGE2_SECONDS -> FreezeStage.FREEZING
            state.freezeElapsedSeconds >= FREEZE_STAGE1_SECONDS -> FreezeStage.COLD
            state.freezeElapsedSeconds >= FREEZE_NEUTRAL_SECONDS -> FreezeStage.NEUTRAL
            state.freezeElapsedSeconds >= 60 -> FreezeStage.WARM
            else -> FreezeStage.HEATED
        }
    }

    private fun ensureHeatClock(player: ServerPlayerEntity, state: HeatState) {
        removeExtraFreezeClocks(player)
        val entry = findFreezeClock(player)
        val inventory = player.inventory
        if (entry == null) {
            val stack = ItemStack(Items.PAPER)
            applyFreezeClockComponents(stack, "Cool")
            if (!inventory.insertStack(stack)) {
                player.giveItemStack(stack)
            }
        } else {
            val (slot, stack) = entry
            if (!stack.isOf(Items.PAPER) || !hasCustomModelData(stack, FREEZE_CLOCK_MODEL_DATA)) {
                val replacement = ItemStack(Items.PAPER)
                val display = freezeClockDisplayName(stack) ?: "Cool"
                applyFreezeClockComponents(replacement, display)
                inventory.setStack(slot, replacement)
            }
        }
        state.hasHeatClock = true
        updateHeatClockName(player, state)
    }

    private fun updateHeatClockName(player: ServerPlayerEntity, state: HeatState) {
        val entry = findFreezeClock(player)
        if (entry == null) {
            state.hasHeatClock = false
            return
        }
        val stage = computeHeatStage(state)
        val display = when (stage) {
            HeatStage.COOL -> "Cool"
            HeatStage.NEUTRAL -> "Neutral"
            HeatStage.WARM -> "Warm"
            HeatStage.HOT -> "Hot"
            HeatStage.BURNING -> "Burning!!"
        }
        val stack = entry.second
        applyFreezeClockComponents(stack, display)
    }

    private fun computeHeatStage(state: HeatState): HeatStage {
        return when {
            state.heatElapsedSeconds >= HEAT_TOTAL_SECONDS -> HeatStage.BURNING
            state.heatElapsedSeconds >= HEAT_STAGE3_SECONDS -> HeatStage.HOT
            state.heatElapsedSeconds >= HEAT_STAGE2_SECONDS -> HeatStage.WARM
            state.heatElapsedSeconds >= HEAT_STAGE1_SECONDS -> HeatStage.NEUTRAL
            else -> HeatStage.COOL
        }
    }

    private fun freezeClockLore(): LoreComponent {
        val line = Text.literal(FREEZE_CLOCK_MARKER).formatted(Formatting.DARK_AQUA, Formatting.ITALIC)
        return LoreComponent(listOf(line))
    }

    private fun embrynsLore(): LoreComponent {
        val line = Text.literal(EMBRYNS_MARKER).formatted(Formatting.DARK_AQUA, Formatting.ITALIC)
        return LoreComponent(listOf(line))
    }

    private fun soothingIceLore(): LoreComponent {
        val line = Text.literal(SOOTHING_ICE_MARKER).formatted(Formatting.AQUA, Formatting.ITALIC)
        return LoreComponent(listOf(line))
    }

    private fun freezeClockDisplayName(stack: ItemStack): String? {
        val display = (stack.get(DataComponentTypes.CUSTOM_NAME) ?: stack.name).string.replace("§", "")
        return display.takeIf { it in FREEZE_CLOCK_NAMES }
    }

    private fun hasCustomModelData(stack: ItemStack, expected: Int): Boolean {
        val component = stack.get(DataComponentTypes.CUSTOM_MODEL_DATA) ?: return false
        return component.value == expected
    }

    private fun applyFreezeClockComponents(stack: ItemStack, display: String) {
        val name = if (display in FREEZE_CLOCK_NAMES) display else "Heated"
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name).formatted(Formatting.GOLD, Formatting.BOLD))
        stack.set(DataComponentTypes.LORE, freezeClockLore())
        stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelDataComponent(FREEZE_CLOCK_MODEL_DATA))
    }

    private fun removeExtraFreezeClocks(player: ServerPlayerEntity) {
        var found = false
        val inventory = player.inventory
        for (slot in 0 until inventory.size()) {
            val stack = inventory.getStack(slot)
            if (isFreezeClock(stack)) {
                if (!found) {
                    found = true
                } else {
                    inventory.setStack(slot, ItemStack.EMPTY)
                }
            }
        }
    }

    private fun findFreezeClock(player: ServerPlayerEntity): Pair<Int, ItemStack>? {
        val inventory = player.inventory
        for (slot in 0 until inventory.size()) {
            val stack = inventory.getStack(slot)
            if (isFreezeClock(stack)) {
                return slot to stack
            }
        }
        return null
    }

    @JvmStatic
    fun shouldPreventFreezeClockDrop(player: ServerPlayerEntity, stack: ItemStack): Boolean {
        if (!isFreezeClock(stack)) return false
        val session = sessions.values.firstOrNull { it.world == player.serverWorld && it.players.contains(player.uuid) }
            ?: return false
        if (!session.isIceDungeon && !session.isFireDungeon) return false
        val freezeState = session.freezeStates.getOrPut(player.uuid) { FreezeState() }
        val heatState = session.heatStates.getOrPut(player.uuid) { HeatState() }
        if (session.isIceDungeon) freezeState.hasFreezeClock = true
        if (session.isFireDungeon) heatState.hasHeatClock = true
        val now = player.serverWorld.time
        if (now - freezeState.lastClockDropWarningTick >= 20) {
            player.sendMessage(Text.literal("The temperature tracker refuses to leave your hand.").formatted(Formatting.AQUA), true)
            freezeState.lastClockDropWarningTick = now
        }
        return true
    }

    private fun isFreezeClock(stack: ItemStack): Boolean {
        if (stack.isEmpty || !(stack.isOf(Items.PAPER) || stack.isOf(Items.CLOCK))) return false
        val displayName = freezeClockDisplayName(stack) ?: return false
        val lore = stack.get(DataComponentTypes.LORE)
        val marker = lore?.lines?.any { it.string == FREEZE_CLOCK_MARKER } == true
        if (!marker) return false
        return stack.isOf(Items.CLOCK) || hasCustomModelData(stack, FREEZE_CLOCK_MODEL_DATA)
    }

    private fun createEmbrynsStack(count: Int): ItemStack {
        val stack = ItemStack(Items.PAPER, count.coerceAtLeast(1))
        stack.set(
            DataComponentTypes.CUSTOM_NAME,
            Text.literal(EMBRYNS_DISPLAY_NAME).formatted(Formatting.GOLD, Formatting.BOLD)
        )
        stack.set(DataComponentTypes.LORE, embrynsLore())
        stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelDataComponent(EMBRYNS_MODEL_DATA))
        return stack
    }
    private fun isEmbryns(stack: ItemStack): Boolean {
        if (stack.isEmpty || !stack.isOf(Items.PAPER)) return false
        val customName = stack.get(DataComponentTypes.CUSTOM_NAME)?.string
        val raw = (customName ?: stack.name.string).replace("§", "")
        if (!raw.equals(EMBRYNS_DISPLAY_NAME, ignoreCase = true)) return false
        val lore = stack.get(DataComponentTypes.LORE)
        return lore?.lines?.any { it.string == EMBRYNS_MARKER } == true
    }

    private fun createSoothingIceStack(count: Int): ItemStack {
        val stack = ItemStack(Items.BLUE_ICE, count.coerceAtLeast(1))
        stack.set(
            DataComponentTypes.CUSTOM_NAME,
            Text.literal(SOOTHING_ICE_DISPLAY_NAME).formatted(Formatting.AQUA, Formatting.BOLD)
        )
        stack.set(DataComponentTypes.LORE, soothingIceLore())
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true)
        return stack
    }

    private fun isSoothingIce(stack: ItemStack): Boolean {
        if (stack.isEmpty || !stack.isOf(Items.BLUE_ICE)) return false
        val customName = stack.get(DataComponentTypes.CUSTOM_NAME)?.string
        val raw = (customName ?: stack.name.string).replace("§", "")
        if (!raw.equals(SOOTHING_ICE_DISPLAY_NAME, ignoreCase = true)) return false
        val lore = stack.get(DataComponentTypes.LORE)
        return lore?.lines?.any { it.string == SOOTHING_ICE_MARKER } == true
    }

    private fun createFlameShieldBannerTag(): NbtCompound {
        val tag = NbtCompound()
        tag.putString("id", BANNER_BLOCK_ENTITY_ID)
        tag.putInt("Base", DyeColor.RED.id)

        val patterns = NbtList()
        val layers = listOf(
            "flo" to DyeColor.YELLOW.id,
            "gra" to DyeColor.GRAY.id,
            "moj" to DyeColor.RED.id,
            "mr" to DyeColor.ORANGE.id,
            "mc" to DyeColor.YELLOW.id,
            "bri" to DyeColor.BLACK.id
        )

        layers.forEach { (pattern, color) ->
            val layer = NbtCompound()
            layer.putString("Pattern", pattern)
            layer.putInt("Color", color)
            patterns.add(layer)
        }

        tag.put("Patterns", patterns)
        return tag
    }

    private fun createFlameShieldStack(): ItemStack {
        val stack = ItemStack(Items.SHIELD)
        val marker = NbtCompound()
        marker.putByte(FLAME_SHIELD_TAG, 1)
        val customData = NbtCompound()
        customData.put(CUSTOM_DATA_ROOT, marker)
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(customData))
        stack.set(DataComponentTypes.BLOCK_ENTITY_DATA, NbtComponent.of(createFlameShieldBannerTag()))
        sanitizeBannerPatternData(stack, null)
        stack.set(
            DataComponentTypes.CUSTOM_NAME,
            Text.literal(FLAME_SHIELD_DISPLAY_NAME).formatted(Formatting.RED, Formatting.BOLD)
        )
        stack.set(DataComponentTypes.LORE, LoreComponent(listOf(Text.literal(FLAME_SHIELD_MARKER).formatted(Formatting.GOLD, Formatting.ITALIC))))
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true)
        return stack
    }

    // I kept WorldEdit from choking on missing banner ids; bloo made this mod!! :3
    private fun sanitizeBannerPatternData(stack: ItemStack, holder: ServerPlayerEntity?): Boolean {
        val component = stack.get(DataComponentTypes.BLOCK_ENTITY_DATA) ?: return false
        val data = component.nbt
        val hasPatternData = data.contains("Patterns") || data.contains("Base")
        if (!hasPatternData) return false

        val hasValidId = data.contains("id", NbtElement.STRING_TYPE.toInt()) && data.getString("id").isNotBlank()
        if (hasValidId) return false

        val repaired = data.copy()
        repaired.putString("id", BANNER_BLOCK_ENTITY_ID)
        stack.set(DataComponentTypes.BLOCK_ENTITY_DATA, NbtComponent.of(repaired))

        val id = Registries.ITEM.getId(stack.item)
        val ownerLabel = holder?.let { "${it.gameProfile.name} (${it.uuid})" } ?: "unknown"
        println("[Dungeons][BannerFix] Added missing banner id for $ownerLabel item=$id to keep WorldEdit happy.")
        return true
    }

    fun repairBannerItems(player: ServerPlayerEntity): Int {
        var repaired = 0
        val inv = player.inventory
        for (slot in 0 until inv.size()) {
            val stack = inv.getStack(slot)
            if (sanitizeBannerPatternData(stack, player)) {
                inv.setStack(slot, stack)
                repaired++
            }
        }
        return repaired
    }

    fun createDebugBannerStack(): ItemStack {
        val stack = ItemStack(Items.WHITE_BANNER)
        stack.set(DataComponentTypes.BLOCK_ENTITY_DATA, NbtComponent.of(createFlameShieldBannerTag()))
        sanitizeBannerPatternData(stack, null)
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Debug Flame Banner").formatted(Formatting.RED))
        return stack
    }

    fun grantFlameShield(player: ServerPlayerEntity): Boolean {
        val stack = createFlameShieldStack()
        val inserted = player.inventory.insertStack(stack)
        if (!inserted) {
            player.giveItemStack(stack)
        }
        return true
    }

    private fun isFlameShield(stack: ItemStack): Boolean {
        if (stack.isEmpty || !stack.isOf(Items.SHIELD)) return false
        val customData = stack.get(DataComponentTypes.CUSTOM_DATA)?.nbt
        val marker = customData?.getCompound(CUSTOM_DATA_ROOT)
        if (marker?.getByte(FLAME_SHIELD_TAG) == 1.toByte()) return true
        val raw = (stack.get(DataComponentTypes.CUSTOM_NAME)?.string ?: stack.name.string).replace("§", "")
        if (!raw.equals(FLAME_SHIELD_DISPLAY_NAME, ignoreCase = true)) return false
        val lore = stack.get(DataComponentTypes.LORE)
        return lore?.lines?.any { it.string == FLAME_SHIELD_MARKER } == true
    }

    private fun createBreadcrumbStack(usesLeft: Int = BREADCRUMB_MAX_USES): ItemStack {
        val stack = ItemStack(Items.BREAD)
        stack.set(
            DataComponentTypes.CUSTOM_NAME,
            Text.literal(BREADCRUMB_DISPLAY_NAME).formatted(Formatting.GOLD, Formatting.BOLD)
        )
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true)
        updateBreadcrumbLore(stack, usesLeft.coerceIn(0, BREADCRUMB_MAX_USES))
        return stack
    }

    private fun updateBreadcrumbLore(stack: ItemStack, usesLeft: Int) {
        val lore = listOf(
            Text.literal(BREADCRUMB_MARKER).formatted(Formatting.YELLOW, Formatting.ITALIC),
            Text.literal("Uses remaining: ${usesLeft.coerceAtLeast(0)}").formatted(Formatting.GRAY)
        )
        stack.set(DataComponentTypes.LORE, LoreComponent(lore))
    }

    private fun isBreadcrumbStack(stack: ItemStack): Boolean {
        if (stack.isEmpty || !stack.isOf(Items.BREAD)) return false
        val raw = (stack.get(DataComponentTypes.CUSTOM_NAME)?.string ?: stack.name.string).replace("§", "")
        if (!raw.equals(BREADCRUMB_DISPLAY_NAME, ignoreCase = true)) return false
        val lore = stack.get(DataComponentTypes.LORE)
        return lore?.lines?.any { it.string == BREADCRUMB_MARKER } == true
    }

    private fun giveBreadcrumbs(player: ServerPlayerEntity, usesLeft: Int) {
        val stack = createBreadcrumbStack(usesLeft)
        val inserted = player.inventory.insertStack(stack)
        if (!inserted) {
            player.giveItemStack(stack)
        }
    }

    private fun removeBreadcrumbs(player: ServerPlayerEntity) {
        val inv = player.inventory
        for (slot in 0 until inv.size()) {
            val stack = inv.getStack(slot)
            if (isBreadcrumbStack(stack)) {
                inv.setStack(slot, ItemStack.EMPTY)
            }
        }
    }

    private fun ensureBreadcrumbItem(session: Session, player: ServerPlayerEntity) {
        val uses = session.breadcrumbUses.getOrPut(player.uuid) { BREADCRUMB_MAX_USES }
        if (uses <= 0) {
            removeBreadcrumbs(player)
            return
        }
        var found = false
        val inv = player.inventory
        for (slot in 0 until inv.size()) {
            val stack = inv.getStack(slot)
            if (isBreadcrumbStack(stack)) {
                updateBreadcrumbLore(stack, uses)
                found = true
            }
        }
        if (!found) {
            giveBreadcrumbs(player, uses)
        }
    }

    private fun canTrigger(session: Session, player: ServerPlayerEntity, roomIdx: Int, time: Long): Boolean {
        val cdMap = session.cooldowns.getOrPut(player.uuid) { mutableMapOf() }
        val last = cdMap[roomIdx] ?: 0L
        if (time - last < 20) return false
        cdMap[roomIdx] = time
        return true
    }

    fun openDoors(player: ServerPlayerEntity): Boolean {
        val session = sessions.values.find { player.uuid in it.players } ?: return false
        val room = session.playerRooms[player.uuid] ?: return false
        val roomIdx = session.rooms.indexOf(room)
        openRoomDoors(player.serverWorld, room)
        if (roomIdx != -1) {
            clearRaidState(session, roomIdx)
        }
        return true
    }

    fun onDisconnect(player: ServerPlayerEntity) {
        val session = sessions.values.firstOrNull { player.uuid in it.players } ?: return
        session.readyPlayers.remove(player.uuid)
        session.readyCooldowns.remove(player.uuid)

        val raidIdx = session.playerRaidRooms[player.uuid]
        if (raidIdx != null) {
            val room = session.rooms.getOrNull(raidIdx)
            val state = session.raidStates[raidIdx]
            if (state != null && state.active && raidIdx != session.bossRoomIndex) {
                finishRaid(session, raidIdx, session.world, state, forceCleanup = true)
                return
            }
            if (room != null) {
                session.playerRaidRooms.remove(player.uuid)
                cancelPendingDoorClosure(session, raidIdx)
                openRoomDoors(session.world, room)
            }
            return
        }

        val room = session.playerRooms[player.uuid]
            ?: session.rooms.firstOrNull { isInsideRoom(player.blockPos, it) }
        if (room != null) {
            val idx = session.rooms.indexOf(room)
            if (idx != -1) {
                cancelPendingDoorClosure(session, idx)
            }
            openRoomDoors(session.world, room)
        }
    }

    fun onRespawn(old: ServerPlayerEntity, new: ServerPlayerEntity) {
        println("[Dungeons] playerRespawned ${new.gameProfile.name}")
        val party = PartyService.getPartyOf(new.uuid) ?: return
        val dungeonName = party.activeDungeon ?: return
        val session = sessions[dungeonName] ?: return
        val secretState = session.secretRoomState
        if (secretState != null && secretState.phase != SecretRoomPhase.COMPLETED) {
            handleSecretRespawn(session, secretState, new)
            if (secretState.phase == SecretRoomPhase.CAPTURE && secretState.pendingGuiPlayers.remove(new.uuid)) {
                new.server.execute { openSecretCaptureGui(session, secretState, new) }
            }
            return
        }
        handleRaidDeath(session, new.uuid, old.serverWorld)
        replaceBossBarPlayer(session, old, new)
        session.lives--
        val displayLives = session.lives.coerceAtLeast(0)
        broadcastLivesUpdate(session, displayLives)
        if (session.isIceDungeon) {
            val state = session.freezeStates.getOrPut(new.uuid) { FreezeState() }
            ensureFreezeClock(new, state)
        }
        if (session.isFireDungeon) {
            val state = session.heatStates.getOrPut(new.uuid) { HeatState() }
            ensureHeatClock(new, state)
            updateHeatClockName(new, state)
        }
        ensureBreadcrumbItem(session, new)
        if (session.lives >= 0) {
            val world = session.world
            val teammates = session.players
                .asSequence()
                .mapNotNull { id ->
                    if (id == new.uuid) return@mapNotNull null
                    val teammate = new.server.playerManager.getPlayer(id) ?: return@mapNotNull null
                    if (teammate.serverWorld != world || !teammate.isAlive || teammate.isSpectator) return@mapNotNull null
                    teammate
                }
                .toList()

            val spawnPos = session.partySpawn
            var targetX = spawnPos.x + 0.5
            var targetY = (spawnPos.y + 1).toDouble()
            var targetZ = spawnPos.z + 0.5

            val teammate = teammates.randomOrNull()
            if (teammate != null) {
                targetX = teammate.x
                targetY = teammate.y
                targetZ = teammate.z
            }

            new.teleport(world, targetX, targetY, targetZ, new.yaw, new.pitch)
        } else {
            val players = session.players.mapNotNull { new.server.playerManager.getPlayer(it) }
                .filter { it.serverWorld == session.world }
            killAllPokemonInDungeon(session)
            val titleJson = "{\"text\":\"Dungeon Lost...\",\"bold\":true,\"color\":\"dark_red\"}"
            SoundService.playToPlayers(players, SoundEvents.ENTITY_GHAST_SCREAM)
            players.forEach { player ->
                PlayerEffects.sendTitle(player, titleJson, null, 10, 70, 20)
            }
            val cfg = DungeonConfig.instance
            val srv = new.server
            val worldKey = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(cfg.endWorld))
            val destWorld = srv.getWorld(worldKey)
            val pos = cfg.endPos
            session.players.mapNotNull { srv.playerManager.getPlayer(it) }.forEach { p ->
                destWorld?.let { w ->
                    p.teleport(w, pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, p.yaw, p.pitch)
                }
            }
            PartyService.endDungeon(new, PartyService.PartyEndReason.LIVES_DEPLETED)
        }
    }

    fun onBeforePlayerDamaged(player: ServerPlayerEntity, source: DamageSource) {
        maybeIgniteAttacker(player, source)
    }

    fun onPlayerDamaged(player: ServerPlayerEntity, source: DamageSource) {
        val session = sessions.values.firstOrNull { player.uuid in it.players }
        if (session?.isIceDungeon == true) {
            val state = session.freezeStates.getOrPut(player.uuid) { FreezeState() }
            if (state.freezeElapsedSeconds < FREEZE_TOTAL_SECONDS && ThreadLocalRandom.current().nextBoolean()) {
                state.accumulationRemainder = 0.0
                state.freezeElapsedSeconds = min(FREEZE_TOTAL_SECONDS, state.freezeElapsedSeconds + 1)
                val world = player.serverWorld
                val now = world.time
                if (!state.isFrozen && state.freezeElapsedSeconds >= FREEZE_TOTAL_SECONDS) {
                    enterFrozenState(session, world, player, state, now)
                } else {
                    applyMilestoneEffects(world, player, state, now)
                    updateClockName(player, state)
                }
            }
        }
        maybeIgniteAttacker(player, source)
    }

    private fun maybeIgniteAttacker(player: ServerPlayerEntity, source: DamageSource) {
        if (!player.isBlocking) return
        if (!player.blockedByShield(source)) return
        val attacker = (source.attacker ?: source.source) as? Entity ?: return
        if (!isFlameShield(player.offHandStack) && !isFlameShield(player.mainHandStack)) return
        attacker.setOnFireFor(8.0f)
    }

    private fun resetTemperatureOnDeath(session: Session, player: ServerPlayerEntity) {
        val now = player.serverWorld.time
        session.freezeStates[player.uuid]?.apply {
            freezeElapsedSeconds = 0
            isFrozen = false
            pauseUntilGameTime = 0
            thawUntilGameTime = 0
            thawImmunityUntil = 0
            accumulationRemainder = 0.0
            lastMilestoneOrdinal = FreezeMilestone.NONE.ordinal
            applyMilestoneEffects(player.serverWorld, player, this, now)
            updateClockName(player, this)
        }
        session.heatStates[player.uuid]?.apply {
            heatElapsedSeconds = 0
            pauseUntilGameTime = 0
            immunityUntil = 0
            accumulationRemainder = 0.0
            lastMilestoneOrdinal = HeatMilestone.NONE.ordinal
            applyHeatMilestoneEffects(player.serverWorld, player, this, now)
            updateHeatClockName(player, this)
        }
        player.extinguish()
    }

    fun onPlayerDeath(player: ServerPlayerEntity) {
        println("[Dungeons] playerDied ${player.gameProfile.name}")
        val session = sessions.values.firstOrNull { player.uuid in it.players } ?: return
        resetTemperatureOnDeath(session, player)
        val secretState = session.secretRoomState
        if (secretState != null && secretState.phase != SecretRoomPhase.COMPLETED) {
            if (secretState.phase == SecretRoomPhase.CAPTURE) {
                secretState.pendingGuiPlayers.add(player.uuid)
                secretState.guiOpenPlayers.remove(player.uuid)
                secretState.forcedGuiClosures.remove(player.uuid)
            }
            return
        }
        handleRaidDeath(session, player.uuid, player.serverWorld)
    }

    /**
     * Allow players to right-click raid room doors to join the fight with a short cooldown.
     */
    fun handleRaidDoorInteraction(
        player: ServerPlayerEntity,
        world: ServerWorld,
        hand: Hand,
        pos: BlockPos
    ): ActionResult {
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS
        if (player.isSpectator || !player.isAlive) return ActionResult.PASS

        val session = sessions.values.firstOrNull { it.world == world && player.uuid in it.players }
            ?: return ActionResult.PASS

        val candidateRooms = mutableListOf<Int>()
        session.rooms.forEachIndexed { idx, room ->
            if (room.doors.any { door -> doorContainsBlock(door, pos) }) {
                candidateRooms += idx
            }
        }
        if (candidateRooms.isEmpty()) return ActionResult.PASS

        val targetIdx = candidateRooms.firstOrNull { idx ->
            val spawnPoints = session.raidSpawnPoints[idx]
            val state = session.raidStates[idx]
            !spawnPoints.isNullOrEmpty() && state?.active == true && !state.completed
        } ?: return ActionResult.PASS

        val spawnPoints = session.raidSpawnPoints[targetIdx] ?: return ActionResult.PASS
        if (spawnPoints.isEmpty()) return ActionResult.PASS
        val state = session.raidStates[targetIdx] ?: return ActionResult.PASS
        if (!state.active || state.completed) return ActionResult.PASS

        val now = world.time
        val cooldownUntil = session.raidDoorCooldowns[player.uuid] ?: 0L
        if (now < cooldownUntil) {
            val remainingTicks = cooldownUntil - now
            val remainingSeconds = ((remainingTicks + 19) / 20).toInt().coerceAtLeast(1)
            player.sendDungeonMessage(
                "The raid door is recharging for ${remainingSeconds}s.",
                DungeonMessageType.ERROR
            )
            return ActionResult.FAIL
        }

        val anchor = spawnPoints.randomOrNull() ?: return ActionResult.PASS
        session.raidDoorCooldowns[player.uuid] = now + RAID_DOOR_COOLDOWN_TICKS

        if (state.participants.add(player.uuid)) {
            state.dead.remove(player.uuid)
            session.playerRaidRooms[player.uuid] = targetIdx
            state.maxWaves = determineRaidWaveLimit(state.participants.size)
        } else {
            state.dead.remove(player.uuid)
            session.playerRaidRooms[player.uuid] = targetIdx
        }

        player.teleport(world, anchor.x + 0.5, anchor.y + 1.0, anchor.z + 0.5, player.yaw, player.pitch)
        return ActionResult.SUCCESS
    }

    /**
     * Handle right-clicks on armed yellow gilded chests mid-run.
     *
     * Must run on the server thread; we line it up with the owning session so only dungeon-owned
     * chests react. Each chest resolves once and flips a 50/50 coin: upgrade into green loot or
     * spawn a Gimmighoul.
     */
    fun handleGildedChestInteraction(
        player: ServerPlayerEntity,
        world: ServerWorld,
        pos: BlockPos
    ): ActionResult {
        val yellowChestBlock = yellowGildedChestBlock ?: return ActionResult.PASS
        val state = world.getBlockState(pos)
        if (!state.isOf(yellowChestBlock)) return ActionResult.PASS
        val session = sessions.values.firstOrNull { it.world == world && it.gildedChests.containsKey(pos) }
            ?: return ActionResult.PASS
        val chestState = session.gildedChests[pos] ?: return ActionResult.PASS
        if (!chestState.beginResolution()) return ActionResult.PASS

        return try {
            val roll = world.random.nextDouble()
            if (roll < 0.5) {
                val upgraded = upgradeGildedChest(world, pos, player, session)
                if (!upgraded) {
                    println("[Dungeons] Failed to upgrade gilded chest at ${pos.x},${pos.y},${pos.z}.")
                }
            } else {
                val commandSuccess = spawnGimmighoul(world, pos)
                if (!commandSuccess) {
                    println("[Dungeons] Failed to spawn Gimmighoul at ${pos.x},${pos.y},${pos.z}.")
                }
                SoundService.playLocal(player, SoundEvents.ENTITY_WITCH_CELEBRATE)
            }
            chestState.markResolved()
            ActionResult.SUCCESS
        } catch (error: Exception) {
            chestState.cancelResolution()
            throw error
        }
    }

    fun handleItemUse(player: ServerPlayerEntity, world: ServerWorld, hand: Hand): ActionResult {
        val stack = player.getStackInHand(hand)
        val session = sessions.values.firstOrNull { it.world == world && it.players.contains(player.uuid) }
            ?: return ActionResult.PASS
        val now = world.time
        return when {
            isBreadcrumbStack(stack) -> placeBreadcrumb(session, world, player, hand)
            isEmbryns(stack) && session.isIceDungeon -> {
                val affectedPlayers = session.players.mapNotNull { world.server.playerManager.getPlayer(it) }
                    .filter { target -> target.serverWorld == world && target.squaredDistanceTo(player) <= 64.0 }
                if (affectedPlayers.isEmpty()) {
                    return ActionResult.PASS
                }
                var triggered = false
                affectedPlayers.forEach { target ->
                    val state = session.freezeStates.getOrPut(target.uuid) { FreezeState() }
                    if (now < state.embrynsCooldownUntil) return@forEach
                    val previousSeconds = state.freezeElapsedSeconds
                    state.freezeElapsedSeconds = max(0, previousSeconds - FREEZE_EMBRYNS_REGRESSION_SECONDS)
                    state.accumulationRemainder = 0.0
                    state.pauseUntilGameTime = max(state.pauseUntilGameTime, now + FREEZE_EMBRYNS_PAUSE_SECONDS * 20)
                    state.thawImmunityUntil = max(state.thawImmunityUntil, now + FREEZE_EMBRYNS_PAUSE_SECONDS * 20)
                    state.embrynsCooldownUntil = now + FREEZE_EMBRYNS_COOLDOWN_SECONDS * 20
                    if (state.isFrozen && state.freezeElapsedSeconds < FREEZE_TOTAL_SECONDS) {
                        thawPlayer(world, target, state, now, passive = false)
                    } else {
                        applyMilestoneEffects(world, target, state, now)
                        updateClockName(target, state)
                    }
                    world.spawnParticles(ParticleTypes.SNOWFLAKE, target.x, target.y + 1.0, target.z, 20, 0.5, 0.5, 0.5, 0.0)
                    SoundService.playLocal(target, SoundEvents.BLOCK_FIRE_EXTINGUISH, volume = 0.5f, pitch = 1.2f)
                    target.sendMessage(Text.literal("Embryns warmth washes over you.").formatted(Formatting.GOLD), true)
                    triggered = true
                }
                if (!triggered) {
                    return ActionResult.PASS
                }
                player.swingHand(hand)
                stack.decrement(1)
                SoundService.playLocal(player, SoundEvents.ITEM_BOTTLE_FILL_DRAGONBREATH, volume = 0.6f, pitch = 0.8f)
                ActionResult.SUCCESS
            }

            isSoothingIce(stack) && session.isFireDungeon -> {
                val affectedPlayers = session.players.mapNotNull { world.server.playerManager.getPlayer(it) }
                    .filter { target -> target.serverWorld == world && target.squaredDistanceTo(player) <= 64.0 }
                if (affectedPlayers.isEmpty()) {
                    return ActionResult.PASS
                }
                var triggered = false
                affectedPlayers.forEach { target ->
                    val state = session.heatStates.getOrPut(target.uuid) { HeatState() }
                    if (now < state.soothingCooldownUntil) return@forEach
                    val previousSeconds = state.heatElapsedSeconds
                    state.heatElapsedSeconds = max(0, previousSeconds - HEAT_SOOTHING_REGRESSION_SECONDS)
                    state.accumulationRemainder = 0.0
                    state.pauseUntilGameTime = max(state.pauseUntilGameTime, now + HEAT_SOOTHING_PAUSE_SECONDS * 20)
                    state.immunityUntil = max(state.immunityUntil, now + HEAT_SOOTHING_PAUSE_SECONDS * 20)
                    state.soothingCooldownUntil = now + HEAT_SOOTHING_COOLDOWN_SECONDS * 20
                    applyHeatMilestoneEffects(world, target, state, now)
                    updateHeatClockName(target, state)
                    target.extinguish()
                    world.spawnParticles(ParticleTypes.SNOWFLAKE, target.x, target.y + 1.0, target.z, 20, 0.5, 0.5, 0.5, 0.0)
                    SoundService.playLocal(target, SoundEvents.BLOCK_FIRE_EXTINGUISH, volume = 0.5f, pitch = 1.2f)
                    target.sendMessage(Text.literal("The soothing ice cools you down.").formatted(Formatting.AQUA), true)
                    triggered = true
                }
                if (!triggered) {
                    return ActionResult.PASS
                }
                player.swingHand(hand)
                stack.decrement(1)
                SoundService.playLocal(player, SoundEvents.ITEM_BOTTLE_FILL_DRAGONBREATH, volume = 0.6f, pitch = 0.8f)
                ActionResult.SUCCESS
            }

            else -> ActionResult.PASS
        }
    }

    private fun placeBreadcrumb(
        session: Session,
        world: ServerWorld,
        player: ServerPlayerEntity,
        hand: Hand
    ): ActionResult {
        if (session.status == DungeonStatus.ENDED) return ActionResult.FAIL
        val remaining = session.breadcrumbUses.getOrDefault(player.uuid, BREADCRUMB_MAX_USES)
        if (remaining <= 0) {
            player.setStackInHand(hand, ItemStack.EMPTY)
            return ActionResult.FAIL
        }
        val armorStand = ArmorStandEntity(world, player.x, player.y, player.z)
        armorStand.isInvisible = true
        armorStand.isInvulnerable = true
        armorStand.isSilent = true
        armorStand.setNoGravity(true)
        armorStand.addCommandTag(BREADCRUMB_TAG)
        if (!world.spawnEntity(armorStand)) {
            return ActionResult.FAIL
        }
        session.breadcrumbs.add(Breadcrumb(armorStand.uuid, world.time + BREADCRUMB_LIFETIME_TICKS))
        val newRemaining = remaining - 1
        session.breadcrumbUses[player.uuid] = newRemaining
        player.swingHand(hand)
        val held = player.getStackInHand(hand)
        if (newRemaining <= 0) {
            player.setStackInHand(hand, ItemStack.EMPTY)
        } else {
            updateBreadcrumbLore(held, newRemaining)
        }
        return ActionResult.SUCCESS
    }

    fun handleEntityInteraction(
        player: ServerPlayerEntity,
        target: ServerPlayerEntity,
        world: ServerWorld,
        hand: Hand
    ): ActionResult {
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS
        val session = sessions.values.firstOrNull { it.world == world && it.players.contains(player.uuid) }
            ?: return ActionResult.PASS
        if (!session.isIceDungeon || target.uuid !in session.players) return ActionResult.PASS
        val state = session.freezeStates.getOrPut(target.uuid) { FreezeState() }
        if (!state.isFrozen) return ActionResult.PASS
        val now = world.time
        thawPlayer(world, target, state, now, passive = false, targetSeconds = FREEZE_NEUTRAL_SECONDS)
        player.swingHand(hand)
        target.sendMessage(Text.literal("${player.gameProfile.name} thaws you out!").formatted(Formatting.AQUA), true)
        SoundService.playLocal(target, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, pitch = 1.4f)
        SoundService.playLocal(player, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, pitch = 1.4f)
        return ActionResult.SUCCESS
    }

    private fun upgradeGildedChest(
        world: ServerWorld,
        pos: BlockPos,
        player: ServerPlayerEntity,
        session: Session
    ): Boolean {
        val greenState = resolveGreenGildedChestState() ?: return false
        val changed = world.setBlockState(pos, greenState)
        val placedState = world.getBlockState(pos)
        if (!changed && !placedState.isOf(greenState.block)) {
            return false
        }
        fillChestWithLoot(
            world,
            pos,
            GoodChestLootConfig.get(),
            session.embrynsChestPlan,
            session.soothingIceChestPlan,
            session.flameShieldChance
        )
        placedState.createScreenHandlerFactory(world, pos)?.let { factory ->
            player.openHandledScreen(factory)
        }
        return true
    }

    private fun spawnGimmighoul(world: ServerWorld, pos: BlockPos): Boolean {
        clearInventory(world, pos)
        world.removeBlockEntity(pos)
        world.setBlockState(pos, Blocks.AIR.defaultState)
        val server = world.server
        val source = server.commandSource
            .withWorld(world)
            .withPosition(Vec3d(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()))
        val command = "/alphaz dungeon strong gimmighoul false ${pos.x} ${pos.y} ${pos.z}"
        return runCatching { server.commandManager.executeWithPrefix(source, command) }
            .onFailure { error ->
                println(
                    "[Dungeons] Gimmighoul command failed at ${pos.x},${pos.y},${pos.z}: ${error.message}"
                )
            }
            .isSuccess
    }

    private fun schedule(world: ServerWorld, delay: Long, sessionId: String? = null, action: () -> Unit) {
        scheduled.add(Scheduled(world, world.time + delay, action, sessionId))
    }

    fun cancelScheduled(sessionId: String) {
        scheduled.removeIf { it.sessionId == sessionId }
    }

    fun computeRooms(scan: DungeonScanner.ScanResult.Success): List<Room> {
        val raidCorners = scan.roomBlocks["raid"] ?: emptyList()
        val tallRaidCorners = scan.roomBlocks["tallRaid"] ?: emptyList()
        val furnaceCorners = scan.roomBlocks["lootOrRaid"] ?: emptyList()
        val bossCorners = scan.roomBlocks["boss"] ?: emptyList()
        val rooms = mutableSetOf<Pair<BlockPos, BlockPos>>()
        val standardCorners = (raidCorners + furnaceCorners).toSet()
        rooms.addAll(findRoomsForCorners(standardCorners, ROOM_SIZE_XZ - 1, ROOM_SIZE_Y - 1, ROOM_SIZE_XZ - 1))
        val tallRaidCornerSet = tallRaidCorners.toSet()
        rooms.addAll(findRoomsForCorners(tallRaidCornerSet, ROOM_SIZE_XZ - 1, TALL_ROOM_SIZE_Y - 1, ROOM_SIZE_XZ - 1))

        val bossCornerSet = bossCorners.toSet()
        val bossRoomPairs = mutableSetOf<Pair<BlockPos, BlockPos>>()
        bossRoomPairs.addAll(
            findRoomsForCorners(
                bossCornerSet,
                BOSS_ROOM_SIZE_X - 1,
                BOSS_ROOM_SIZE_Y - 1,
                BOSS_ROOM_SIZE_Z - 1
            )
        )
        bossRoomPairs.addAll(
            findRoomsForCorners(
                bossCornerSet,
                BOSS_ROOM_SIZE_Z - 1,
                BOSS_ROOM_SIZE_Y - 1,
                BOSS_ROOM_SIZE_X - 1
            )
        )
        if (bossRoomPairs.isEmpty() && bossCornerSet.isNotEmpty()) {
            bossRoomPairs.addAll(
                findRoomsForHorizontalCorners(
                    bossCornerSet,
                    BOSS_ROOM_SIZE_X - 1,
                    BOSS_ROOM_SIZE_Z - 1,
                    BOSS_ROOM_SIZE_Y - 1
                )
            )
            if (bossRoomPairs.isEmpty()) {
                bossRoomPairs.addAll(
                    findRoomsForHorizontalCorners(
                        bossCornerSet,
                        BOSS_ROOM_SIZE_Z - 1,
                        BOSS_ROOM_SIZE_X - 1,
                        BOSS_ROOM_SIZE_Y - 1
                    )
                )
            }
        }
        rooms.addAll(bossRoomPairs)
        if (rooms.isEmpty()) return emptyList()

        val roomList = rooms.map { (min, max) ->
            val center = BlockPos((min.x + max.x) / 2, (min.y + max.y) / 2, (min.z + max.z) / 2)
            Room(min, max, center)
        }.toMutableList()

        val doors = computeDoors(scan.actionBlocks["door"] ?: emptyList())
        doors.forEach { door ->
            roomList.forEach { room ->
                if (doorIntersectsRoom(door, room)) {
                    val clamped = clampDoorToRoom(door, room)
                    if (room.doors.none { it.min == clamped.min && it.max == clamped.max }) {
                        room.doors.add(clamped)
                    }
                }
            }
        }

        return roomList
    }

    private fun findRoomsForCorners(
        corners: Set<BlockPos>,
        dx: Int,
        dy: Int,
        dz: Int
    ): Set<Pair<BlockPos, BlockPos>> {
        if (corners.size < 2) return emptySet()
        val rooms = mutableSetOf<Pair<BlockPos, BlockPos>>()
        for (p1 in corners) {
            for (p2 in corners) {
                if (p1 == p2) continue
                if (abs(p1.x - p2.x) == dx && abs(p1.y - p2.y) == dy && abs(p1.z - p2.z) == dz) {
                    val min = BlockPos(
                        minOf(p1.x, p2.x),
                        minOf(p1.y, p2.y),
                        minOf(p1.z, p2.z)
                    )
                    val max = BlockPos(
                        maxOf(p1.x, p2.x),
                        maxOf(p1.y, p2.y),
                        maxOf(p1.z, p2.z)
                    )
                    rooms.add(min to max)
                }
            }
        }
        return rooms
    }

    private fun findRoomsForHorizontalCorners(
        corners: Set<BlockPos>,
        dx: Int,
        dz: Int,
        height: Int
    ): Set<Pair<BlockPos, BlockPos>> {
        if (corners.size < 2) return emptySet()
        val lowestY = corners.minOf { it.y }
        val highestY = corners.maxOf { it.y }
        val maxY = maxOf(lowestY + height, highestY)
        val rooms = mutableSetOf<Pair<BlockPos, BlockPos>>()
        for (p1 in corners) {
            for (p2 in corners) {
                if (p1 == p2) continue
                val dxAbs = abs(p1.x - p2.x)
                val dzAbs = abs(p1.z - p2.z)
                if ((dxAbs == dx && dzAbs == dz) || (dxAbs == dz && dzAbs == dx)) {
                    val min = BlockPos(
                        minOf(p1.x, p2.x),
                        lowestY,
                        minOf(p1.z, p2.z)
                    )
                    val max = BlockPos(
                        maxOf(p1.x, p2.x),
                        maxY,
                        maxOf(p1.z, p2.z)
                    )
                    rooms.add(min to max)
                }
            }
        }
        return rooms
    }

    private fun computeDoors(positions: List<BlockPos>): List<Door> {
        val remaining = positions.toMutableList()
        val doors = mutableListOf<Door>()
        while (remaining.isNotEmpty()) {
            val p1 = remaining.removeAt(0)
            val idx = remaining.indexOfFirst { p ->
                (p.x == p1.x && abs(p.y - p1.y) == 4 && abs(p.z - p1.z) == 4) ||
                        (p.z == p1.z && abs(p.x - p1.x) == 4 && abs(p.y - p1.y) == 4) ||
                        (p.y == p1.y && abs(p.x - p1.x) == 4 && abs(p.z - p1.z) == 4)
            }
            if (idx != -1) {
                val p2 = remaining.removeAt(idx)
                val center = BlockPos((p1.x + p2.x) / 2, (p1.y + p2.y) / 2, (p1.z + p2.z) / 2)
                val (min, max) = when {
                    p1.x == p2.x -> {
                        val yMin = minOf(p1.y, p2.y) + 1
                        val yMax = maxOf(p1.y, p2.y) - 1
                        val zMin = minOf(p1.z, p2.z) + 1
                        val zMax = maxOf(p1.z, p2.z) - 1
                        BlockPos(center.x, yMin, zMin) to BlockPos(center.x, yMax, zMax)
                    }
                    p1.y == p2.y -> {
                        val xMin = minOf(p1.x, p2.x) + 1
                        val xMax = maxOf(p1.x, p2.x) - 1
                        val zMin = minOf(p1.z, p2.z) + 1
                        val zMax = maxOf(p1.z, p2.z) - 1
                        BlockPos(xMin, center.y, zMin) to BlockPos(xMax, center.y, zMax)
                    }
                    p1.z == p2.z -> {
                        val xMin = minOf(p1.x, p2.x) + 1
                        val xMax = maxOf(p1.x, p2.x) - 1
                        val yMin = minOf(p1.y, p2.y) + 1
                        val yMax = maxOf(p1.y, p2.y) - 1
                        BlockPos(xMin, yMin, center.z) to BlockPos(xMax, yMax, center.z)
                    }
                    else -> continue
                }
                doors.add(Door(min, max, center))
            }
        }
        return doors
    }

    private fun locateBossRoom(
        dungeon: Dungeon,
        rooms: List<Room>,
        scan: DungeonScanner.ScanResult.Success,
        goldBlocks: List<BlockPos>
    ): BossRoomData? {
        val bossMarkers = scan.roomBlocks["boss"] ?: emptyList()
        if (bossMarkers.isEmpty()) {
            println("[Dungeons][BossAnchor] ${dungeon.name}: no ${DungeonConstants.ANCHOR_BLOCK_ID} markers found in scan volume.")
            throw BossRoomInitializationException("Missing boss room anchor block.")
        }
        rooms.forEachIndexed { index, room ->
            val logsInRoom = bossMarkers.filter { marker -> isInsideRoom(marker, room) }
            if (logsInRoom.isEmpty()) return@forEachIndexed
            val anchor = selectBossAnchor(room, logsInRoom)
            val boundsLabel = "${room.min.x},${room.min.y},${room.min.z} -> ${room.max.x},${room.max.y},${room.max.z}"
            println(
                "[Dungeons][BossAnchor] ${dungeon.name}: room=${index + 1} count=${logsInRoom.size} bounds=$boundsLabel anchor=${anchor.x},${anchor.y},${anchor.z}"
            )
            val spawnPos = scan.actionBlocks["bossCrafter"]?.firstOrNull { marker -> isInsideRoom(marker, room) }
            val goldSpawnPoints = goldBlocks.filter { marker -> isInsideRoom(marker, room) }
            return BossRoomData(index, spawnPos, goldSpawnPoints, anchor)
        }
        println(
            "[Dungeons][BossAnchor] ${dungeon.name}: unable to locate ${DungeonConstants.ANCHOR_BLOCK_ID} within computed boss room bounds."
        )
        throw BossRoomInitializationException("Missing boss room anchor block.")
    }

    private fun selectBossAnchor(room: Room, anchors: List<BlockPos>): BlockPos {
        if (anchors.size == 1) return anchors.first()
        val center = Vec3d(
            room.min.x + (room.max.x - room.min.x + 1) / 2.0,
            room.min.y + (room.max.y - room.min.y + 1) / 2.0,
            room.min.z + (room.max.z - room.min.z + 1) / 2.0
        )
        return anchors.minWithOrNull { a, b ->
            val cmp = squaredDistanceToCenter(a, center).compareTo(squaredDistanceToCenter(b, center))
            if (cmp != 0) {
                cmp
            } else {
                val yCmp = a.y.compareTo(b.y)
                if (yCmp != 0) {
                    yCmp
                } else {
                    hashXZ(a).compareTo(hashXZ(b))
                }
            }
        } ?: anchors.first()
    }

    private fun squaredDistanceToCenter(pos: BlockPos, center: Vec3d): Double {
        val dx = pos.x + 0.5 - center.x
        val dy = pos.y + 0.5 - center.y
        val dz = pos.z + 0.5 - center.z
        return dx * dx + dy * dy + dz * dz
    }

    private fun hashXZ(pos: BlockPos): Int {
        return pos.x * 31 + pos.z
    }

    private fun handleRaidDeath(session: Session, playerId: UUID, world: ServerWorld) {
        val roomIdx = session.playerRaidRooms.remove(playerId) ?: return
        val state = session.raidStates[roomIdx] ?: return
        if (!state.active) return
        state.dead.add(playerId)
        if (session.bossRoomIndex == roomIdx) {
            tryResetRaid(session, roomIdx, world)
        } else {
            finishRaid(session, roomIdx, world, state, forceCleanup = true)
        }
    }

    private fun tryResetRaid(session: Session, roomIdx: Int, world: ServerWorld) {
        val state = session.raidStates[roomIdx] ?: return
        if (!state.active || state.participants.isEmpty()) return
        val room = session.rooms.getOrNull(roomIdx) ?: return
        if (areAllPlayersDeadInRoom(world, room)) {
            completeRaidAfterWipe(session, roomIdx, world, state)
            return
        }
        if (state.participants.all { it in state.dead }) {
            completeRaidAfterWipe(session, roomIdx, world, state)
        }
    }

    private fun completeRaidAfterWipe(
        session: Session,
        roomIdx: Int,
        world: ServerWorld,
        state: RaidState
    ) {
        finishRaid(session, roomIdx, world, state, forceCleanup = true)
    }

    private fun clearRaidState(
        session: Session,
        roomIdx: Int,
        world: ServerWorld? = null,
        removeEntities: Boolean = false
    ) {
        val state = session.raidStates[roomIdx] ?: return
        if (removeEntities && world != null) {
            val room = session.rooms[roomIdx]
            killCobblemonInRoom(world, room)
            removeRaidEntities(session, world, roomIdx, room)
        }
        val affected = state.participants + state.dead
        state.participants.clear()
        state.dead.clear()
        state.active = false
        state.currentWave = 0
        state.waveInProgress = false
        state.hardSpawned = 0
        state.mediumSpawned = 0
        state.nextCheckTick = 0L
        state.completed = false
        affected.forEach { uuid ->
            if (session.playerRaidRooms[uuid] == roomIdx) {
                session.playerRaidRooms.remove(uuid)
            }
        }
        session.playerRaidRooms.entries.removeIf { it.value == roomIdx }
        session.raidBossBars[roomIdx]?.let { bar ->
            bar.players.toList().forEach { bar.removePlayer(it) }
            bar.setVisible(false)
        }
        session.raidEntityUuids.remove(roomIdx)
        state.maxWaves = determineRaidWaveLimit(session.players.size)
    }

    private fun areAllPlayersDeadInRoom(world: ServerWorld, room: Room): Boolean {
        val box = Box(
            room.min.x.toDouble(),
            room.min.y.toDouble(),
            room.min.z.toDouble(),
            (room.max.x + 1).toDouble(),
            (room.max.y + 1).toDouble(),
            (room.max.z + 1).toDouble()
        )
        val playersInRoom = world.players.filter { player ->
            !player.isSpectator && player.boundingBox.intersects(box)
        }
        if (playersInRoom.isEmpty()) return false
        return playersInRoom.none { player -> player.isAlive }
    }

    private fun openRoomDoors(world: ServerWorld, room: Room) {
        val doorState = resolveDoorBlockState(DungeonConfig.instance)
        val doorBlock = doorState.block
        val airState = Blocks.AIR.defaultState
        room.doors.forEach { door ->
            applyDoorState(world, door, airState) { current ->
                current.isOf(Blocks.IRON_BARS) || current.isOf(doorBlock) || current.isOf(Blocks.BARRIER)
            }
        }
    }

    private fun closeRoomDoors(world: ServerWorld, room: Room) {
        val doorState = resolveDoorBlockState(DungeonConfig.instance)
        val doorBlock = doorState.block
        room.doors.forEach { door ->
            val result = closeExistingDoorBlocks(world, door)
            if (doorBlock !is DoorBlock) {
                applyDoorState(world, door, doorState) { current ->
                    current.isAir || current.isOf(Blocks.IRON_BARS) || current.isOf(doorBlock) || current.isOf(Blocks.BARRIER)
                }
            }
            if (result.powered && result.hadDoor) {
                placeDoorPlug(world, door.center)
            }
        }
    }

    private fun resolveDoorBlockState(cfg: DungeonConfig): BlockState {
        val identifier = Identifier.tryParse(cfg.doorBlock)
        val block = identifier?.let { Registries.BLOCK.getOrEmpty(it).orElse(null) }
        return (block ?: Blocks.WAXED_OXIDIZED_COPPER_GRATE).defaultState
    }

    private fun lockBossDoor(world: ServerWorld, session: Session, type: DoorManager.DoorType) {
        val key = session.bossDoors[type]
        if (key == null) {
            broadcastBossDoorDebug(session, world, "Attempted to lock missing boss door ${type.name}.", Formatting.RED)
            return
        }
        val changed = DoorManager.lockDoor(world, key)
        debugBossDoorAction(
            session,
            world,
            "Locking boss door ${type.name}",
            changed,
            mapOf(type to DoorManager.DoorState.LOCKED)
        )
    }

    private fun unlockBossDoor(world: ServerWorld, session: Session, type: DoorManager.DoorType) {
        val key = session.bossDoors[type]
        if (key == null) {
            broadcastBossDoorDebug(session, world, "Attempted to unlock missing boss door ${type.name}.", Formatting.RED)
            return
        }
        val changed = DoorManager.unlockDoor(world, key)
        debugBossDoorAction(
            session,
            world,
            "Unlocking boss door ${type.name}",
            changed,
            mapOf(type to DoorManager.DoorState.UNLOCKED)
        )
    }

    private fun lockBossDoors(world: ServerWorld, session: Session) {
        val roomKey = session.bossRoomKey
        if (roomKey == null) {
            broadcastBossDoorDebug(session, world, "Close requested but no boss room key registered.", Formatting.RED)
            return
        }
        val changed = DoorManager.lockAllDoors(world, roomKey)
        val expected = session.bossDoors.keys.associateWith { DoorManager.DoorState.LOCKED }
        debugBossDoorAction(session, world, "Close requested for boss doors.", changed, expected)
    }

    private fun unlockBossDoors(world: ServerWorld, session: Session) {
        val roomKey = session.bossRoomKey
        if (roomKey == null) {
            broadcastBossDoorDebug(session, world, "Open requested but no boss room key registered.", Formatting.RED)
            return
        }
        val changed = DoorManager.unlockAllDoors(world, roomKey)
        val expected = session.bossDoors.keys.associateWith { DoorManager.DoorState.UNLOCKED }
        debugBossDoorAction(session, world, "Open requested for boss doors.", changed, expected)
    }

    private fun debugBossDoorAction(
        session: Session,
        world: ServerWorld,
        action: String,
        changed: Boolean?,
        expectedStates: Map<DoorManager.DoorType, DoorManager.DoorState>
    ) {
        val stateMessage = when (changed) {
            true -> "$action (blocks updated)"
            false -> "$action (no blocks changed)"
            null -> action
        }
        val color = when (changed) {
            true -> Formatting.GREEN
            false -> Formatting.YELLOW
            null -> Formatting.AQUA
        }
        broadcastBossDoorDebug(session, world, stateMessage, color)
        if (expectedStates.isEmpty()) return
        val mismatches = verifyBossDoorStates(session, expectedStates)
        if (mismatches.isNotEmpty()) {
            val expectedSummary = expectedStates.entries.joinToString { (type, state) -> "${type.name}:${state.name}" }
            val actualSummary = mismatches.joinToString { (type, state) ->
                val label = state?.name ?: "NONE"
                "${type.name}:$label"
            }
            broadcastBossDoorDebug(
                session,
                world,
                "Door state mismatch. Expected $expectedSummary, got $actualSummary",
                Formatting.RED
            )
        }
    }

    private fun verifyBossDoorStates(
        session: Session,
        expectedStates: Map<DoorManager.DoorType, DoorManager.DoorState>
    ): List<Pair<DoorManager.DoorType, DoorManager.DoorState?>> {
        if (expectedStates.isEmpty()) return emptyList()
        val mismatches = mutableListOf<Pair<DoorManager.DoorType, DoorManager.DoorState?>>()
        expectedStates.forEach { (type, expected) ->
            val actual = session.bossDoors[type]?.let { DoorManager.getDoorState(it) }
            if (actual != expected) {
                mismatches.add(type to actual)
            }
        }
        return mismatches
    }

    private fun broadcastBossDoorDebug(
        session: Session,
        world: ServerWorld,
        message: String,
        @Suppress("UNUSED_PARAMETER") formatting: Formatting
    ) {
        val dimensionId = world.registryKey.value
        println("[Dungeons][BossDoors] ${session.dungeon.name}@$dimensionId: $message")
    }

    private fun playersInRoom(session: Session, roomIdx: Int): List<ServerPlayerEntity> {
        val room = session.rooms.getOrNull(roomIdx) ?: return emptyList()
        val server = session.world.server
        return session.players.mapNotNull { server.playerManager.getPlayer(it) }
            .filter { player ->
                player.serverWorld == session.world && player.blockPos.let { pos ->
                    isInsideRoom(pos, room)
                }
            }
    }

    private fun hasActivePlayerInRoom(world: ServerWorld, room: Room): Boolean {
        return world.getEntitiesByClass(ServerPlayerEntity::class.java, room.boundingBox) { player ->
            player.isAlive && !player.isSpectator
        }.isNotEmpty()
    }

    private fun isInsideRoom(pos: BlockPos, room: Room): Boolean =
        pos.x in room.min.x..room.max.x &&
                pos.y in room.min.y..room.max.y &&
                pos.z in room.min.z..room.max.z

    private fun applyDoorState(
        world: ServerWorld,
        door: Door,
        desiredState: BlockState,
        shouldReplace: (BlockState) -> Boolean
    ) {
        val minX = minOf(door.min.x, door.max.x)
        val maxX = maxOf(door.min.x, door.max.x)
        val minY = minOf(door.min.y, door.max.y)
        val maxY = maxOf(door.min.y, door.max.y)
        val minZ = minOf(door.min.z, door.max.z)
        val maxZ = maxOf(door.min.z, door.max.z)
        val mutablePos = BlockPos.Mutable()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    mutablePos.set(x, y, z)
                    if (!world.isChunkLoadedSafe(mutablePos)) continue
                    val currentState = world.getBlockState(mutablePos)
                    if (shouldReplace(currentState) && currentState != desiredState) {
                        world.setBlockState(mutablePos, desiredState, DOOR_UPDATE_FLAGS)
                    }
                }
            }
        }
    }

    private fun handleRaidWaves(world: ServerWorld, session: Session) {
        session.raidStates.forEach { (roomIdx, state) ->
            if (state.completed) return@forEach
            val spawnPoints = session.raidSpawnPoints[roomIdx]
            if (spawnPoints.isNullOrEmpty()) return@forEach
            val room = session.rooms.getOrNull(roomIdx) ?: return@forEach
            if (state.waveInProgress) {
                if (state.nextCheckTick > world.time) return@forEach
                if (!hasRaidEntities(world, session, roomIdx)) {
                    state.waveInProgress = false
                    if (state.currentWave >= state.maxWaves) {
                        finishRaid(session, roomIdx, world, state)
                    } else {
                        state.nextCheckTick = world.time + GRACE_TICKS
                    }
                }
            } else {
                if (!state.active) return@forEach
                if (state.nextCheckTick > world.time) return@forEach
                if (state.currentWave >= state.maxWaves) {
                    finishRaid(session, roomIdx, world, state)
                } else {
                    if (!hasActivePlayerInRoom(world, room)) {
                        state.nextCheckTick = world.time + GRACE_TICKS
                        return@forEach
                    }
                    startNextWave(session, roomIdx, world, state)
                }
            }
        }
    }

    private fun determineRaidWaveLimit(participantCount: Int): Int {
        val size = participantCount.coerceAtLeast(1)
        return when (size) {
            1 -> 1
            2 -> 2
            else -> min(3, WAVE_CONFIG.size)
        }.coerceIn(1, WAVE_CONFIG.size)
    }

    private fun determineBossWaveLimit(session: Session): Int {
        val size = session.players.size.coerceAtLeast(1)
        return when {
            size == 1 -> 1
            size in 2..3 -> 2
            else -> 3
        }
    }

    private fun broadcastLivesUpdate(session: Session, lives: Int) {
        val color = when {
            lives >= 4 -> "green"
            lives in 2..3 -> "yellow"
            lives == 1 -> "red"
            else -> "dark_red"
        }
        val titleJson = "{\"text\":\"$lives Lives Left\",\"bold\":true,\"color\":\"$color\"}"
        val subtitleJson = if (lives == 0) {
            "{\"text\":\"If anyone dies again, it's over...\",\"color\":\"red\"}"
        } else {
            null
        }
        val players = session.players.mapNotNull { session.world.server.playerManager.getPlayer(it) }
            .filter { it.serverWorld == session.world }
        SoundService.playToPlayers(players, SoundEvents.ENTITY_PLAYER_HURT)
        players.forEach { player ->
            PlayerEffects.sendTitle(player, titleJson, subtitleJson, 10, 40, 10)
        }
    }

    private fun handleBossEncounter(world: ServerWorld, session: Session) {
        val bossIdx = session.bossRoomIndex ?: return
        val room = session.rooms.getOrNull(bossIdx) ?: return
        val onlinePartyMembers = session.players.mapNotNull { uuid ->
            world.server.playerManager.getPlayer(uuid)
        }
        val activePlayers = onlinePartyMembers.filter { player ->
            player.serverWorld == world && !player.isSpectator && player.isAlive
        }
        val playersInRoom = activePlayers.filter { player -> isInsideRoom(player.blockPos, room) }
        val playersInRoomCount = playersInRoom.size
        if (!session.bossStarted) {
            val initiator = playersInRoom.firstOrNull()
            if (initiator != null && hasActivePlayerInRoom(world, room)) {
                startBossEncounter(session, world, room, initiator)
            }
            return
        }
        if (session.bossCompleted) return
        if (session.lastBossRoomCount != playersInRoomCount) {
            println("[Dungeons] partyInBossRoom=$playersInRoomCount for ${session.dungeon.name}")
            session.lastBossRoomCount = playersInRoomCount
        }
        if (playersInRoomCount == 0) {
            if (!session.bossPaused) {
                session.bossPaused = true
                session.bossPauseStartTick = world.time
                session.bossFailAtTick = world.time + BOSS_EMPTY_FAIL_TICKS
                println("[Dungeons] encounterPaused for ${session.dungeon.name}")
            }
        } else {
            if (session.bossPaused) {
                session.bossPaused = false
                session.bossPauseStartTick = null
                session.bossFailAtTick = null
                session.bossNextSummonTick = if (session.bossWavesSpawned < session.bossTotalWaves) {
                    world.time + BOSS_WAVE_INTERVAL_TICKS
                } else {
                    0L
                }
                println("[Dungeons] encounterResumed for ${session.dungeon.name}")
            }
        }
        if (session.bossPaused) {
            val failAt = session.bossFailAtTick
            if (failAt != null && world.time >= failAt) {
                failBossEncounter(session, world)
            }
            return
        }
        val bossEntity = findBossEntity(world, session, room)
        if (bossEntity == null) {
            if (!session.bossEntitySpawned) {
                return
            }
            if (playersInRoomCount > 0) {
                onBossDefeated(session, world, room, playersInRoomCount)
            } else {
                val failAt = session.bossFailAtTick
                if (failAt != null && world.time >= failAt) {
                    failBossEncounter(session, world)
                }
            }
            return
        }
        if (!session.bossEntitySpawned) {
            session.bossEntitySpawned = true
        }
        if (session.bossNextSummonTick > 0 && session.bossNextSummonTick <= world.time) {
            val spawned = if (playersInRoomCount > 0) {
                triggerBossWave(session, world)
            } else {
                false
            }
            if (!spawned && session.bossWavesSpawned < session.bossTotalWaves) {
                session.bossNextSummonTick = world.time + BOSS_WAVE_INTERVAL_TICKS
            }
        }
    }

    private fun failBossEncounter(session: Session, world: ServerWorld) {
        if (!session.bossStarted || session.bossCompleted) return
        println("[Dungeons] encounterFailed for ${session.dungeon.name}")
        session.bossPaused = false
        session.bossPauseStartTick = null
        session.bossFailAtTick = null
        session.lastBossRoomCount = -1
        session.bossEntitySpawned = false
        session.bossStarted = false
        session.bossWavesSpawned = 0
        session.bossTotalWaves = 0
        session.bossNextSummonTick = 0L
        session.partySpawn = session.spawn
        killAllPokemonInDungeon(session)
        session.rooms.forEach { openRoomDoors(world, it) }
        unlockBossDoors(world, session)
        resetBossBar(session)
        session.dungeonBossBar.setVisible(false)
    }

    private fun onBossDefeated(session: Session, world: ServerWorld, room: Room, participants: Int) {
        val bossJustCompleted = !session.bossCompleted
        if (bossJustCompleted) {
            session.bossCompleted = true
            println("[Dungeons] bossDefeated flag set for ${session.dungeon.name}")
            session.partySpawn = session.spawn
            session.bossWavesSpawned = 0
            session.bossTotalWaves = 0
            session.bossNextSummonTick = 0L
            session.bossPaused = false
            session.bossPauseStartTick = null
            session.bossFailAtTick = null
            session.lastBossRoomCount = -1
            session.bossEntitySpawned = false
            session.rooms.forEach { openRoomDoors(world, it) }
            unlockBossDoors(world, session)
            resetBossBar(session)
            val titleJson = "{\"text\":\"Boss Defeated!\",\"bold\":true,\"color\":\"gold\"}"
            val subtitleJson = "{\"text\":\"The dungeon has fell silent...\",\"color\":\"gray\"}"
            val players = session.players.mapNotNull { world.server.playerManager.getPlayer(it) }
                .filter { it.serverWorld == world }
            SoundService.playToPlayers(players, SoundEvents.BLOCK_BEACON_ACTIVATE)
            players.forEach { player ->
                PlayerEffects.sendTitle(player, titleJson, subtitleJson, 10, 70, 20)
            }
            beginReadyPhase(session, world, participants)
        }
        val roomLabel = roomLabel(session, room)
        if (room.hasBossCleanupCompleted) {
            println("[Dungeons] room=$roomLabel cleanup skip reason=completed")
            return
        }
        if (room.isBossCleanupRunning) {
            println("[Dungeons] room=$roomLabel cleanup skip reason=running")
            return
        }
        room.isBossCleanupRunning = true
        val server = world.server
        val cleanupTask = Runnable {
            var completed = false
            try {
                killAllPokemonInDungeon(session)
                room.hasBossCleanupCompleted = true
                completed = true
            } catch (ex: Exception) {
                println("[Dungeons] room=$roomLabel cleanup error msg=${ex.message ?: ex::class.simpleName}")
            } finally {
                room.isBossCleanupRunning = false
                if (completed) {
                    println("[Dungeons] room=$roomLabel cleanup complete")
                }
            }
        }
        if (server.isOnThread) {
            cleanupTask.run()
        } else {
            server.execute(cleanupTask)
        }
    }

    private fun beginReadyPhase(session: Session, world: ServerWorld, participants: Int) {
        val maxSize = session.players.size.coerceAtLeast(1)
        val requirement = participants.coerceIn(1, maxSize)
        session.status = DungeonStatus.AWAITING_PROGRESS
        session.partySizeSnapshot = requirement
        session.readyPlayers.clear()
        session.readyCooldowns.clear()
        val server = world.server
        println("[Dungeons] ${session.dungeon.name} awaiting ready-up requirement=$requirement legendary=${session.legendary}")
    }

    private fun finishDungeon(session: Session) {
        session.status = DungeonStatus.ENDED
        session.readyPlayers.clear()
        session.readyCooldowns.clear()
        val result = PartyService.concludeDungeonByName(session.dungeon.name, PartyService.PartyEndReason.BOSS_DEFEATED)
        if (result == null) {
            println("[Dungeons] Unable to locate party for dungeon ${session.dungeon.name}; forcing cleanup.")
            DungeonManager.markTaken(session.dungeon.name, false)
            PartyService.cleanupOrphanedParty(session.dungeon.name, session.players)
            endSession(session.dungeon.name)
        } else if (!result.success) {
            println("[Dungeons] concludeDungeon failed for ${session.dungeon.name}: ${result.message}")
            val stillInParty = session.players.any { uuid -> PartyService.getPartyOf(uuid) != null }
            if (stillInParty) {
                DungeonManager.markTaken(session.dungeon.name, false)
                PartyService.cleanupOrphanedParty(session.dungeon.name, session.players)
                endSession(session.dungeon.name)
            }
        }
    }

    private fun recordDungeonCompletion(session: Session) {
        DungeonStatsStore.recordCompletion(session.players, session.dungeon.type)
    }

    private fun recordLegendaryEntry(session: Session) {
        DungeonStatsStore.incrementLegendary(session.players)
    }

    data class ProgressReadyResult(val success: Boolean, val message: Text)

    fun progressReady(player: ServerPlayerEntity): ProgressReadyResult {
        val session = sessions.values.firstOrNull { it.players.contains(player.uuid) }
            ?: return ProgressReadyResult(
                false,
                Text.literal("${player.gameProfile.name} is not in an active dungeon.").formatted(Formatting.RED)
            )
        if (session.status != DungeonStatus.AWAITING_PROGRESS) {
            val statusLabel = when (session.status) {
                DungeonStatus.RUNNING -> "is not ready for completion yet."
                DungeonStatus.SECRET_ROOM -> "has already entered the secret room."
                DungeonStatus.ENDED -> "has already ended."
                DungeonStatus.AWAITING_PROGRESS -> ""
            }
            if (statusLabel.isNotEmpty()) {
                return ProgressReadyResult(
                    false,
                    Text.literal("Dungeon \"${session.dungeon.name}\" $statusLabel").formatted(Formatting.RED)
                )
            }
        }
        val world = session.world
        val requirement = session.partySizeSnapshot.takeIf { it > 0 }
            ?: session.players.size.coerceAtLeast(1)
        if (requirement <= 0) {
            return ProgressReadyResult(
                false,
                Text.literal("Dungeon \"${session.dungeon.name}\" has no players to confirm.").formatted(Formatting.RED)
            )
        }
        if (session.readyPlayers.size >= requirement) {
            return ProgressReadyResult(
                false,
                Text.literal("Dungeon \"${session.dungeon.name}\" already met its ready requirement.").formatted(Formatting.RED)
            )
        }
        if (session.readyPlayers.contains(player.uuid)) {
            return ProgressReadyResult(
                false,
                Text.literal("${player.gameProfile.name} has already confirmed readiness for dungeon \"${session.dungeon.name}\".")
                    .formatted(Formatting.RED)
            )
        }
        val now = world.time
        val lastTick = session.readyCooldowns[player.uuid]
        if (lastTick != null && now - lastTick < 5L) {
            return ProgressReadyResult(
                false,
                Text.literal("Please wait before confirming again for dungeon \"${session.dungeon.name}\".").formatted(Formatting.RED)
            )
        }
        session.readyCooldowns[player.uuid] = now
        session.readyPlayers.add(player.uuid)
        val newCount = session.readyPlayers.size.coerceAtMost(requirement)
        val server = world.server
        val players = session.players.mapNotNull { server.playerManager.getPlayer(it) }
        val progressMessage = Text.literal(
            "${player.gameProfile.name} confirmed readiness $newCount/$requirement for dungeon \"${session.dungeon.name}\"."
        ).formatted(Formatting.GOLD)
        players.forEach { it.sendMessage(progressMessage, false) }
        println("[Dungeons] ${session.dungeon.name} ready progress ${player.gameProfile.name} $newCount/$requirement")
        if (newCount >= requirement) {
            val completionMessage = Text.literal(
                "Dungeon \"${session.dungeon.name}\" met ready requirement ($requirement)."
            ).formatted(Formatting.GOLD)
            players.forEach { it.sendMessage(completionMessage, false) }
            session.pendingDoorClosures.clear()
            session.rooms.forEach { openRoomDoors(world, it) }
            return if (session.legendary) {
                val legendaryMessage = Text.literal(
                    "Legendary run! Teleporting party of $requirement to Secret Room..."
                ).formatted(Formatting.LIGHT_PURPLE)
                players.forEach { it.sendMessage(legendaryMessage, false) }
                recordLegendaryEntry(session)
                val secretStarted = beginSecretRoom(session)
                if (!secretStarted) {
                    val fallback = Text.literal(
                        "No secret room available. Ending dungeon normally."
                    ).formatted(Formatting.RED)
                    players.forEach { it.sendMessage(fallback, false) }
                    recordDungeonCompletion(session)
                    finishDungeon(session)
                    ProgressReadyResult(true, fallback)
                } else {
                    ProgressReadyResult(true, legendaryMessage)
                }
            } else {
                val rewardsMessage = Text.literal(
                    "Dungeon \"${session.dungeon.name}\" complete. Distributing rewards..."
                ).formatted(Formatting.GOLD)
                players.forEach { it.sendMessage(rewardsMessage, false) }
                recordDungeonCompletion(session)
                finishDungeon(session)
                ProgressReadyResult(true, rewardsMessage)
            }
        }
        return ProgressReadyResult(true, progressMessage)
    }

    data class ActiveDungeonInfo(
        val name: String,
        val type: String,
        val leader: String,
        val partySize: Int,
        val legendary: Boolean,
        val status: DungeonStatus,
        val createdAt: Long
    )

    fun listActiveDungeons(): List<ActiveDungeonInfo> {
        return sessions.values
            .filter { it.status != DungeonStatus.ENDED }
            .map { session ->
                val leaderName = PartyService.lookupName(session.leaderId) ?: session.leaderName
                session.leaderName = leaderName
                val size = session.partySizeSnapshot.takeIf { it > 0 } ?: session.players.size
                ActiveDungeonInfo(
                    name = session.dungeon.name,
                    type = session.dungeon.type,
                    leader = leaderName,
                    partySize = size,
                    legendary = session.legendary,
                    status = session.status,
                    createdAt = session.createdAt
                )
            }
            .sortedBy { it.createdAt }
    }

    private fun killAllPokemonInDungeon(session: Session) {
        val world = session.world
        session.rooms.forEach { room ->
            killCobblemonInRoom(world, room)
            clearAlphazInDungeon(session, room)
        }
    }

    private data class DoorClosureResult(val powered: Boolean, val hadDoor: Boolean)

    private fun closeExistingDoorBlocks(world: ServerWorld, door: Door): DoorClosureResult {
        var powered = false
        var hadDoor = false
        val minX = minOf(door.min.x, door.max.x)
        val maxX = maxOf(door.min.x, door.max.x)
        val minY = minOf(door.min.y, door.max.y)
        val maxY = maxOf(door.min.y, door.max.y)
        val minZ = minOf(door.min.z, door.max.z)
        val maxZ = maxOf(door.min.z, door.max.z)
        val mutablePos = BlockPos.Mutable()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    mutablePos.set(x, y, z)
                    if (!world.isChunkLoadedSafe(mutablePos)) continue
                    val currentState = world.getBlockState(mutablePos)
                    val doorBlock = currentState.block as? DoorBlock ?: continue
                    val half = currentState.getOrNull(DoorBlock.HALF) ?: DoubleBlockHalf.LOWER
                    if (half != DoubleBlockHalf.LOWER) continue
                    hadDoor = true
                    val basePos = mutablePos.toImmutable()
                    val poweredBefore = currentState.getOrNull(DoorBlock.POWERED) ?: false
                    val receivingPower = world.isReceivingRedstonePower(basePos) || world.isReceivingRedstonePower(basePos.up())
                    val closedBottom = currentState
                        .withIfExists(DoorBlock.OPEN, false)
                        .withIfExists(DoorBlock.POWERED, false)
                    if (world.getBlockState(basePos) != closedBottom) {
                        world.setBlockState(basePos, closedBottom, DOOR_UPDATE_FLAGS)
                    }
                    logDoorClosing(basePos, DoubleBlockHalf.LOWER.asString(), poweredBefore || receivingPower)
                    val upperPos = basePos.up()
                    val upperState = world.getBlockState(upperPos)
                    if (upperState.block is DoorBlock) {
                        val upperPoweredBefore = upperState.getOrNull(DoorBlock.POWERED) ?: false
                        val closedUpper = upperState
                            .withIfExists(DoorBlock.OPEN, false)
                            .withIfExists(DoorBlock.POWERED, false)
                        if (world.getBlockState(upperPos) != closedUpper) {
                            world.setBlockState(upperPos, closedUpper, DOOR_UPDATE_FLAGS)
                        }
                        logDoorClosing(upperPos, DoubleBlockHalf.UPPER.asString(), upperPoweredBefore || receivingPower)
                        powered = powered || upperPoweredBefore
                    }
                    powered = powered || poweredBefore || receivingPower
                }
            }
        }
        return DoorClosureResult(powered, hadDoor)
    }

    private fun placeDoorPlug(world: ServerWorld, pos: BlockPos) {
        if (!world.isChunkLoadedSafe(pos)) return
        val current = world.getBlockState(pos)
        if (!current.isAir && !current.isOf(Blocks.BARRIER)) return
        if (!current.isOf(Blocks.BARRIER)) {
            world.setBlockState(pos, Blocks.BARRIER.defaultState, DOOR_UPDATE_FLAGS)
        }
        logDoorClosing(pos, "plug", true)
    }

    private fun logDoorClosing(pos: BlockPos, halfLabel: String, powered: Boolean) {
        println("[Dungeons] closingDoor at ${pos.x},${pos.y},${pos.z} half=$halfLabel powered=$powered")
    }

    private fun roomLabel(session: Session, room: Room): String {
        val index = session.rooms.indexOf(room).takeIf { it >= 0 } ?: -1
        val roomId = if (index >= 0) index.toString() else "?"
        return "${session.dungeon.name}#$roomId"
    }

    private fun clearAlphazInDungeon(session: Session, room: Room) {
        val tracked = room.trackedAlphaUuids
        if (tracked.isEmpty()) return
        val world = session.world
        val label = roomLabel(session, room)
        val snapshot = tracked.toList()
        println("[Dungeons] room=$label begin cleanup candidates=${snapshot.size}")
        var cleaned = 0
        var pruned = 0
        var skipped = 0
        for (uuid in snapshot) {
            val entity = world.getEntity(uuid)
            if (entity == null) {
                tracked.remove(uuid)
                pruned++
                println("[Dungeons] room=$label prune=null/unloaded uuid=$uuid")
                continue
            }
            val living = entity as? LivingEntity
            if (living == null) {
                tracked.remove(uuid)
                pruned++
                println("[Dungeons] room=$label prune=not_living uuid=$uuid")
                continue
            }
            if (entity.isRemoved || !living.isAlive) {
                tracked.remove(uuid)
                pruned++
                println("[Dungeons] room=$label prune=dead/removed uuid=$uuid")
                continue
            }
            if (!isInsideRoom(entity.blockPos, room)) {
                tracked.remove(uuid)
                skipped++
                println("[Dungeons] room=$label skip=not_in_room uuid=$uuid")
                continue
            }
            try {
                entity.discard()
                cleaned++
                println("[Dungeons] room=$label clean=removed uuid=$uuid")
            } catch (ex: Exception) {
                println("[Dungeons] room=$label error uuid=$uuid msg=${ex.message ?: ex::class.simpleName}")
            } finally {
                tracked.remove(uuid)
            }
        }
        println("[Dungeons] room=$label cleanup done cleaned=$cleaned pruned=$pruned skipped=$skipped")
    }

    private fun BlockState.withIfExists(property: Property<Boolean>, value: Boolean): BlockState {
        return if (this.contains(property)) this.with(property, value) else this
    }

    private fun <T : Comparable<T>> BlockState.getOrNull(property: Property<T>): T? {
        return if (this.contains(property)) this.get(property) else null
    }

    private fun ServerWorld.isChunkLoadedSafe(pos: BlockPos): Boolean {
        return this.isChunkLoaded(pos.x shr 4, pos.z shr 4)
    }

    private fun handleBossRoomEntry(session: Session, world: ServerWorld, initiator: ServerPlayerEntity) {
        if (session.bossStarted) return
        val bossIdx = session.bossRoomIndex ?: return
        val room = session.rooms.getOrNull(bossIdx) ?: return
        startBossEncounter(session, world, room, initiator)
    }

    private fun startBossEncounter(
        session: Session,
        world: ServerWorld,
        room: Room,
        initiator: ServerPlayerEntity? = null
    ) {
        if (session.bossStarted) return
        session.bossStarted = true
        session.bossCompleted = false
        session.bossPaused = false
        session.bossPauseStartTick = null
        session.bossFailAtTick = null
        session.lastBossRoomCount = -1
        session.bossEntitySpawned = false
        session.bossEntityUuid = null
        val sessionId = session.dungeon.name
        val initiatorId = initiator?.uuid
        val crafterPos = session.bossCrafterState?.pos ?: session.bossSpawnPos ?: room.center

        session.bossRoomIndex?.let { bossRoomIdx ->
            schedulePartySpawnUpdate(
                session = session,
                world = world,
                roomIdx = bossRoomIdx,
                preferredPlayerId = initiatorId,
                fallbackPos = crafterPos
            )
        }
        if (initiator != null) {
            val initiatorId = initiator.uuid
            val fallbackPos = initiator.pos
            val fallbackYaw = initiator.yaw
            val fallbackPitch = initiator.pitch
            val playerIds = session.players.toList()
            schedule(world, RAID_TELEPORT_DELAY_TICKS, sessionId) {
                val current = sessions[sessionId] ?: return@schedule
                if (current !== session) return@schedule
                val server = world.server
                val initiatorPlayer = server.playerManager.getPlayer(initiatorId)
                    ?.takeIf { it.isAlive && !it.isSpectator }
                val targetPos = initiatorPlayer?.pos ?: fallbackPos
                val targetYaw = initiatorPlayer?.yaw ?: fallbackYaw
                val targetPitch = initiatorPlayer?.pitch ?: fallbackPitch
                playerIds.mapNotNull { server.playerManager.getPlayer(it) }
                    .filter { it.isAlive && !it.isSpectator }
                    .forEach { player ->
                        player.teleport(world, targetPos.x, targetPos.y, targetPos.z, targetYaw, targetPitch)
                    }
            }
        }
        val spawnPos = session.bossSpawnPos ?: room.center
        spawnBossPokemon(session, world, spawnPos)
        schedule(world, ROOM_DOOR_CLOSE_DELAY_TICKS, session.dungeon.name) {
            val current = sessions[session.dungeon.name]
            if (current != session) return@schedule
            current.rooms.forEach { closeRoomDoors(world, it) }
            lockBossDoors(world, current)
        }
        session.bossTotalWaves = determineBossWaveLimit(session)
        session.bossWavesSpawned = 0
        if (session.bossTotalWaves > 0) {
            triggerBossWave(session, world)
        } else {
            session.bossNextSummonTick = 0L
        }
    }

    private fun spawnBossPokemon(session: Session, world: ServerWorld, pos: BlockPos) {
        val pokemonList = session.difficulties.boss.pokemon
        if (pokemonList.isEmpty()) {
            println("[Dungeons] No boss Pokémon configured for ${session.dungeon.type}.")
            return
        }
        val index = ThreadLocalRandom.current().nextInt(pokemonList.size)
        val pokemon = pokemonList[index]
        val source = world.server.commandSource
            .withWorld(world)
            .withPosition(Vec3d(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5))
        val dungeonType = session.dungeon.type.trim().lowercase(Locale.ROOT)
        val size = (session.difficulties.boss.size ?: 1.0).coerceAtLeast(0.1)
        val sizeArg = formatSizeArgument(size)
        val command = if (dungeonType.isNotEmpty()) {
            formatBossSpawnCommand(dungeonType, pokemon, pos, sizeArg)
        } else {
            println("[Dungeons] Dungeon '${session.dungeon.name}' has no type; spawning boss without type parameter.")
            formatBossSpawnCommand(null, pokemon, pos, sizeArg)
        }
        world.server.commandManager.executeWithPrefix(source, command)
        if (dungeonType.isNotEmpty()) {
            println(
                "[Dungeons] Spawned boss '$pokemon' for ${session.dungeon.name} (type=$dungeonType) at ${pos.x},${pos.y},${pos.z}."
            )
        } else {
            println("[Dungeons] Spawned boss '$pokemon' for ${session.dungeon.name} at ${pos.x},${pos.y},${pos.z}.")
        }
        session.bossRoomIndex?.let { roomIdx ->
            scheduleBossEntityRefresh(session, world, roomIdx, BOSS_CACHE_REFRESH_DELAY_TICKS)
        }
    }

    private fun triggerBossWave(session: Session, world: ServerWorld): Boolean {
        if (session.bossWavesSpawned >= session.bossTotalWaves) return false
        if (session.bossGoldSpawnPoints.isEmpty()) return false
        val random = ThreadLocalRandom.current()
        val spawnQueue = session.bossGoldSpawnPoints.toMutableList().apply { shuffle(random) }
        val difficulties = listOf(
            session.difficulties.weak,
            session.difficulties.medium,
            session.difficulties.hard
        )
        val available = difficulties.filter { it.pokemon.isNotEmpty() }
        if (available.isEmpty()) return false
        val spawns = mutableListOf<Pair<DifficultyConfig, String>>()
        repeat(BOSS_WAVE_MON_COUNT) {
            if (spawnQueue.isEmpty()) return@repeat
            val difficulty = available[random.nextInt(available.size)]
            val options = difficulty.pokemon
            if (options.isEmpty()) return@repeat
            val pos = spawnQueue.removeAt(0)
            val pokemon = options[random.nextInt(options.size)]
            val source = world.server.commandSource
                .withWorld(world)
                .withPosition(Vec3d(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5))
            val command = formatRaidSpawnCommand(session, difficulty, pokemon, pos)
            world.server.commandManager.executeWithPrefix(source, command)
            spawns += difficulty to pokemon
        }
        if (spawns.isEmpty()) {
            session.bossNextSummonTick = 0L
            return false
        }

        session.bossWavesSpawned++
        session.bossNextSummonTick = if (session.bossWavesSpawned < session.bossTotalWaves) {
            world.time + BOSS_WAVE_INTERVAL_TICKS
        } else {
            0L
        }

        val summary = spawns
            .groupBy { it.first }
            .entries
            .sortedBy { difficultyPriority(session, it.key) }
            .joinToString(", ") { (difficulty, entries) ->
                val names = entries.joinToString(", ") { it.second }
                "${entries.size} ${difficulty.displayName} Pokémon ($names)"
            }
        println("[Dungeons] Boss wave spawned in ${session.dungeon.name}: $summary.")
        return true
    }

    private fun findBossEntity(world: ServerWorld, session: Session, room: Room): LivingEntity? {
        session.bossEntityUuid?.let { uuid ->
            val entity = world.getEntity(uuid) as? LivingEntity
            if (entity != null && entity.isAlive && !entity.isRemoved && entity.boundingBox.intersects(room.boundingBox)) {
                return entity
            }
            session.bossEntityUuid = null
        }
        val boss = world.getEntitiesByClass(LivingEntity::class.java, room.boundingBox) { living ->
            !living.isRemoved && living.isAlive && ALPHAZ_BOSS_TAG in living.commandTags
        }.firstOrNull()
        session.bossEntityUuid = boss?.uuid
        return boss
    }

    private fun updateBossBarProgress(world: ServerWorld, session: Session): Boolean {
        if (!session.bossStarted) {
            resetBossBar(session)
            return false
        }
        val room = session.bossRoomIndex?.let { idx -> session.rooms.getOrNull(idx) }
        if (room == null) {
            resetBossBar(session)
            return false
        }
        val boss = findBossEntity(world, session, room)
        if (boss != null) {
            if (!session.bossEntitySpawned) {
                session.bossEntitySpawned = true
            }
            val maxHealth = boss.maxHealth
            val percent = if (maxHealth > 0f) {
                (boss.health / maxHealth).coerceIn(0f, 1f)
            } else {
                0f
            }
            val title = (boss.displayName ?: boss.name).string
            session.dungeonBossBar.setColor(BossBar.Color.RED)
            session.dungeonBossBar.setName(Text.literal(title))
            session.dungeonBossBar.setPercent(percent)
            return true
        }
        if (!session.bossEntitySpawned) {
            return false
        }
        resetBossBar(session)
        return false
    }

    private fun resetBossBar(session: Session) {
        session.dungeonBossBar.setColor(session.defaultBossBarColor)
        session.dungeonBossBar.setName(Text.literal(session.defaultBossBarTitle))
        session.dungeonBossBar.setPercent(1.0f)
    }

    private fun startNextWave(
        session: Session,
        roomIdx: Int,
        world: ServerWorld,
        state: RaidState
    ) {
        if (state.completed) return
        val spawnPoints = session.raidSpawnPoints[roomIdx] ?: return
        if (spawnPoints.isEmpty()) return
        val room = session.rooms.getOrNull(roomIdx) ?: return
        val waveIndex = state.currentWave
        if (waveIndex >= state.maxWaves) return
        val wave = WAVE_CONFIG[waveIndex.coerceAtMost(WAVE_CONFIG.lastIndex)]
        val server = world.server
        val baseSource = server.commandSource.withWorld(world)
        val spawnPlan = mutableListOf<SpawnEntry>()
        repeat(wave.count) { idx ->
            val basePos = spawnPoints[idx % spawnPoints.size]
            val pos = randomizeRaidSpawn(basePos, room)
            val difficulty = selectDifficultyForSpawn(session, state)
            val pokemonList = difficulty.pokemon
            if (pokemonList.isEmpty()) {
                return@repeat
            }
            val pokemonIndex = ThreadLocalRandom.current().nextInt(pokemonList.size)
            val pokemon = pokemonList[pokemonIndex]
            spawnPlan += SpawnEntry(pos, difficulty, pokemon)
            when {
                difficulty === session.difficulties.hard -> state.hardSpawned++
                difficulty === session.difficulties.medium -> state.mediumSpawned++
            }
        }
        if (spawnPlan.isEmpty()) {
            state.nextCheckTick = world.time + GRACE_TICKS
            return
        }
        spawnPlan.forEachIndexed { index, entry ->
            val delay = ALPHA_SPAWN_DELAY_TICKS * index.toLong()
            schedule(world, delay, session.dungeon.name) {
                val source = baseSource.withPosition(
                    Vec3d(entry.position.x + 0.5, entry.position.y.toDouble(), entry.position.z + 0.5)
                )
                val command = formatRaidSpawnCommand(session, entry.difficulty, entry.pokemon, entry.position)
                server.commandManager.executeWithPrefix(source, command)
            }
        }
        val summary = spawnPlan
            .groupBy { it.difficulty }
            .entries
            .sortedBy { difficultyPriority(session, it.key) }
            .joinToString(", ") { (difficulty, entries) ->
                val names = entries.joinToString(", ") { it.pokemon }
                "${entries.size} ${difficulty.displayName} Pokémon ($names)"
            }
        println("[Dungeons] Spawned raid wave ${waveIndex + 1} in room ${roomIdx + 1}: $summary at ${spawnPoints.size} spawn points.")
        state.waveInProgress = true
        state.currentWave = waveIndex + 1
        val additionalDelay = if (spawnPlan.size <= 1) 0L else ALPHA_SPAWN_DELAY_TICKS * (spawnPlan.size - 1).toLong()
        state.nextCheckTick = world.time + GRACE_TICKS + additionalDelay
        session.raidEntityUuids.getOrPut(roomIdx) { mutableSetOf() }.clear()
        val trackingDelay = additionalDelay + RAID_CACHE_REFRESH_DELAY_TICKS
        scheduleRaidCacheRefresh(session, world, roomIdx, trackingDelay)
    }

    private fun scheduleRaidCacheRefresh(
        session: Session,
        world: ServerWorld,
        roomIdx: Int,
        delay: Long
    ) {
        val sessionId = session.dungeon.name
        val effectiveDelay = delay.coerceAtLeast(1L)
        schedule(world, effectiveDelay, sessionId) {
            val current = sessions[sessionId] ?: return@schedule
            if (current !== session) return@schedule
            val room = current.rooms.getOrNull(roomIdx) ?: return@schedule
            val cache = current.raidEntityUuids.getOrPut(roomIdx) { mutableSetOf() }
            cache.clear()
            cache.addAll(collectRaidEntityUuids(world, room))
        }
    }

    private fun scheduleBossEntityRefresh(
        session: Session,
        world: ServerWorld,
        roomIdx: Int,
        delay: Long
    ) {
        val sessionId = session.dungeon.name
        val effectiveDelay = delay.coerceAtLeast(1L)
        schedule(world, effectiveDelay, sessionId) {
            val current = sessions[sessionId] ?: return@schedule
            if (current !== session) return@schedule
            val room = current.rooms.getOrNull(roomIdx) ?: return@schedule
            val boss = findBossEntity(world, current, room)
            if (boss != null) {
                current.bossEntityUuid = boss.uuid
                current.bossEntitySpawned = true
            }
        }
    }

    private fun selectDifficultyForSpawn(session: Session, state: RaidState): DifficultyConfig {
        val weak = session.difficulties.weak
        val medium = session.difficulties.medium
        val hard = session.difficulties.hard

        val options = mutableListOf<Pair<DifficultyConfig, Double>>()
        if (WEAK_WEIGHT > 0.0 && weak.pokemon.isNotEmpty()) {
            options += weak to WEAK_WEIGHT
        }
        if (
            MEDIUM_WEIGHT > 0.0 &&
            medium.pokemon.isNotEmpty() &&
            state.mediumSpawned < MAX_MEDIUM_PER_RAID
        ) {
            options += medium to MEDIUM_WEIGHT
        }
        if (
            HARD_WEIGHT > 0.0 &&
            hard.pokemon.isNotEmpty() &&
            state.hardSpawned < MAX_HARD_PER_RAID
        ) {
            options += hard to HARD_WEIGHT
        }
        if (options.isEmpty()) {
            return when {
                weak.pokemon.isNotEmpty() -> weak
                medium.pokemon.isNotEmpty() && state.mediumSpawned < MAX_MEDIUM_PER_RAID -> medium
                hard.pokemon.isNotEmpty() && state.hardSpawned < MAX_HARD_PER_RAID -> hard
                medium.pokemon.isNotEmpty() -> medium
                hard.pokemon.isNotEmpty() -> hard
                else -> weak
            }
        }
        val totalWeight = options.sumOf { it.second }
        val roll = ThreadLocalRandom.current().nextDouble(totalWeight)
        var cumulative = 0.0
        for ((difficulty, weight) in options) {
            cumulative += weight
            if (roll <= cumulative) {
                return difficulty
            }
        }
        return options.last().first
    }

    private fun difficultyPriority(session: Session, difficulty: DifficultyConfig): Int = when {
        difficulty === session.difficulties.hard -> 0
        difficulty === session.difficulties.medium -> 1
        difficulty === session.difficulties.weak -> 2
        else -> 3
    }

    private fun finishRaid(
        session: Session,
        roomIdx: Int,
        world: ServerWorld,
        state: RaidState,
        forceCleanup: Boolean = false
    ) {
        clearRaidState(session, roomIdx, world, removeEntities = forceCleanup)
        openRoomDoors(world, session.rooms[roomIdx])
        state.completed = true
    }

    private fun hasRaidEntities(world: ServerWorld, session: Session, roomIdx: Int): Boolean {
        val room = session.rooms.getOrNull(roomIdx) ?: return false
        val cache = session.raidEntityUuids.getOrPut(roomIdx) { mutableSetOf() }
        pruneRaidEntityCache(world, room, cache)
        if (cache.isNotEmpty()) {
            return true
        }
        cache.addAll(collectRaidEntityUuids(world, room))
        return cache.isNotEmpty()
    }

    private fun pruneRaidEntityCache(world: ServerWorld, room: Room, cache: MutableSet<UUID>) {
        if (cache.isEmpty()) return
        val iterator = cache.iterator()
        while (iterator.hasNext()) {
            val uuid = iterator.next()
            val entity = world.getEntity(uuid) as? LivingEntity
            if (entity == null || entity.isRemoved || !entity.isAlive || !isInsideRoom(entity.blockPos, room)) {
                iterator.remove()
            }
        }
    }

    private fun collectRaidEntityUuids(world: ServerWorld, room: Room): Set<UUID> {
        return world.getEntitiesByClass(LivingEntity::class.java, room.boundingBox) { living ->
            !living.isRemoved && living.isAlive && ALPHAZ_TAG in living.commandTags
        }.mapTo(mutableSetOf()) { it.uuid }
    }

    private fun removeRaidEntities(session: Session, world: ServerWorld, roomIdx: Int, room: Room) {
        val raidEntities = world.getEntitiesByClass(LivingEntity::class.java, room.boundingBox) { living ->
            !living.isRemoved && ALPHAZ_TAG in living.commandTags
        }
        var removed = 0
        raidEntities.forEach { entity ->
            val discarded = runCatching { entity.discard() }
            if (discarded.isSuccess) {
                removed++
            } else {
                discarded.exceptionOrNull()?.let {
                    println("[Dungeons] removeRaidEntities: failed to discard entity ${entity.uuid}: ${it.message}")
                }
            }
        }
        session.raidEntityUuids.remove(roomIdx)
        if (removed > 0) {
            println("[Dungeons] removeRaidEntities: removed=$removed in room ${roomIdx + 1}")
        }
    }

    private fun killCobblemonInRoom(world: ServerWorld, room: Room) {
        val server = world.server
        val x = room.min.x
        val y = room.min.y
        val z = room.min.z
        val dx = (room.max.x - room.min.x).coerceAtLeast(0)
        val dy = (room.max.y - room.min.y).coerceAtLeast(0)
        val dz = (room.max.z - room.min.z).coerceAtLeast(0)
        val command = "/kill @e[type=cobblemon:pokemon,x=$x,y=$y,z=$z,dx=$dx,dy=$dy,dz=$dz]"
        val source = server.commandSource
            .withWorld(world)
            .withPosition(Vec3d(x.toDouble(), y.toDouble(), z.toDouble()))
        server.commandManager.executeWithPrefix(source, command)
    }

    private fun createDifficultySet(type: String): DifficultySet {
        val config = DungeonTypeConfig.ensureType(type)
        return DifficultySet(
            weak = DifficultyConfig("weak", "Weak", config.weak.toList()),
            medium = DifficultyConfig("medium", "Medium", config.medium.toList()),
            hard = DifficultyConfig("strong", "Hard", config.hard.toList()),
            boss = DifficultyConfig("boss", "Boss", config.boss.toList(), config.bossSize)
        )
    }

    private fun assignRaidSpawnPoints(
        rooms: List<Room>,
        goldBlocks: List<BlockPos>,
        bossRoomIdxToExclude: Int?
    ): Map<Int, List<BlockPos>> {
        val result = mutableMapOf<Int, MutableList<BlockPos>>()
        goldBlocks.forEach { pos ->
            val roomIdx = rooms.indexOfFirst { room ->
                pos.x in room.min.x..room.max.x &&
                        pos.y in room.min.y..room.max.y &&
                        pos.z in room.min.z..room.max.z
            }
            if (roomIdx != -1 && roomIdx != bossRoomIdxToExclude) {
                result.getOrPut(roomIdx) { mutableListOf() }.add(pos)
            }
        }
        return result.mapValues { it.value.toList() }
    }

    private fun seedDispensers(world: ServerWorld, dispensers: List<BlockPos>) {
        if (dispensers.isEmpty()) return
        val random = ThreadLocalRandom.current()
        dispensers.forEach { pos ->
            val blockEntity = world.getBlockEntity(pos) as? DispenserBlockEntity ?: return@forEach
            val emptySlot = (0 until blockEntity.size()).firstOrNull { slot ->
                blockEntity.getStack(slot).isEmpty
            } ?: return@forEach
            val stack = createRandomArrowStack(world, random)
            if (!stack.isEmpty) {
                blockEntity.setStack(emptySlot, stack)
                blockEntity.markDirty()
                val state = world.getBlockState(pos)
                world.updateListeners(pos, state, state, 3)
            }
        }
    }

    private fun createRandomArrowStack(world: ServerWorld, random: ThreadLocalRandom): ItemStack {
        return when (random.nextInt(3)) {
            0 -> ItemStack(Items.ARROW)
            1 -> createTippedArrowStack(world, "minecraft:poison")
            else -> createTippedArrowStack(world, "minecraft:slow_falling")
        }
    }

    private fun createTippedArrowStack(world: ServerWorld, potionId: String): ItemStack {
        val nbt = NbtCompound()
        nbt.putString("id", "minecraft:tipped_arrow")
        nbt.putByte("Count", 1.toByte())
        val components = NbtCompound()
        val potionComponent = NbtCompound()
        potionComponent.putString("potion", potionId)
        components.put("minecraft:potion_contents", potionComponent)
        nbt.put("components", components)
        return ItemStack.fromNbt(world.registryManager, nbt).orElse(ItemStack.EMPTY)
    }

    private fun removeBossCrafter(world: ServerWorld, pos: BlockPos?): StoredBlockState? {
        if (pos == null) return null
        val state = world.getBlockState(pos)
        if (state.isAir) return null
        val stored = StoredBlockState(pos, state)
        world.removeBlockEntity(pos)
        world.setBlockState(pos, Blocks.AIR.defaultState)
        return stored
    }

    private fun replaceEmeraldsWithChests(
        world: ServerWorld,
        emeraldBlocks: List<BlockPos>,
        lootTable: DungeonLootTable,
        embrynsChestPlan: EmbrynsChestPlan?,
        soothingIceChestPlan: SoothingIceChestPlan?,
        flameShieldChance: Double
    ): Set<BlockPos> {
        if (emeraldBlocks.isEmpty()) return emptySet()
        val replaced = mutableSetOf<BlockPos>()
        val chestState = Blocks.BARREL.defaultState
        emeraldBlocks.forEach { pos ->
            val changed = world.setBlockState(pos, chestState)
            val state = world.getBlockState(pos)
            if (!changed && !state.isOf(Blocks.BARREL)) {
                return@forEach
            }
            replaced.add(pos)
            fillChestWithLoot(world, pos, lootTable, embrynsChestPlan, soothingIceChestPlan, flameShieldChance)
        }
        return replaced
    }

    private fun initializeGildedChests(
        world: ServerWorld,
        rooms: List<Room>,
        positions: List<BlockPos>
    ): Pair<MutableMap<BlockPos, GildedChestState>, Set<BlockPos>> {
        if (positions.isEmpty()) return mutableMapOf<BlockPos, GildedChestState>() to emptySet()
        val chestState = resolveYellowGildedChestState() ?: return mutableMapOf<BlockPos, GildedChestState>() to emptySet()
        val chests = mutableMapOf<BlockPos, GildedChestState>()
        val replaced = mutableSetOf<BlockPos>()
        positions.distinct().forEach { rawPos ->
            val pos = rawPos.toImmutable()
            if (!world.isChunkLoadedSafe(pos)) {
                println("[Dungeons] Skipped gilded chest at ${pos.x},${pos.y},${pos.z}: chunk not loaded.")
                return@forEach
            }
            val currentState = world.getBlockState(pos)
            if (!currentState.isOf(Blocks.NETHERITE_BLOCK) && !currentState.isOf(Blocks.SMOKER)) {
                println("[Dungeons] Skipped gilded chest at ${pos.x},${pos.y},${pos.z}: block changed before start.")
                return@forEach
            }
            val roomIdx = rooms.indexOfFirst { room ->
                pos.x in room.min.x..room.max.x &&
                        pos.y in room.min.y..room.max.y &&
                        pos.z in room.min.z..room.max.z
            }
            if (roomIdx == -1) {
                println("[Dungeons] Skipped gilded chest at ${pos.x},${pos.y},${pos.z}: outside dungeon rooms.")
                return@forEach
            }
            world.removeBlockEntity(pos)
            val changed = world.setBlockState(pos, chestState)
            val placed = world.getBlockState(pos)
            if (!changed && !placed.isOf(chestState.block)) {
                println("[Dungeons] Failed to arm gilded chest at ${pos.x},${pos.y},${pos.z}.")
                return@forEach
            }
            chests[pos] = GildedChestState(roomIdx)
            replaced.add(pos)
        }
        return chests to replaced
    }

    private fun fillChestWithLoot(
        world: ServerWorld,
        pos: BlockPos,
        lootTable: DungeonLootTable,
        embrynsChestPlan: EmbrynsChestPlan?,
        soothingIceChestPlan: SoothingIceChestPlan?,
        flameShieldChance: Double
    ) {
        val blockEntity = world.getBlockEntity(pos) ?: return
        val inventory = blockEntity as? Inventory ?: return
        val random = ThreadLocalRandom.current()
        val airChance = lootTable.airPercentage.coerceIn(0, 100)
        val lootEntries = lootTable.loot.toList()
        for (slot in 0 until inventory.size()) {
            inventory.setStack(slot, ItemStack.EMPTY)
            if (airChance > 0 && random.nextInt(100) < airChance) continue
            val entry = selectLootEntry(lootEntries, random) ?: continue
            val stack = createStackForEntry(entry, random)
            if (!stack.isEmpty) {
                inventory.setStack(slot, stack)
            }
        }
        val embrynsCount = embrynsChestPlan?.nextEmbrynsCount(random) ?: 0
        if (embrynsCount > 0) {
            placeSpecialItem(inventory, createEmbrynsStack(embrynsCount))
        }
        val soothingIceCount = soothingIceChestPlan?.nextSoothingIceCount(random) ?: 0
        if (soothingIceCount > 0) {
            placeSpecialItem(inventory, createSoothingIceStack(soothingIceCount))
        }
        val normalizedFlameChance = flameShieldChance.coerceAtLeast(0.0)
        if (normalizedFlameChance > 0 && random.nextDouble() < normalizedFlameChance) {
            placeSpecialItem(inventory, createFlameShieldStack())
        }
        blockEntity.markDirty()
        val state = world.getBlockState(pos)
        world.updateListeners(pos, state, state, 3)
    }

    private fun placeSpecialItem(inventory: Inventory, stack: ItemStack) {
        if (stack.isEmpty || inventory.size() <= 0) return
        for (slot in 0 until inventory.size()) {
            if (inventory.getStack(slot).isEmpty) {
                inventory.setStack(slot, stack)
                return
            }
        }
        val slot = (inventory.size() - 1).coerceAtLeast(0)
        inventory.setStack(slot, stack)
    }

    private fun selectLootEntry(
        entries: List<LootItemConfig>,
        random: ThreadLocalRandom
    ): LootItemConfig? {
        if (entries.isEmpty()) return null
        val start = if (entries.size == 1) 0 else random.nextInt(entries.size)
        for (i in entries.indices) {
            val entry = entries[(start + i) % entries.size]
            if (random.nextDouble(0.0, 100.0) < entry.chance) {
                return entry
            }
        }
        return null
    }

    private fun createStackForEntry(entry: LootItemConfig, random: ThreadLocalRandom): ItemStack {
        val id = Identifier.tryParse(entry.item) ?: run {
            println("Invalid loot item identifier '${entry.item}'.")
            return ItemStack.EMPTY
        }
        val item = Registries.ITEM.getOrEmpty(id).orElse(null) ?: run {
            println("Unknown loot item '${entry.item}' in dungeon loot config.")
            return ItemStack.EMPTY
        }
        val amount = if (entry.stackable) {
            val range = entry.stackRange
            val rolled = range?.roll(random) ?: 1
            rolled
        } else {
            1
        }
        val clamped = amount.coerceIn(1, item.maxCount)
        return ItemStack(item, clamped)
    }

    fun restoreDungeonActivatorBlocks(world: ServerWorld, dungeon: Dungeon) {
        if (dungeon.activatorBlocks.isEmpty() && dungeon.gildedChestPositions.isEmpty()) {
            return
        }
        for ((blockId, positions) in dungeon.activatorBlocks) {
            val identifier = Identifier.tryParse(blockId) ?: continue
            val block = Registries.BLOCK.getOrEmpty(identifier).orElse(null) ?: continue
            val state = block.defaultState
            for (dto in positions) {
                val pos = dto.toBlockPos()
                if (!world.isChunkLoadedSafe(pos)) continue
                world.setBlockState(pos, state, 3)
            }
        }
        if (dungeon.gildedChestPositions.isNotEmpty()) {
            val positions = dungeon.gildedChestPositions.map { it.toBlockPos() }
            restoreGildedChestBlocks(world, positions)
        }
    }

    fun clearDungeonDoorBlocks(world: ServerWorld, dungeon: Dungeon) {
        val scan = DungeonScanCache.scan(world, dungeon)
        if (scan !is DungeonScanner.ScanResult.Success) {
            println("[Dungeons] Unable to clear door blocks for '${dungeon.name}': dungeon scan failed.")
            return
        }
        val rooms = computeRooms(scan)
        if (rooms.isEmpty()) {
            return
        }
        rooms.forEach { room -> openRoomDoors(world, room) }
    }

    private fun restoreGoldBlocks(session: Session) {
        if (session.removedGoldBlocks.isEmpty()) return
        val world = session.world
        val state = Blocks.GOLD_BLOCK.defaultState
        session.removedGoldBlocks.forEach { pos ->
            world.setBlockState(pos, state)
        }
    }

    private fun restoreBossCrafter(session: Session) {
        val stored = session.bossCrafterState ?: return
        val world = session.world
        world.setBlockState(stored.pos, stored.state)
    }

    private fun restoreEmeraldBlocks(session: Session) {
        if (session.replacedEmeraldBlocks.isEmpty()) return
        val world = session.world
        val state = Blocks.EMERALD_BLOCK.defaultState
        session.replacedEmeraldBlocks.forEach { pos ->
            if (!world.isChunkLoadedSafe(pos)) return@forEach
            clearInventory(world, pos)
            world.removeBlockEntity(pos)
            world.setBlockState(pos, Blocks.AIR.defaultState)
            world.setBlockState(pos, state)
        }
    }

    private fun cleanupUnresolvedGildedChests(session: Session) {
        val world = session.world
        val yellowBlock = yellowGildedChestBlock
        if (yellowBlock != null && session.gildedChests.isNotEmpty()) {
            session.gildedChests.forEach { (pos, state) ->
                if (!world.isChunkLoadedSafe(pos)) return@forEach
                val blockState = world.getBlockState(pos)
                if (!state.resolved && blockState.isOf(yellowBlock)) {
                    clearInventory(world, pos)
                    world.removeBlockEntity(pos)
                    world.setBlockState(pos, Blocks.AIR.defaultState)
                }
            }
        }
        session.gildedChests.clear()

        val configuredPositions = session.dungeon.gildedChestPositions.map { it.toBlockPos() }
        val allPositions = LinkedHashSet<BlockPos>().apply {
            addAll(session.gildedChestPositions)
            addAll(configuredPositions)
        }
        restoreGildedChestBlocks(world, allPositions)
    }

    private fun restoreGildedChestBlocks(world: ServerWorld, positions: Collection<BlockPos>) {
        if (positions.isEmpty()) return
        val netheriteState = Blocks.NETHERITE_BLOCK.defaultState
        positions.forEach { pos ->
            if (!world.isChunkLoadedSafe(pos)) return@forEach
            clearInventory(world, pos)
            world.removeBlockEntity(pos)
            world.setBlockState(pos, netheriteState, 3)
        }
    }

    private fun updateBossBars(
        world: ServerWorld,
        session: Session,
        onlinePlayers: List<ServerPlayerEntity>
    ) {
        val bossAlive = updateBossBarProgress(world, session)
        val raidPlayers = mutableMapOf<Int, MutableList<ServerPlayerEntity>>()
        val bossTargets = mutableListOf<ServerPlayerEntity>()
        val bossRoom = session.bossRoomIndex?.let { idx -> session.rooms.getOrNull(idx) }

        onlinePlayers.forEach { player ->
            if (player.serverWorld != world || player.isSpectator) {
                return@forEach
            }
            val raidIdx = session.playerRaidRooms[player.uuid]
            if (raidIdx != null && session.raidStates[raidIdx]?.active == true) {
                raidPlayers.getOrPut(raidIdx) { mutableListOf() }.add(player)
            } else if (bossAlive && bossRoom != null && isInsideRoom(player.blockPos, bossRoom)) {
                bossTargets.add(player)
            }
        }

        if (bossAlive) {
            val targetUuids = bossTargets.map { it.uuid }.toSet()
            session.dungeonBossBar.players.toList().forEach { existing ->
                if (existing.uuid !in targetUuids) {
                    session.dungeonBossBar.removePlayer(existing)
                }
            }
            bossTargets.forEach { player ->
                if (!session.dungeonBossBar.players.contains(player)) {
                    session.dungeonBossBar.addPlayer(player)
                }
            }
            session.dungeonBossBar.setVisible(targetUuids.isNotEmpty())
        } else {
            session.dungeonBossBar.players.toList().forEach { session.dungeonBossBar.removePlayer(it) }
            session.dungeonBossBar.setVisible(false)
        }

        val activeRaidRooms = mutableSetOf<Int>()
        raidPlayers.forEach { (roomIdx, playersInRaid) ->
            val bar = session.raidBossBars.getOrPut(roomIdx) { createRaidBossBar() }
            activeRaidRooms.add(roomIdx)
            val targetUuids = playersInRaid.map { it.uuid }.toSet()
            bar.players.toList().forEach { existing ->
                if (existing.uuid !in targetUuids) {
                    bar.removePlayer(existing)
                }
            }
            playersInRaid.forEach { player ->
                if (!bar.players.contains(player)) {
                    bar.addPlayer(player)
                }
            }
            val remaining = countRaidEntities(world, session, roomIdx)
            bar.setName(Text.literal("Pokemon Left in Raid: $remaining"))
            bar.setColor(BossBar.Color.RED)
            bar.setVisible(true)
        }

        session.raidBossBars.forEach { (roomIdx, bar) ->
            if (roomIdx !in activeRaidRooms) {
                bar.players.toList().forEach { bar.removePlayer(it) }
                bar.setVisible(false)
            }
        }
    }

    private fun replaceBossBarPlayer(session: Session, old: ServerPlayerEntity, new: ServerPlayerEntity) {
        session.dungeonBossBar.removePlayer(old)
        session.raidBossBars.values.forEach { it.removePlayer(old) }
        if (session.dungeonBossBar.isVisible) {
            session.dungeonBossBar.addPlayer(new)
        }
        session.secretRoomState?.bossBar?.let { bar ->
            if (bar.players.contains(old)) {
                bar.removePlayer(old)
            }
            if (!bar.players.contains(new)) {
                bar.addPlayer(new)
            }
        }
    }

    private fun clearBossBars(session: Session) {
        session.dungeonBossBar.players.toList().forEach { session.dungeonBossBar.removePlayer(it) }
        session.dungeonBossBar.setVisible(false)
        session.raidBossBars.values.forEach { bar ->
            bar.players.toList().forEach { bar.removePlayer(it) }
            bar.setVisible(false)
        }
    }

    private fun createDungeonBossBar(dungeon: Dungeon): ServerBossBar {
        val displayType = formatDungeonType(dungeon.type).ifBlank { dungeon.type }
        return ServerBossBar(
            Text.literal("$displayType Dungeon"),
            BossBar.Color.BLUE,
            BossBar.Style.PROGRESS
        ).apply {
            setPercent(1.0f)
            setVisible(false)
        }
    }

    private fun randomizeRaidSpawn(base: BlockPos, room: Room): BlockPos {
        val random = ThreadLocalRandom.current()
        val offsetX = if (random.nextBoolean()) 2 else -2
        val offsetZ = if (random.nextBoolean()) 2 else -2
        val clampedX = (base.x + offsetX).coerceIn(room.min.x, room.max.x)
        val clampedZ = (base.z + offsetZ).coerceIn(room.min.z, room.max.z)
        return BlockPos(clampedX, base.y, clampedZ)
    }

    private fun formatRaidSpawnCommand(
        session: Session,
        difficulty: DifficultyConfig,
        pokemon: String,
        pos: BlockPos
    ): String {
        val shiny = session.legendary && ThreadLocalRandom.current().nextDouble() < 0.2
        val shinyFlag = if (shiny) "true" else "false"
        val parsed = parsePokemonCommand(pokemon)
        val species = parsed.name.ifBlank { pokemon.trim() }
        val builder = StringBuilder()
        builder.append("/alphaz dungeon ")
            .append(difficulty.command)
            .append(' ')
            .append(species)
            .append(' ')
            .append(shinyFlag)
            .append(' ')
            .append(pos.x)
            .append(' ')
            .append(pos.y)
            .append(' ')
            .append(pos.z)
        appendAttribute(builder, parsed.attribute)
        return builder.toString()
    }

    private fun formatBossSpawnCommand(
        dungeonType: String?,
        pokemon: String,
        pos: BlockPos,
        sizeArg: String
    ): String {
        val parsed = parsePokemonCommand(pokemon)
        val species = parsed.name.ifBlank { pokemon.trim() }
        val builder = StringBuilder("/alphaz dungeon boss ")
        dungeonType?.takeIf { it.isNotBlank() }?.let {
            builder.append(it.trim()).append(' ')
        }
        builder.append(species)
            .append(' ')
            .append(pos.x)
            .append(' ')
            .append(pos.y)
            .append(' ')
            .append(pos.z)
            .append(' ')
            .append(sizeArg)
        appendAttribute(builder, parsed.attribute)
        return builder.toString()
    }

    private fun beginSecretRoom(session: Session): Boolean {
        if (session.secretRoomState != null) return true
        val world = session.world
        val type = session.dungeon.type
        val currentWorldKey = world.registryKey.value.toString()
        val definition = SecretRoomManager.claimRoom(type, currentWorldKey, session.dungeon.name) ?: return false
        val server = world.server
        val targetIdentifier = Identifier.tryParse(definition.world)
        val targetWorld = targetIdentifier?.let { server.getWorld(RegistryKey.of(RegistryKeys.WORLD, it)) } ?: world
        val trapdoor = definition.trapdoors.firstOrNull()?.toBlockPos()
        if (trapdoor == null) {
            SecretRoomManager.releaseRoom(definition.id, session.dungeon.name)
            return false
        }
        val readyMin = definition.readyAreaMin.toBlockPos()
        val readyMax = definition.readyAreaMax.toBlockPos()
        val readyArea = Box(
            readyMin.x.toDouble(),
            readyMin.y.toDouble(),
            readyMin.z.toDouble(),
            (readyMax.x + 1).toDouble(),
            (readyMax.y + 1).toDouble(),
            (readyMax.z + 1).toDouble()
        )
        val state = SecretRoomState(
            definition = definition,
            world = targetWorld,
            trapdoorPos = trapdoor,
            readyArea = readyArea,
            bossSpawnPos = definition.bossSpawn.toBlockPos(),
            bossTeleportTargets = definition.bossTeleportTargets.map { it.toBlockPos() }
        )
        session.secretRoomState = state
        val secretCorner1 = definition.corner1.toBlockPos()
        val secretCorner2 = definition.corner2.toBlockPos()
        session.chunkLease = ChunkPin.pinArea(targetWorld, secretCorner1, secretCorner2, session.instanceId)
        session.status = DungeonStatus.SECRET_ROOM
        val members = session.players.mapNotNull { server.playerManager.getPlayer(it) }
        members.forEach { member ->
            member.teleport(targetWorld, trapdoor.x + 0.5, (trapdoor.y + 1).toDouble(), trapdoor.z + 0.5, member.yaw, member.pitch)
            PlayerEffects.sendTitle(
                member,
                "{\"text\":\" Secret Room Discovered!\",\"bold\":true,\"color\":\"gold\"}",
                "{\"text\":\"You delve deeper into the dungeon...\",\"color\":\"gray\"}",
                20,
                80,
                20
            )
            val introDurationTicks = 3 * 20
            val introAmplifier = 4
            member.addStatusEffect(
                StatusEffectInstance(StatusEffects.BLINDNESS, introDurationTicks, introAmplifier, false, false, true)
            )
            member.addStatusEffect(
                StatusEffectInstance(StatusEffects.SLOWNESS, introDurationTicks, introAmplifier, false, false, true)
            )
        }
        if (members.isNotEmpty()) {
            SoundService.playToPlayers(members, SoundEvents.ENTITY_ENDER_DRAGON_GROWL)
        }
        broadcastSecretRoomEntry(session, members)
        println("[Dungeons][SecretRoom] ${session.dungeon.name} routed to secret room ${definition.id} (${definition.world}).")
        return true
    }

    private fun broadcastSecretRoomEntry(session: Session, members: List<ServerPlayerEntity>) {
        if (members.isEmpty()) return
        val names = members.map { it.gameProfile.name }.sorted()
        if (names.isEmpty()) return
        val formattedNames = names.joinToString(", ") { name -> "($name)" }
        val verb = if (names.size == 1) "has" else "have"
        val message = Text.literal("[ DUNGEONS] $formattedNames $verb entered a Deeper Part of their Dungeon...")
            .formatted(Formatting.LIGHT_PURPLE)
        val server = session.world.server
        server.playerManager.playerList.forEach { it.sendMessage(message, false) }
        println("[Dungeons][SecretRoom] Broadcast entry: $formattedNames")
    }

    private fun broadcastLegendaryCatch(server: MinecraftServer, catcher: String, pokemon: String) {
        val message = Text.literal("[ DUNGEONS] ($catcher) caught ($pokemon)!")
            .formatted(Formatting.GOLD)
        server.playerManager.playerList.forEach { it.sendMessage(message, false) }
        println("[Dungeons][SecretRoom] $catcher caught $pokemon in a secret room.")
    }

    private fun formatPokemonDisplayName(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "Unknown Pokemon"
        return trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
            }
    }

    private fun handleSecretRoomTick(world: ServerWorld, session: Session, state: SecretRoomState) {
        when (state.phase) {
            SecretRoomPhase.WAITING_READY -> checkSecretRoomReady(world, session, state)
            SecretRoomPhase.IN_BOSS_ROOM -> updateSecretBoss(world, session, state)
            SecretRoomPhase.CAPTURE -> ensureSecretGuiState(world, session, state)
            SecretRoomPhase.COMPLETED -> {}
        }
    }

    private fun checkSecretRoomReady(world: ServerWorld, session: Session, state: SecretRoomState) {
        val server = world.server
        val players = session.players.mapNotNull { server.playerManager.getPlayer(it) }
            .filter { it.serverWorld == state.world }
        if (players.isEmpty()) return
        val required = session.players
        if (players.size != required.size || required.isEmpty()) return
        val inside = players.filter { player ->
            val pos = player.pos
            state.readyArea.contains(pos)
        }
        if (inside.size == required.size && inside.map { it.uuid }.containsAll(required)) {
            teleportToSecretBossRoom(session, state, inside)
        }
    }

    private fun teleportToSecretBossRoom(
        session: Session,
        state: SecretRoomState,
        players: List<ServerPlayerEntity>
    ) {
        val entry = SecretBossConfigManager.randomEntryForType(session.dungeon.type)
        if (entry == null) {
            players.forEach { player ->
                player.sendDungeonMessage(
                    "No secret bosses are configured for ${session.dungeon.type} dungeons.",
                    DungeonMessageType.ERROR
                )
            }
            completeSecretRoom(session, state)
            return
        }
        val targets = if (state.bossTeleportTargets.isEmpty()) listOf(state.bossSpawnPos) else state.bossTeleportTargets
        players.forEachIndexed { index, player ->
            val slot = (state.nextTeleportIndex + index) % targets.size
            val target = targets[slot]
            player.teleport(state.world, target.x + 0.5, (target.y + 1).toDouble(), target.z + 0.5, player.yaw, player.pitch)
        }
        state.nextTeleportIndex = (state.nextTeleportIndex + players.size) % targets.size
        spawnSecretBoss(session, state, players, entry)
    }

    private fun spawnSecretBoss(
        session: Session,
        state: SecretRoomState,
        players: List<ServerPlayerEntity>,
        entry: SecretBossEntry
    ) {
        val type = entry.type.trim()
        if (type.isEmpty()) {
            println("[Dungeons][SecretRoom] Secret boss entry is missing a dungeon type; aborting spawn.")
            return
        }
        val pokemon = entry.pokemon.trim()
        if (pokemon.isEmpty()) {
            println("[Dungeons][SecretRoom] Secret boss entry is missing a Pokémon identifier; aborting spawn.")
            return
        }
        val size = entry.size.coerceAtLeast(0.1)
        val sanitizedEntry = entry.copy(type = type, pokemon = pokemon, size = size)
        val world = state.world
        val pos = state.bossSpawnPos
        val existingState = world.getBlockState(pos)
        if (!existingState.isAir) {
            state.crafterState = StoredBlockState(pos, existingState)
            world.removeBlockEntity(pos)
            world.setBlockState(pos, Blocks.AIR.defaultState)
        } else {
            state.crafterState = null
        }
        val sizeArg = formatSizeArgument(size)
        val command = formatBossSpawnCommand(type, pokemon, pos, sizeArg)
        world.server.commandManager.executeWithPrefix(world.server.commandSource.withWorld(world), command)
        state.bossEntry = sanitizedEntry
        state.bossSpawnConfirmed = false
        state.bossSpawnTick = 0L
        val spawned = findSecretBossEntity(world, pos)
        state.bossEntityUuid = spawned?.uuid
        val titleName = entry.pokemon.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val titleJson = "{\"text\":\"$titleName has spawned!\",\"bold\":true,\"color\":\"gold\"}"
        val subtitleJson = "{\"text\":\"Beat it to try and catch it!\",\"color\":\"gray\"}"
        players.forEach { PlayerEffects.sendTitle(it, titleJson, subtitleJson, 10, 60, 20) }
        state.phase = SecretRoomPhase.IN_BOSS_ROOM
        updateSecretBoss(world, session, state)
    }

    private fun findSecretBossEntity(world: ServerWorld, pos: BlockPos): LivingEntity? {
        val searchBox = Box(
            (pos.x - 5).toDouble(),
            (pos.y - 2).toDouble(),
            (pos.z - 5).toDouble(),
            (pos.x + 6).toDouble(),
            (pos.y + 6).toDouble(),
            (pos.z + 6).toDouble()
        )
        return world.getEntitiesByClass(LivingEntity::class.java, searchBox) { living ->
            !living.isRemoved && living.isAlive && ALPHAZ_BOSS_TAG in living.commandTags
        }.firstOrNull()
    }

    private fun updateSecretBoss(world: ServerWorld, session: Session, state: SecretRoomState) {
        val uuid = state.bossEntityUuid
        val server = world.server
        val players = session.players.mapNotNull { server.playerManager.getPlayer(it) }
            .filter { it.serverWorld == state.world }
        val entry = state.bossEntry ?: return
        val name = entry.pokemon.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val title = Text.literal(name)
        val bar = state.bossBar ?: run {
            val created = ServerBossBar(title, BossBar.Color.PURPLE, BossBar.Style.PROGRESS)
            created.setPercent(1.0f)
            created.setVisible(true)
            players.forEach { created.addPlayer(it) }
            state.bossBar = created
            created
        }
        bar.setName(title)
        players.forEach { if (!bar.players.contains(it)) bar.addPlayer(it) }
        val bossEntity = uuid?.let { state.world.getEntity(it) as? LivingEntity }
            ?: findSecretBossEntity(state.world, state.bossSpawnPos)
        if (bossEntity == null || !bossEntity.isAlive) {
            if (state.phase == SecretRoomPhase.IN_BOSS_ROOM) {
                if (!state.bossSpawnConfirmed) {
                    return
                }
                val now = world.time
                if (now - state.bossSpawnTick < SECRET_GUI_DELAY_TICKS) {
                    return
                }
                state.phase = SecretRoomPhase.CAPTURE
                state.bossEntityUuid = null
                state.bossDefeatedTick = now
                bar.setVisible(false)
                openSecretCaptureForPlayers(session, state)
            }
            return
        }
        state.bossEntityUuid = bossEntity.uuid
        if (!state.bossSpawnConfirmed) {
            state.bossSpawnConfirmed = true
            state.bossSpawnTick = world.time
        }
        val maxHealth = bossEntity.maxHealth.takeIf { it > 0.0f } ?: 1.0f
        bar.setPercent((bossEntity.health / maxHealth).coerceIn(0.0f, 1.0f))
    }

    private fun ensureSecretGuiState(world: ServerWorld, session: Session, state: SecretRoomState) {
        if (state.phase != SecretRoomPhase.CAPTURE) return
        val server = world.server
        val players = session.players.mapNotNull { server.playerManager.getPlayer(it) }
        if (players.isEmpty()) return
        players.filter { it.serverWorld == state.world }
            .forEach { player ->
                if (!player.isAlive || player.isSpectator) {
                    state.pendingGuiPlayers.add(player.uuid)
                    state.guiOpenPlayers.remove(player.uuid)
                    return@forEach
                }
                if (state.pendingGuiPlayers.remove(player.uuid)) {
                    player.server.execute { openSecretCaptureGui(session, state, player) }
                    return@forEach
                }
                if (player.uuid !in state.completedPlayers && player.uuid !in state.guiOpenPlayers) {
                    openSecretCaptureGui(session, state, player)
                }
            }
    }

    private fun openSecretCaptureForPlayers(session: Session, state: SecretRoomState) {
        val server = state.world.server
        session.players.mapNotNull { server.playerManager.getPlayer(it) }
            .filter { it.serverWorld == state.world }
            .forEach { player ->
                if (!player.isAlive || player.isSpectator) {
                    state.pendingGuiPlayers.add(player.uuid)
                    state.guiOpenPlayers.remove(player.uuid)
                } else {
                    openSecretCaptureGui(session, state, player)
                }
            }
    }

    private fun openSecretCaptureGui(session: Session, state: SecretRoomState, player: ServerPlayerEntity) {
        if (state.completedPlayers.contains(player.uuid)) return
        if (!player.isAlive || player.isSpectator) {
            state.pendingGuiPlayers.add(player.uuid)
            state.guiOpenPlayers.remove(player.uuid)
            return
        }
        val pokemon = state.bossEntry?.pokemon
        val inventory = SimpleInventory(27)
        val filler = secretFillerStack()
        for (slot in 0 until inventory.size()) {
            inventory.setStack(slot, filler.copy())
        }
        inventory.setStack(4, createPokemonModelStack(pokemon, state.world))
        val hasMasterBall = playerHasMasterBall(player)
        val clicks = mutableMapOf<Int, (ServerPlayerEntity, Int) -> Unit>()
        if (hasMasterBall) {
            inventory.setStack(21, createCaptureBallStack())
            inventory.setStack(23, createMasterBallStack())
            clicks[21] = { actor, _ -> handleSecretCaptureClick(session, state, actor, false) }
            clicks[23] = { actor, _ -> handleSecretCaptureClick(session, state, actor, true) }
        } else {
            inventory.setStack(22, createCaptureBallStack())
            clicks[22] = { actor, _ -> handleSecretCaptureClick(session, state, actor, false) }
        }
        val factory = object : NamedScreenHandlerFactory {
            override fun getDisplayName(): Text = Text.literal("Secret Capture")
            override fun createMenu(syncId: Int, playerInv: net.minecraft.entity.player.PlayerInventory, playerEntity: net.minecraft.entity.player.PlayerEntity) =
                LockedChestHandler(syncId, playerInv, inventory, 3, clicks) { closed ->
                    handleSecretGuiClosed(session, state, closed)
                }
        }
        state.guiOpenPlayers.add(player.uuid)
        player.openHandledScreen(factory)
    }

    private fun handleSecretCaptureClick(
        session: Session,
        state: SecretRoomState,
        player: ServerPlayerEntity,
        useMasterBall: Boolean
    ) {
        if (state.completedPlayers.contains(player.uuid)) return
        state.pendingGuiPlayers.remove(player.uuid)
        state.waitingForMasterBall.remove(player.uuid)
        if (useMasterBall) {
            if (!tryConsumeMasterBall(player)) {
                if (state.waitingForMasterBall.add(player.uuid)) {
                    player.sendMessage(Text.literal("You need a Master Ball to use this option."), false)
                }
                return
            }
            completeSecretCapture(session, state, player)
            return
        }
        val attempts = (state.attempts[player.uuid] ?: 0) + 1
        state.attempts[player.uuid] = attempts
        val chance = SECRET_CAPTURE_CHANCES.getOrNull(attempts - 1) ?: SECRET_CAPTURE_CHANCES.last()
        val success = ThreadLocalRandom.current().nextDouble() < chance
        if (success) {
            completeSecretCapture(session, state, player)
            return
        }
        if (attempts >= SECRET_CAPTURE_CHANCES.size) {
            state.completedPlayers.add(player.uuid)
            state.guiOpenPlayers.remove(player.uuid)
            state.forcedGuiClosures.add(player.uuid)
            player.closeHandledScreen()
            handleSecretCompletionCheck(session, state)
        }
    }

    private fun completeSecretCapture(
        session: Session,
        state: SecretRoomState,
        player: ServerPlayerEntity
    ) {
        state.successes.add(player.uuid)
        giveSecretReward(state, player)
        state.completedPlayers.add(player.uuid)
        state.guiOpenPlayers.remove(player.uuid)
        state.forcedGuiClosures.add(player.uuid)
        state.waitingForMasterBall.remove(player.uuid)
        player.closeHandledScreen()
        handleSecretCompletionCheck(session, state)
    }

    private fun handleSecretGuiClosed(session: Session, state: SecretRoomState, player: ServerPlayerEntity) {
        state.guiOpenPlayers.remove(player.uuid)
        state.waitingForMasterBall.remove(player.uuid)
        if (state.phase != SecretRoomPhase.CAPTURE) {
            state.forcedGuiClosures.remove(player.uuid)
            return
        }
        if (state.completedPlayers.contains(player.uuid)) {
            state.forcedGuiClosures.remove(player.uuid)
            return
        }
        if (state.forcedGuiClosures.remove(player.uuid)) {
            return
        }
        player.server.execute {
            openSecretCaptureGui(session, state, player)
        }
    }

    private fun handleSecretCompletionCheck(session: Session, state: SecretRoomState) {
        if (!state.completedPlayers.containsAll(session.players)) return
        completeSecretRoom(session, state)
    }

    private fun giveSecretReward(state: SecretRoomState, player: ServerPlayerEntity) {
        val entry = state.bossEntry ?: return
        val random = ThreadLocalRandom.current().asKotlinRandom()
        val attributes = SECRET_IV_ATTRIBUTES.shuffled(random).take(3)
        val parsed = parsePokemonCommand(entry.pokemon)
        val species = parsed.name.ifBlank { entry.pokemon.trim() }
        if (species.isEmpty()) {
            println("[Dungeons][SecretRoom] Skipping secret reward: missing Pokémon identifier for ${player.gameProfile.name}.")
            return
        }
        val command = buildString {
            append("pokegiveother ")
            append(player.gameProfile.name)
            append(' ')
            append(species)
            appendAttribute(this, parsed.attribute)
            attributes.forEach { attr ->
                append(' ')
                append(attr)
            }
        }
        player.server.commandManager.executeWithPrefix(player.server.commandSource, command)
        val displaySpecies = formatPokemonDisplayName(species)
        broadcastLegendaryCatch(player.server, player.gameProfile.name, displaySpecies)
    }

    private fun handleSecretRespawn(session: Session, state: SecretRoomState, player: ServerPlayerEntity) {
        val world = state.world
        val targets = if (state.bossTeleportTargets.isEmpty()) listOf(state.bossSpawnPos) else state.bossTeleportTargets
        val index = state.nextTeleportIndex % targets.size
        val target = targets[index]
        state.nextTeleportIndex = (state.nextTeleportIndex + 1) % targets.size
        player.teleport(world, target.x + 0.5, (target.y + 1).toDouble(), target.z + 0.5, player.yaw, player.pitch)
        state.bossBar?.let { bar -> if (!bar.players.contains(player)) bar.addPlayer(player) }
    }

    private fun completeSecretRoom(session: Session, state: SecretRoomState) {
        if (state.phase == SecretRoomPhase.COMPLETED) return
        state.phase = SecretRoomPhase.COMPLETED
        val world = state.world
        state.guiOpenPlayers.toList().forEach { uuid ->
            world.server.playerManager.getPlayer(uuid)?.closeHandledScreen()
        }
        state.bossBar?.players?.toList()?.forEach { state.bossBar?.removePlayer(it) }
        state.bossBar?.setVisible(false)
        state.forcedGuiClosures.clear()
        state.crafterState?.let { world.setBlockState(it.pos, it.state) } ?: world.setBlockState(state.bossSpawnPos, Blocks.CRAFTER.defaultState)
        SecretRoomManager.releaseRoom(state.definition.id, session.dungeon.name)
        session.secretRoomState = null
        recordDungeonCompletion(session)
        val onlinePlayer = session.players.mapNotNull { world.server.playerManager.getPlayer(it) }.firstOrNull()
        if (onlinePlayer != null) {
            PartyService.endDungeon(onlinePlayer, PartyService.PartyEndReason.BOSS_DEFEATED)
        }
    }

    private fun resetSecretRoom(session: Session) {
        val state = session.secretRoomState ?: return
        val world = state.world
        state.guiOpenPlayers.toList().forEach { uuid ->
            world.server.playerManager.getPlayer(uuid)?.closeHandledScreen()
        }
        state.bossBar?.players?.toList()?.forEach { state.bossBar?.removePlayer(it) }
        state.bossBar?.setVisible(false)
        state.forcedGuiClosures.clear()
        state.crafterState?.let { world.setBlockState(it.pos, it.state) } ?: world.setBlockState(state.bossSpawnPos, Blocks.CRAFTER.defaultState)
        SecretRoomManager.releaseRoom(state.definition.id, session.dungeon.name)
        session.secretRoomState = null
    }

    private fun secretFillerStack(): ItemStack {
        val stack = ItemStack(Items.BLACK_STAINED_GLASS_PANE)
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "))
        return stack
    }

    private fun createCaptureBallStack(): ItemStack {
        val item = Registries.ITEM.getOrEmpty(ANCIENT_POKE_BALL_ID).orElse(Items.BARRIER)
        val stack = ItemStack(item)
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Attempt Capture"))
        return stack
    }

    private fun createMasterBallStack(): ItemStack {
        val item = Registries.ITEM.getOrEmpty(MASTER_BALL_ID).orElse(Items.BARRIER)
        val stack = ItemStack(item)
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Use Master Ball"))
        return stack
    }

    fun playerHasMasterBall(player: ServerPlayerEntity): Boolean {
        val masterBall = Registries.ITEM.getOrEmpty(MASTER_BALL_ID).orElse(null) ?: return false
        return findMasterBallSlot(player, masterBall) != null
    }

    private fun tryConsumeMasterBall(player: ServerPlayerEntity): Boolean {
        val masterBall = Registries.ITEM.getOrEmpty(MASTER_BALL_ID).orElse(null) ?: return false
        val slot = findMasterBallSlot(player, masterBall) ?: return false
        val inventory = player.inventory
        val stack = inventory.getStack(slot)
        stack.decrement(1)
        if (stack.isEmpty) {
            inventory.setStack(slot, ItemStack.EMPTY)
        }
        inventory.markDirty()
        player.playerScreenHandler.sendContentUpdates()
        return true
    }

    private fun findMasterBallSlot(player: ServerPlayerEntity, masterBall: Item): Int? {
        val inventory = player.inventory
        for (slot in 0 until inventory.size()) {
            val stack = inventory.getStack(slot)
            if (!stack.isEmpty && stack.isOf(masterBall)) {
                return slot
            }
        }
        return null
    }

    private fun createPokemonModelStack(pokemon: String?, world: ServerWorld): ItemStack {
        if (pokemon == null) {
            return ItemStack(Items.BARRIER)
        }
        val nbt = NbtCompound()
        nbt.putString("id", "cobblemon:pokemon_model")
        nbt.putByte("Count", 1)
        val components = NbtCompound()
        val pokemonComponent = NbtCompound()
        pokemonComponent.putString("species", "cobblemon:$pokemon")
        pokemonComponent.put("aspects", NbtList())
        components.put("cobblemon:pokemon_item", pokemonComponent)
        nbt.put("components", components)
        return ItemStack.fromNbt(world.registryManager, nbt).orElse(ItemStack(Items.BARRIER))
    }

    private fun createRaidBossBar(): ServerBossBar {
        return ServerBossBar(
            Text.literal("Pokemon Left in Raid: 0"),
            BossBar.Color.RED,
            BossBar.Style.PROGRESS
        ).apply {
            setPercent(1.0f)
            setVisible(false)
        }
    }

    private fun countRaidEntities(world: ServerWorld, session: Session, roomIdx: Int): Int {
        val room = session.rooms.getOrNull(roomIdx) ?: return 0
        val cache = session.raidEntityUuids.getOrPut(roomIdx) { mutableSetOf() }
        pruneRaidEntityCache(world, room, cache)
        if (cache.isEmpty()) {
            cache.addAll(collectRaidEntityUuids(world, room))
        }
        return cache.size
    }

    private fun isGimmighoulRoom(session: Session, roomIdx: Int): Boolean {
        if (session.gildedChests.isEmpty()) return false
        return session.gildedChests.values.any { it.roomIdx == roomIdx }
    }

    private fun clampDoorToRoom(door: Door, room: Room): Door {
        val min = BlockPos(
            door.min.x.coerceIn(room.min.x, room.max.x),
            door.min.y.coerceIn(room.min.y, room.max.y),
            door.min.z.coerceIn(room.min.z, room.max.z)
        )
        val max = BlockPos(
            door.max.x.coerceIn(room.min.x, room.max.x),
            door.max.y.coerceIn(room.min.y, room.max.y),
            door.max.z.coerceIn(room.min.z, room.max.z)
        )
        val center = BlockPos(
            door.center.x.coerceIn(room.min.x, room.max.x),
            door.center.y.coerceIn(room.min.y, room.max.y),
            door.center.z.coerceIn(room.min.z, room.max.z)
        )
        return Door(min, max, center)
    }

    private fun doorIntersectsRoom(door: Door, room: Room): Boolean {
        val doorMinX = minOf(door.min.x, door.max.x)
        val doorMaxX = maxOf(door.min.x, door.max.x)
        val doorMinY = minOf(door.min.y, door.max.y)
        val doorMaxY = maxOf(door.min.y, door.max.y)
        val doorMinZ = minOf(door.min.z, door.max.z)
        val doorMaxZ = maxOf(door.min.z, door.max.z)
        val intersectsX = doorMaxX >= room.min.x && doorMinX <= room.max.x
        val intersectsY = doorMaxY >= room.min.y && doorMinY <= room.max.y
        val intersectsZ = doorMaxZ >= room.min.z && doorMinZ <= room.max.z
        return intersectsX && intersectsY && intersectsZ
    }

    private fun doorContainsBlock(door: Door, pos: BlockPos): Boolean {
        val minX = minOf(door.min.x, door.max.x)
        val maxX = maxOf(door.min.x, door.max.x)
        val minY = minOf(door.min.y, door.max.y)
        val maxY = maxOf(door.min.y, door.max.y)
        val minZ = minOf(door.min.z, door.max.z)
        val maxZ = maxOf(door.min.z, door.max.z)
        return pos.x in minX..maxX && pos.y in minY..maxY && pos.z in minZ..maxZ
    }

    private fun formatDungeonType(type: String): String {
        if (type.isBlank()) return ""
        return type.split(Regex("[\\s_-]+")).filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                val lower = part.lowercase()
                lower.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
            }
    }

    private fun resolveYellowGildedChestState(): BlockState? = yellowGildedChestStateCache
    private fun resolveGreenGildedChestState(): BlockState? = greenGildedChestStateCache

    private fun loadChestState(id: Identifier): BlockState? {
        val block = Registries.BLOCK.getOrEmpty(id).orElse(null)
        if (block == null) {
            println("[Dungeons] Missing block '$id' for furnace chest handling.")
        }
        return block?.defaultState
    }

    private val yellowGildedChestStateCache: BlockState? by lazy { loadChestState(YELLOW_GILDED_CHEST_ID) }
    private val greenGildedChestStateCache: BlockState? by lazy { loadChestState(GREEN_GILDED_CHEST_ID) }
    private val yellowGildedChestBlock: Block?
        get() = yellowGildedChestStateCache?.block

    private val YELLOW_GILDED_CHEST_ID = Identifier.of("cobblemon", "yellow_gilded_chest")
    private val GREEN_GILDED_CHEST_ID = Identifier.of("cobblemon", "green_gilded_chest")

private const val ROOM_SIZE_XZ = 25
private const val ROOM_SIZE_Y = 7
private const val TALL_ROOM_SIZE_Y = 14
private const val BOSS_ROOM_SIZE_X = 47
private const val BOSS_ROOM_SIZE_Y = 14
private const val BOSS_ROOM_SIZE_Z = 39
    private const val ALPHAZ_TAG = "alphaz"
    private const val ALPHAZ_BOSS_TAG = "alphaz_dboss"
    private const val LEGENDARY_CHANCE = 0.10
    private const val LEGENDARY_GUARANTEE_CLEANUP_INTERVAL_TICKS = 20L * 60L
    private val ANCIENT_POKE_BALL_ID = Identifier.of("cobblemon:ancient_poke_ball")
    private val MASTER_BALL_ID = Identifier.of("cobblemon:master_ball")
    private val SECRET_CAPTURE_CHANCES = listOf(0.20, 0.20, 0.30)
    private val SECRET_IV_ATTRIBUTES = listOf(
        "hp_iv=31",
        "attack_iv=31",
        "special_attack_iv=31",
        "speed_iv=31",
        "defence_iv=31",
        "special_defence_iv=31"
    )
    private const val GRACE_TICKS = 40L
    private const val BOSS_EMPTY_FAIL_TICKS = 45L * 20L
    private const val ALPHA_SPAWN_DELAY_TICKS = 4L
    private const val BOSS_WAVE_INTERVAL_TICKS = 15L * 20L
    private const val BOSS_WAVE_MON_COUNT = 3
    private const val RAID_CACHE_REFRESH_DELAY_TICKS = 10L
    private const val BOSS_CACHE_REFRESH_DELAY_TICKS = 10L
    private const val WEAK_WEIGHT = 1.0 / 3.0
    private const val HARD_WEIGHT = 2.0 / 5.0
    private const val MEDIUM_WEIGHT = 1.0 - WEAK_WEIGHT - HARD_WEIGHT
    private const val MAX_HARD_PER_RAID = 3
    private const val MAX_MEDIUM_PER_RAID = 5
    private const val DOOR_UPDATE_FLAGS = Block.NOTIFY_ALL or Block.FORCE_STATE or Block.REDRAW_ON_MAIN_THREAD
    private val ATTRIBUTE_WHITESPACE = Regex("""\s+""")

    private val WAVE_CONFIG = listOf(
        WaveConfig(3),
        WaveConfig(5),
        WaveConfig(7)
    )

    private data class WaveConfig(val count: Int)
    data class DifficultySet(
        val weak: DifficultyConfig,
        val medium: DifficultyConfig,
        val hard: DifficultyConfig,
        val boss: DifficultyConfig
    )
    data class DifficultyConfig(
        val command: String,
        val displayName: String,
        val pokemon: List<String>,
        val size: Double? = null
    )
    private data class PokemonCommand(
        val name: String,
        val attribute: String?
    )
    private fun formatSizeArgument(value: Double): String {
        val normalized = value.coerceAtLeast(0.1)
        return if (normalized == normalized.toInt().toDouble()) {
            normalized.toInt().toString()
        } else {
            String.format(Locale.ROOT, "%.2f", normalized).trimEnd('0').trimEnd('.')
        }
    }
    private data class SpawnEntry(val position: BlockPos, val difficulty: DifficultyConfig, val pokemon: String)

    private fun parsePokemonCommand(raw: String): PokemonCommand {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return PokemonCommand("", null)
        }
        val closing = trimmed.lastIndexOf(')')
        val opening = if (closing != -1) trimmed.lastIndexOf('(', startIndex = closing) else -1
        if (opening != -1 && closing > opening) {
            val attribute = trimmed.substring(opening + 1, closing).trim()
            val species = trimmed.substring(0, opening).trim()
            if (species.isNotEmpty() && attribute.isNotEmpty()) {
                return PokemonCommand(species, attribute)
            }
        }
        return PokemonCommand(trimmed, null)
    }

    private fun appendAttribute(builder: StringBuilder, attribute: String?) {
        val value = attribute?.trim()
        if (!value.isNullOrEmpty()) {
            builder.append(' ').append(ATTRIBUTE_WHITESPACE.replace(value, "_"))
        }
    }
}
