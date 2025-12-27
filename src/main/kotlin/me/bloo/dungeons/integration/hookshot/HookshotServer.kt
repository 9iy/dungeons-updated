package me.bloo.dungeons.integration.hookshot

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.ButtonBlock
import net.minecraft.block.LeverBlock
import net.minecraft.block.OperatorBlock
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.CustomModelDataComponent
import net.minecraft.component.type.NbtComponent
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.nbt.NbtCompound
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.TypedActionResult
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.text.Text
import net.minecraft.world.RaycastContext
import net.minecraft.world.World
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

object HookshotServer {
    private const val MAX_RANGE = 20.0
    private const val FLIGHT_SPEED = 3.0
    private const val PULL_SPEED_MIN = 0.6
    private const val PULL_SPEED_MAX = 1.4
    private const val STOP_DISTANCE = 1.6
    private const val COOLDOWN_TICKS = 60
    private const val TIMEOUT_TICKS = 20 * 5

    private val states = mutableMapOf<UUID, HookState>()
    private var initialized = false

    enum class Phase { IDLE, FLYING, ANCHORED, PULLING }

    data class HookState(
        var phase: Phase = Phase.IDLE,
        var headPos: Vec3d? = null,
        var headVelocity: Vec3d? = null,
        var anchorPos: Vec3d? = null,
        var anchorSide: Direction = Direction.UP,
        var originPos: Vec3d? = null,
        var ticks: Int = 0,
        var chargingHand: Hand? = null,
        var chargeTicks: Int = 0,
        var isCharging: Boolean = false,
        var worldKey: net.minecraft.registry.RegistryKey<World>? = null,
        var totalTicks: Int = 0,
        var lastHeadParticleTick: Int = Int.MIN_VALUE,
        var lastRopeParticleTick: Int = Int.MIN_VALUE,
        var lastRopeStart: Vec3d? = null,
        var lastRopeEnd: Vec3d? = null,
        var cachedAnchorState: BlockState? = null,
        var cachedAnchorStateTick: Int = -1
    )

    fun init() {
        if (initialized) return
        initialized = true

        UseItemCallback.EVENT.register(UseItemCallback { player, world, hand ->
            val stack = player.getStackInHand(hand)
            if (!isHookshot(stack)) {
                return@UseItemCallback TypedActionResult.pass(stack)
            }
            if (world.isClient) {
                return@UseItemCallback TypedActionResult.pass(stack)
            }
            val serverPlayer = player as? ServerPlayerEntity
                ?: return@UseItemCallback TypedActionResult.pass(stack)

            val state = states.computeIfAbsent(serverPlayer.uuid) { HookState() }
            if (state.phase == Phase.ANCHORED) {
                state.phase = Phase.PULLING
                state.ticks = 0
                return@UseItemCallback TypedActionResult.consume(stack)
            }
            if (state.phase == Phase.FLYING || state.phase == Phase.PULLING) {
                return@UseItemCallback TypedActionResult.pass(stack)
            }
            state.chargingHand = hand
            state.chargeTicks = 0
            state.isCharging = false
            serverPlayer.setCurrentHand(hand)
            return@UseItemCallback TypedActionResult.consume(stack)
        })

        AttackBlockCallback.EVENT.register(AttackBlockCallback { player, world, hand, _, _ ->
            if (world.isClient) return@AttackBlockCallback ActionResult.PASS
            val serverPlayer = player as? ServerPlayerEntity ?: return@AttackBlockCallback ActionResult.PASS
            val stack = player.getStackInHand(hand)
            if (!isHookshot(stack)) return@AttackBlockCallback ActionResult.PASS
            val state = states[serverPlayer.uuid]
            if (state != null && state.phase != Phase.IDLE) {
                forceCancel(serverPlayer)
            }
            ActionResult.PASS
        })

        AttackEntityCallback.EVENT.register(AttackEntityCallback { player, world, hand, _, _ ->
            if (world.isClient) return@AttackEntityCallback ActionResult.PASS
            val serverPlayer = player as? ServerPlayerEntity ?: return@AttackEntityCallback ActionResult.PASS
            val stack = player.getStackInHand(hand)
            if (!isHookshot(stack)) return@AttackEntityCallback ActionResult.PASS
            val state = states[serverPlayer.uuid]
            if (state != null && state.phase != Phase.IDLE) {
                forceCancel(serverPlayer)
            }
            ActionResult.PASS
        })

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            forceCancel(handler.player)
        }

        ServerPlayerEvents.AFTER_RESPAWN.register { oldPlayer, newPlayer, _ ->
            forceCancel(oldPlayer)
            forceCancel(newPlayer)
        }

        ServerTickEvents.END_SERVER_TICK.register(ServerTickEvents.EndTick { server ->
            tick(server)
        })
    }

    fun giveHookshotTo(player: ServerPlayerEntity) {
        val stack = ItemStack(Items.CROSSBOW)
        val marker = NbtCompound()
        marker.putByte("hookshot", 1)
        val customData = NbtCompound()
        customData.put("dungeons", marker)
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(customData))
        stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelDataComponent(90001))
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Hookshot"))
        if (!player.giveItemStack(stack)) {
            player.dropItem(stack, false)
        }
    }

    fun forceCancel(player: ServerPlayerEntity) {
        val state = states[player.uuid]
        if (state != null && state.phase != Phase.IDLE) {
            clearHookState(state)
            player.velocity = Vec3d.ZERO
            player.velocityModified = true
            player.fallDistance = 0f
        }
        state?.apply {
            isCharging = false
            chargeTicks = 0
            chargingHand = null
        }
        if (state == null || state.phase == Phase.IDLE) {
            states.remove(player.uuid)
        }
        if (player.isUsingItem && player.activeItem.let { isHookshot(it) }) {
            player.clearActiveItem()
        }
    }

    fun isHookshot(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        if (stack.item != Items.CROSSBOW) return false
        val customData = stack.get(DataComponentTypes.CUSTOM_DATA)?.nbt ?: return false
        val marker = customData.getCompound("dungeons")
        return marker.getByte("hookshot") == 1.toByte()
    }

    private fun tick(server: MinecraftServer) {
        val keys = states.keys.toList()
        for (uuid in keys) {
            val state = states[uuid] ?: continue
            val player = server.playerManager.getPlayer(uuid)
            if (player == null) {
                states.remove(uuid)
                continue
            }
            tickPlayer(player, state)
            if (state.phase == Phase.IDLE && !state.isCharging) {
                states.remove(uuid)
            }
        }
    }

    private fun tickPlayer(player: ServerPlayerEntity, state: HookState) {
        if (!player.isAlive) {
            forceCancel(player)
            return
        }
        val stackInHand = player.mainHandStack
        val offhand = player.offHandStack
        if (state.phase != Phase.IDLE && !isHookshot(stackInHand) && !isHookshot(offhand)) {
            forceCancel(player)
            return
        }
        if (state.phase != Phase.IDLE) {
            if (state.worldKey != null && state.worldKey != player.world.registryKey) {
                forceCancel(player)
                return
            }
            if (state.ticks++ > TIMEOUT_TICKS) {
                forceCancel(player)
                return
            }
            state.totalTicks++
        } else {
            state.ticks = 0
            state.totalTicks = 0
        }
        handleCharging(player, state)
        when (state.phase) {
            Phase.IDLE -> {}
            Phase.FLYING -> handleFlying(player, state)
            Phase.ANCHORED -> handleAnchored(player, state)
            Phase.PULLING -> handlePulling(player, state)
        }
    }

    private fun handleCharging(player: ServerPlayerEntity, state: HookState) {
        val activeStack = player.activeItem
        if (player.isUsingItem && isHookshot(activeStack)) {
            if (!state.isCharging) {
                state.isCharging = true
                state.chargeTicks = 0
                state.chargingHand = player.activeHand
            } else {
                state.chargeTicks++
            }
        } else if (state.isCharging) {
            val hand = state.chargingHand
            val stack = hand?.let { player.getStackInHand(it) }
            val required = stack?.let { requiredChargeTicks(it) } ?: 25
            val charged = state.chargeTicks >= required
            state.isCharging = false
            state.chargeTicks = 0
            state.chargingHand = null
            if (charged && stack != null && isHookshot(stack) && state.phase == Phase.IDLE) {
                fireHookshot(player, state)
            }
        }
    }

    private fun handleFlying(player: ServerPlayerEntity, state: HookState) {
        val world = player.serverWorld
        val head = state.headPos ?: run {
            forceCancel(player); return
        }
        val velocity = state.headVelocity ?: run {
            forceCancel(player); return
        }
        val origin = state.originPos ?: run {
            forceCancel(player); return
        }
        val nextPos = head.add(velocity)
        if (origin.distanceTo(nextPos) > MAX_RANGE) {
            forceCancel(player)
            return
        }
        val hit = raycast(world, head, nextPos, player)
        if (hit.type != HitResult.Type.MISS) {
            if (hit is BlockHitResult) {
                val anchorBlock = hit.blockPos
                val blockState = world.getBlockState(anchorBlock)
                if (!canAnchor(world, anchorBlock, blockState)) {
                    forceCancel(player)
                    return
                }
                if (isRedstoneTrigger(blockState.block)) {
                    blockState.onUse(world, player, hit)
                    spawnHeadParticles(world, hit.pos, state, velocity.length(), force = true)
                    completeInteraction(player, state)
                    return
                }
                val anchorPos = hit.pos
                state.anchorPos = anchorPos
                state.anchorSide = hit.side
                state.headPos = anchorPos
                state.headVelocity = Vec3d.ZERO
                state.ticks = 0
                state.phase = Phase.ANCHORED
                state.worldKey = player.world.registryKey
                state.cachedAnchorState = blockState
                state.cachedAnchorStateTick = state.totalTicks
                spawnHeadParticles(world, anchorPos, state, 0.0, force = true)
                maybeDrawRope(world, player.eyePos, anchorPos, state, 0.0, force = true)
            } else {
                forceCancel(player)
            }
        } else {
            state.headPos = nextPos
            spawnHeadParticles(world, nextPos, state, velocity.length())
        }
        if (state.phase != Phase.ANCHORED) {
            maybeDrawRope(world, player.eyePos, state.headPos ?: nextPos, state, velocity.length())
        }
    }

    private fun handleAnchored(player: ServerPlayerEntity, state: HookState) {
        val world = player.serverWorld
        val anchor = state.anchorPos ?: run {
            forceCancel(player); return
        }
        if (!isAnchorValid(world, state)) {
            forceCancel(player)
            return
        }
        maybeDrawRope(world, player.eyePos, anchor, state, player.velocity.length())
    }

    private fun handlePulling(player: ServerPlayerEntity, state: HookState) {
        val world = player.serverWorld
        val anchor = state.anchorPos ?: run {
            forceCancel(player); return
        }
        if (!isAnchorValid(world, state)) {
            forceCancel(player)
            return
        }
        val playerPos = player.pos
        val distance = playerPos.distanceTo(anchor)
        if (distance <= STOP_DISTANCE) {
            finishPull(player, state)
            return
        }
        val direction = anchor.subtract(playerPos).normalize()
        val baseSpeed = PULL_SPEED_MIN + distance * 0.06
        val speed = max(PULL_SPEED_MIN, min(baseSpeed, PULL_SPEED_MAX))
        val newVelocity = player.velocity.multiply(0.2).add(direction.multiply(speed))
        player.velocity = newVelocity
        player.velocityModified = true
        player.fallDistance = 0f
        val velocityMagnitude = newVelocity.length()
        spawnHeadParticles(world, anchor, state, velocityMagnitude)
        maybeDrawRope(world, player.eyePos, anchor, state, velocityMagnitude)
    }

    private fun finishPull(player: ServerPlayerEntity, state: HookState) {
        clearHookState(state)
        player.velocity = Vec3d.ZERO
        player.velocityModified = true
        player.fallDistance = 0f
        player.itemCooldownManager.set(Items.CROSSBOW, COOLDOWN_TICKS)
    }

    private fun completeInteraction(player: ServerPlayerEntity, state: HookState) {
        clearHookState(state)
        player.itemCooldownManager.set(Items.CROSSBOW, COOLDOWN_TICKS)
    }

    private fun clearHookState(state: HookState) {
        state.phase = Phase.IDLE
        state.headPos = null
        state.headVelocity = null
        state.anchorPos = null
        state.anchorSide = Direction.UP
        state.originPos = null
        state.worldKey = null
        state.ticks = 0
        state.totalTicks = 0
        state.lastHeadParticleTick = Int.MIN_VALUE
        state.lastRopeParticleTick = Int.MIN_VALUE
        state.lastRopeStart = null
        state.lastRopeEnd = null
        state.cachedAnchorState = null
        state.cachedAnchorStateTick = -1
    }

    private fun fireHookshot(player: ServerPlayerEntity, state: HookState) {
        val look = player.getRotationVec(1.0f).normalize()
        val eyePos = player.eyePos
        state.phase = Phase.FLYING
        state.headPos = eyePos.add(look.multiply(0.6))
        state.headVelocity = look.multiply(FLIGHT_SPEED)
        state.anchorPos = null
        state.anchorSide = Direction.UP
        state.originPos = eyePos
        state.ticks = 0
        state.worldKey = player.world.registryKey
        state.totalTicks = 0
        spawnHeadParticles(player.serverWorld, state.headPos!!, state, FLIGHT_SPEED, force = true)
        maybeDrawRope(player.serverWorld, player.eyePos, state.headPos!!, state, FLIGHT_SPEED, force = true)
    }

    private val nonAnchorableBlocks = setOf(
        Blocks.BARRIER,
        Blocks.LIGHT,
        Blocks.STRUCTURE_VOID
    )

    private fun canAnchor(
        world: ServerWorld,
        pos: BlockPos,
        state: BlockState
    ): Boolean {
        if (state.isAir) return false
        if (state.block in nonAnchorableBlocks) return false
        if (state.block is OperatorBlock) return false
        if (state.createScreenHandlerFactory(world, pos) != null) return false
        return true
    }

    private fun isAnchorValid(world: ServerWorld, state: HookState): Boolean {
        val anchor = state.anchorPos ?: return false
        val blockPos = BlockPos.ofFloored(anchor)
        val shouldRefresh = state.cachedAnchorState == null ||
            state.totalTicks - state.cachedAnchorStateTick >= 5
        val blockState = if (shouldRefresh) {
            val latest = world.getBlockState(blockPos)
            state.cachedAnchorState = latest
            state.cachedAnchorStateTick = state.totalTicks
            latest
        } else {
            state.cachedAnchorState
        } ?: return false
        return canAnchor(world, blockPos, blockState)
    }

    private fun isRedstoneTrigger(block: Block): Boolean {
        return block is LeverBlock || block is ButtonBlock
    }

    private const val ROPE_REDRAW_THRESHOLD_SQ = 0.25 * 0.25

    private fun maybeDrawRope(
        world: ServerWorld,
        start: Vec3d,
        end: Vec3d,
        state: HookState,
        movementSpeed: Double,
        force: Boolean = false
    ) {
        val interval = if (movementSpeed > 0.6) 1 else 3
        val startMoved = state.lastRopeStart?.squaredDistanceTo(start) ?: Double.POSITIVE_INFINITY
        val endMoved = state.lastRopeEnd?.squaredDistanceTo(end) ?: Double.POSITIVE_INFINITY
        val movedEnough = startMoved > ROPE_REDRAW_THRESHOLD_SQ || endMoved > ROPE_REDRAW_THRESHOLD_SQ
        val intervalMet = state.totalTicks - state.lastRopeParticleTick >= interval
        if (!force && !movedEnough && !intervalMet) {
            return
        }
        emitRopeParticles(world, start, end)
        state.lastRopeStart = start
        state.lastRopeEnd = end
        state.lastRopeParticleTick = state.totalTicks
    }

    private fun emitRopeParticles(world: ServerWorld, start: Vec3d, end: Vec3d) {
        val distance = start.distanceTo(end)
        val steps = max(1, min(20, (distance * 1.5).toInt()))
        val stepVec = end.subtract(start).multiply(1.0 / steps.toDouble())
        var currentX = start.x
        var currentY = start.y
        var currentZ = start.z
        repeat(steps) {
            currentX += stepVec.x
            currentY += stepVec.y
            currentZ += stepVec.z
            world.spawnParticles(ParticleTypes.CRIT, currentX, currentY, currentZ, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun spawnHeadParticles(
        world: ServerWorld,
        position: Vec3d,
        state: HookState,
        movementSpeed: Double,
        force: Boolean = false
    ) {
        val interval = if (movementSpeed > 0.6) 1 else 3
        if (!force && state.totalTicks - state.lastHeadParticleTick < interval) {
            return
        }
        world.spawnParticles(ParticleTypes.POOF, position.x, position.y, position.z, 1, 0.0, 0.0, 0.0, 0.0)
        state.lastHeadParticleTick = state.totalTicks
    }

    private fun raycast(world: ServerWorld, start: Vec3d, end: Vec3d, player: ServerPlayerEntity): HitResult {
        val context = RaycastContext(
            start,
            end,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            player
        )
        return world.raycast(context)
    }

    private fun requiredChargeTicks(stack: ItemStack): Int {
        return 25
    }

}
