package me.bloo.dungeons.ui

import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import java.nio.charset.StandardCharsets
import java.util.*
import me.bloo.dungeons.config.DungeonTypeDisplay
import me.bloo.dungeons.config.DungeonTypeDisplayConfig
import me.bloo.dungeons.dungeon.manager.DungeonManager
import me.bloo.dungeons.dungeon.runtime.LockedChestHandler
import me.bloo.dungeons.message.DungeonMessageType
import me.bloo.dungeons.message.sendDungeonMessage
import me.bloo.dungeons.party.PartyService
import me.bloo.dungeons.sound.SoundService
import me.bloo.dungeons.player.stats.DungeonStatsStore
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.LoreComponent
import net.minecraft.component.type.ProfileComponent
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.inventory.SimpleInventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.screen.NamedScreenHandlerFactory
import net.minecraft.screen.ScreenHandler
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier

object Guis {
    private val inviteWatchers = mutableSetOf<UUID>()
    private val partyWatchers = mutableSetOf<UUID>()
    private val publicWatchers = mutableSetOf<UUID>()
    private fun idx(oneBased: Int) = (oneBased - 1).coerceIn(0, 26)

    private fun named(stack: ItemStack, name: String): ItemStack {
        val display = colorize(ensureBold(name))
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(display))
        return stack
    }

    private fun fillerStack(): ItemStack {
        val stack = ItemStack(Items.BLACK_STAINED_GLASS_PANE)
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "))
        return stack
    }

    private fun colorize(input: String): String = input.replace('&', '§')

    private fun ensureBold(input: String): String {
        val hasBold = Regex("(?i)(?:&|§)l").containsMatchIn(input)
        if (hasBold) return input
        val prefixMatch = Regex("^((?:(?:&|§)[0-9a-fk-or])+)", RegexOption.IGNORE_CASE).find(input)
        return if (prefixMatch != null) {
            val prefix = prefixMatch.value
            prefix + "&l" + input.substring(prefix.length)
        } else {
            "&l$input"
        }
    }

    private fun stackForIcon(id: String): ItemStack {
        val identifier = Identifier.tryParse(id)
        if (identifier != null) {
            val block = Registries.BLOCK.getOrEmpty(identifier).orElse(null)
            if (block != null) {
                val blockItem = block.asItem()
                if (blockItem != Items.AIR) {
                    return ItemStack(blockItem)
                }
            }
            val item = Registries.ITEM.getOrEmpty(identifier).orElse(null)
            if (item != null && item != Items.AIR) {
                return ItemStack(item)
            }
        }
        return ItemStack(Items.BARRIER)
    }

    private fun applyLore(stack: ItemStack, description: String) {
        val normalized = description.replace("\\n", "\n")
        val lines = normalized.split('\n').mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                null
            } else {
                Text.literal(colorize(trimmed))
            }
        }
        if (lines.isNotEmpty()) {
            stack.set(DataComponentTypes.LORE, LoreComponent(lines))
        }
    }

    private fun displayNameForType(type: String?): String {
        if (type.isNullOrBlank()) return "None"
        val configured = DungeonTypeDisplayConfig.get(type)
        val configuredName = configured?.name?.takeIf { it.isNotBlank() }
        return configuredName ?: formatTypeId(type)
    }

    private fun formatTypeId(type: String): String {
        if (type.isBlank()) return ""
        return type.split(Regex("[\\s_-]+")).filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                val lower = part.lowercase()
                lower.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
            }
    }

    private fun partyLoreLines(party: PartyService.Party): List<Text> {
        val lines = mutableListOf<Text>()
        val count = party.order.size.coerceAtLeast(0)
        lines += Text.literal("${count}/4 players").formatted(Formatting.GRAY)
        with(PartyService) {
            party.membersOrdered().forEach { member ->
                val name = lookupName(member) ?: "Unknown"
                lines += Text.literal(name).formatted(Formatting.WHITE)
            }
        }
        val selected = displayNameForType(party.selectedType)
        val typeText = if (selected.equals("None", ignoreCase = true)) {
            Text.literal("Dungeon: None").formatted(Formatting.DARK_GRAY)
        } else {
            Text.literal("Dungeon: $selected").formatted(Formatting.DARK_AQUA)
        }
        lines += typeText
        return lines
    }

    private fun fillEmptySlots(inventory: SimpleInventory) {
        val filler = fillerStack()
        for (slot in 0 until inventory.size()) {
            if (inventory.getStack(slot).isEmpty) {
                inventory.setStack(slot, filler.copy())
            }
        }
    }

    /** Build a head item from just the profile name so invites feel personal. */
    private fun headByName(display: String, profileName: String): ItemStack {
        val s = ItemStack(Items.PLAYER_HEAD)
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(colorize(ensureBold(display))))
        val gp = resolveProfileByName(profileName)
        s.set(DataComponentTypes.PROFILE, ProfileComponent(gp))
        return s
    }

    private fun playerHead(uuid: UUID, name: String): ItemStack {
        val s = ItemStack(Items.PLAYER_HEAD)
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(colorize(ensureBold(name))))
        val gp = resolveProfileByUuid(uuid, name)
        s.set(DataComponentTypes.PROFILE, ProfileComponent(gp))
        return s
    }

    private const val CHECKMARK_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTc5YTVjOTVlZTE3YWJmZWY0NWM4ZGMyMjQxODk5NjQ5NDRkNTYwZjE5YTQ0ZjE5ZjhhNDZhZWYzZmVlNDc1NiJ9fX0="
    private const val XMARK_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmViNTg4YjIxYTZmOThhZDFmZjRlMDg1YzU1MmRjYjA1MGVmYzljYWI0MjdmNDYwNDhmMThmYzgwMzQ3NWY3In19fQ=="
    private const val BACK_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjUzNDc0MjNlZTU1ZGFhNzkyMzY2OGZjYTg1ODE5ODVmZjUzODlhNDU0MzUzMjFlZmFkNTM3YWYyM2QifX19"
    private const val EXPEDITIONS_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzBjZjc0ZTI2MzhiYTVhZDMyMjM3YTM3YjFkNzZhYTEyM2QxODU0NmU3ZWI5YTZiOTk2MWU0YmYxYzNhOTE5In19fQ=="
    private const val PUBLIC_PARTIES_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvN2E1MTRiY2NhOGEwNDlhYWRlY2QxNDhhZTA0MzU3YWQwZDUzMzVkYjMwMDMzYTdiM2RkYjU3MWZkNjBkMTc1OCJ9fX0="
    private const val START_BUTTON_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvY2UyYTUzMGY0MjcyNmZhN2EzMWVmYWI4ZTQzZGFkZWUxODg5MzdjZjgyNGFmODhlYThlNGM5M2E0OWM1NzI5NCJ9fX0="
    private const val DUNGEON_STATS_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjliOTU3ODgyNTA0OGQyZTU0NWM4Y2M4MDQ5NWFjYzJjZGNmOTVlMzY2MjVmNmI1N2FiMTllMWUyYThiOWRhOCJ9fX0="

    private fun headByTexture(display: String, texture: String): ItemStack {
        val stack = ItemStack(Items.PLAYER_HEAD)
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(colorize(ensureBold(display))))
        val profile = GameProfile(UUID.randomUUID(), "")
        profile.properties.put("textures", Property("textures", texture))
        stack.set(DataComponentTypes.PROFILE, ProfileComponent(profile))
        return stack
    }

    /** Swap between ready/not-ready icons using the baked head textures. */
    private fun readyIcon(ready: Boolean, ownerName: String): ItemStack {
        return if (ready) {
            headByTexture("READY: $ownerName", CHECKMARK_TEXTURE)
        } else {
            headByTexture("NOT READY: $ownerName", XMARK_TEXTURE)
        }
    }

    private fun statsHead(player: ServerPlayerEntity): ItemStack {
        val stats = DungeonStatsStore.getStats(player.uuid)
        val stack = headByTexture("&eDungeon Stats", DUNGEON_STATS_TEXTURE)
        val typeNames = if (stats.dungeonTypesCompleted.isEmpty()) {
            "None yet"
        } else {
            stats.dungeonTypesCompleted
                .sorted()
                .joinToString(", ") { type -> formatTypeId(type) }
        }
        val lore = listOf(
            Text.literal(colorize("&6&l${player.gameProfile.name} Stats!")),
            Text.literal(colorize("&eDungeons Completed: &f${stats.completions}")),
            Text.literal(colorize("&6Legendary Dungeons: &f${stats.legendaryRuns}")),
            Text.literal(colorize("&bDungeon Types Completed:")),
            Text.literal(colorize("&7$typeNames"))
        )
        stack.set(DataComponentTypes.LORE, LoreComponent(lore))
        return stack
    }

    fun openMainMenu(player: ServerPlayerEntity) {
        val inv = SimpleInventory(27)
        inv.setStack(idx(11), headByTexture("&6Expeditions", EXPEDITIONS_TEXTURE))
        inv.setStack(idx(12), named(ItemStack(Items.WRITABLE_BOOK), "Party Invites"))
        inv.setStack(idx(14), statsHead(player))
        inv.setStack(idx(16), named(stackForIcon("cobblemon:relic_coin"), "&5Party"))
        inv.setStack(idx(17), headByTexture("&aPublic Parties", PUBLIC_PARTIES_TEXTURE))

        val clicks = mutableMapOf<Int, (ServerPlayerEntity, Int) -> Unit>()
        clicks[idx(11)] = { p, _ ->
            SoundService.playUiSound(p, SoundEvents.UI_BUTTON_CLICK)
            openDungeonSelectGui(p)
        }
        clicks[idx(12)] = { p, _ ->
            SoundService.playUiSound(p, SoundEvents.UI_BUTTON_CLICK)
            openInvitesGui(p)
        }
        clicks[idx(16)] = { p, _ ->
            SoundService.playUiSound(p, SoundEvents.UI_BUTTON_CLICK)
            openPartyGui(p)
        }
        clicks[idx(17)] = { p, _ ->
            SoundService.playUiSound(p, SoundEvents.UI_BUTTON_CLICK)
            openPublicPartiesGui(p)
        }

        openLockedChest(player, inv, Text.literal("Dungeons"), clicks)
    }

    fun openPartyGui(player: ServerPlayerEntity) {
        val inv = SimpleInventory(27)
        val clicks = mutableMapOf<Int, (ServerPlayerEntity, Int) -> Unit>()
        val party = PartyService.getPartyOf(player.uuid)

        if (party != null) {
            inv.setStack(idx(9), headByTexture("Leave Party", XMARK_TEXTURE))
            clicks[idx(9)] = { clicker, _ ->
                val result = PartyService.leaveParty(clicker)
                if (result.playClickSound) {
                    SoundService.playUiSound(clicker, SoundEvents.UI_BUTTON_CLICK)
                } else {
                    SoundService.playUiSound(clicker, SoundEvents.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f)
                }
                clicker.sendDungeonMessage(
                    result.message,
                    if (result.success) DungeonMessageType.NORMAL else DungeonMessageType.ERROR
                )
                if (result.success) {
                    openPartyGui(clicker)
                }
            }

            val headSlots = listOf(12, 13, 14, 15)
            val readySlots = listOf(21, 22, 23, 24)

            val ordered = with(PartyService) { party.membersOrdered() }
            val nameOf = { u: UUID -> PartyService.lookupName(u) ?: "Unknown" }

            // player heads: right-click lets the leader boot folks (not yourself).
            for (i in ordered.indices) {
                if (i >= headSlots.size) break
                val member = ordered[i]
                val name = nameOf(member)
                inv.setStack(idx(headSlots[i]), playerHead(member, name))
                clicks[idx(headSlots[i])] = { clicker, btn ->
                    if (btn == 1 && clicker.uuid != member) {
                        if (party.leader == clicker.uuid) {
                            SoundService.playUiSound(clicker, SoundEvents.UI_BUTTON_CLICK)
                            val res = PartyService.kick(clicker, member)
                            if (!res.success) {
                                clicker.sendDungeonMessage(res.message, DungeonMessageType.ERROR)
                            }
                        } else {
                            SoundService.playUiSound(
                                clicker,
                                SoundEvents.BLOCK_NOTE_BLOCK_BASS,
                                0.5f,
                                0.5f
                            )
                            clicker.sendDungeonMessage(
                                "Only the leader can kick members.",
                                DungeonMessageType.ERROR
                            )
                        }
                    }
                }
            }
            // ready toggles are self-serve; we play the click just for that player.
            for (i in ordered.indices) {
                if (i >= readySlots.size) break
                val member = ordered[i]
                val name = nameOf(member)
                val isReady = party.ready[member] == true
                inv.setStack(idx(readySlots[i]), readyIcon(isReady, name))

                clicks[idx(readySlots[i])] = { clicker, _ ->
                    if (clicker.uuid == member) {
                        val nowReady = PartyService.toggleReady(clicker) // flips your ready flag instantly.
                        val sound = if (nowReady) {
                            SoundEvents.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE
                        } else {
                            SoundEvents.BLOCK_NOTE_BLOCK_GUITAR
                        }
                        SoundService.playUiSound(clicker, sound)
                    } else {
                        SoundService.playUiSound(
                            clicker,
                            SoundEvents.BLOCK_NOTE_BLOCK_BASS,
                            0.5f,
                            0.5f
                        )
                    }
                }
            }

            if (party.order.all { party.ready[it] == true } && party.selectedType != null && player.uuid == party.leader) {
                inv.setStack(idx(10), headByTexture("Start", START_BUTTON_TEXTURE))
                clicks[idx(10)] = { p, _ ->
                    val res = PartyService.startDungeon(p)
                    if (res.playClickSound) {
                        SoundService.playUiSound(p, SoundEvents.UI_BUTTON_CLICK)
                    }
                    if (!res.success) {
                        p.sendDungeonMessage(res.message, DungeonMessageType.ERROR)
                    }
                }
            }
        } else {
            // no party yet? drop in quick-create buttons.
            inv.setStack(idx(12), named(ItemStack(Items.PURPLE_CONCRETE), "Create Private Party"))
            inv.setStack(idx(16), named(ItemStack(Items.LIME_CONCRETE), "Create Public Party"))

            clicks[idx(12)] = { p, _ ->
                SoundService.playUiSound(p, SoundEvents.UI_BUTTON_CLICK)
                val res = PartyService.createParty(p, false)
                p.sendDungeonMessage(
                    res.message,
                    if (res.success) DungeonMessageType.NORMAL else DungeonMessageType.ERROR
                )
                if (res.success) openPartyGui(p)
            }
            clicks[idx(16)] = { p, _ ->
                SoundService.playUiSound(p, SoundEvents.UI_BUTTON_CLICK)
                val res = PartyService.createParty(p, true)
                p.sendDungeonMessage(
                    res.message,
                    if (res.success) DungeonMessageType.NORMAL else DungeonMessageType.ERROR
                )
                if (res.success) openPartyGui(p)
            }
        }

        inv.setStack(idx(27), headByTexture("Back", BACK_TEXTURE))
        clicks[idx(27)] = { p, _ ->
            SoundService.playUiSound(p, SoundEvents.UI_BUTTON_CLICK)
            openMainMenu(p)
        }

        openLockedChest(player, inv, Text.literal("Party"), clicks) { partyWatchers.remove(it.uuid) }
        partyWatchers.add(player.uuid)
    }

    fun openInvitesGui(player: ServerPlayerEntity) {
        val inv = SimpleInventory(27)
        val invites = PartyService.getInvites(player.uuid).sortedBy { it.createdAt }.take(27)
        val slotMap = mutableMapOf<Int, PartyService.PartyInvite>()
        invites.forEachIndexed { i, invite ->
            val nm = PartyService.lookupName(invite.from) ?: "Unknown"
            val head = headByName("Invite: $nm", nm)
            val party = PartyService.getParty(invite.partyId)
            val lore = party?.let { partyLoreLines(it) }
                ?: listOf(Text.literal("Party unavailable").formatted(Formatting.RED))
            head.set(DataComponentTypes.LORE, LoreComponent(lore))
            inv.setStack(i, head)
            slotMap[i] = invite
        }
        val clicks = mutableMapOf<Int, (ServerPlayerEntity, Int) -> Unit>()
        for ((s, invite) in slotMap) {
            clicks[s] = { p, button ->
                if (button == 0) {
                    SoundService.playUiSound(p, SoundEvents.UI_BUTTON_CLICK)
                    val res = PartyService.acceptInvite(p, invite)
                    p.sendDungeonMessage(
                        res.message,
                        if (res.success) DungeonMessageType.NORMAL else DungeonMessageType.ERROR
                    )
                } else if (button == 1) {
                    SoundService.playUiSound(p, SoundEvents.UI_BUTTON_CLICK)
                    PartyService.declineInvite(p.uuid, invite)
                }
            }
        }
        inv.setStack(idx(27), headByTexture("Back", BACK_TEXTURE))
        clicks[idx(27)] = { p, _ ->
            SoundService.playUiSound(p, SoundEvents.UI_BUTTON_CLICK)
            openMainMenu(p)
        }
        openLockedChest(player, inv, Text.literal("Party Invites"), clicks) { inviteWatchers.remove(it.uuid) }
        inviteWatchers.add(player.uuid)
    }

    fun openPublicPartiesGui(player: ServerPlayerEntity) {
        val inv = SimpleInventory(27)
        val publics = PartyService.listPublicParties().take(27)

        val slotToParty = mutableMapOf<Int, Int>()
        publics.forEachIndexed { i, party ->
            val hostName = PartyService.lookupName(party.leader) ?: "Host"
            val head = playerHead(party.leader, hostName)
            head.set(DataComponentTypes.LORE, LoreComponent(partyLoreLines(party)))

            inv.setStack(i, head)
            slotToParty[i] = party.id
        }

        val clicks = mutableMapOf<Int, (ServerPlayerEntity, Int) -> Unit>()
        for ((s, id) in slotToParty) {
            clicks[s] = { p, button ->
                if (button == 0) {
                    SoundService.playUiSound(p, SoundEvents.UI_BUTTON_CLICK)
                    val res = PartyService.joinPublicParty(p, id)
                    if (res.success) {
                        openPartyGui(p)
                    } else {
                        p.sendDungeonMessage(res.message, DungeonMessageType.ERROR)
                    }
                }
            }
        }

        inv.setStack(idx(27), headByTexture("Back", BACK_TEXTURE))
        clicks[idx(27)] = { p, _ ->
            SoundService.playUiSound(p, SoundEvents.UI_BUTTON_CLICK)
            openMainMenu(p)
        }

        openLockedChest(player, inv, Text.literal("Public Parties"), clicks) { publicWatchers.remove(it.uuid) }
        publicWatchers.add(player.uuid)
    }

    fun openDungeonSelectGui(player: ServerPlayerEntity) {
        val party = PartyService.getPartyOf(player.uuid)
        if (party == null) {
            player.sendDungeonMessage("Create a party first.", DungeonMessageType.ERROR)
            return
        }
        val inv = SimpleInventory(27)
        val slotMap = mutableMapOf<Int, String>()
        val available = DungeonManager.listTypes()
        val configured = DungeonTypeDisplayConfig.all()
        val combined = (available.keys + configured.keys).distinct()
        var slot = 0
        combined.forEach { type ->
            if (slot >= 26) return@forEach
            val display = configured[type] ?: DungeonTypeDisplay(
                name = type,
                icon = available[type] ?: "minecraft:stone",
                enabled = true,
                description = ""
            )
            if (!display.enabled) return@forEach
            val stack = stackForIcon(display.icon)
            val displayName = ensureBold(display.name.ifBlank { type })
            stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(colorize(displayName)))
            if (display.description.isNotBlank()) {
                applyLore(stack, display.description)
            }
            inv.setStack(slot, stack)
            slotMap[slot] = type
            slot++
        }
        val clicks = mutableMapOf<Int, (ServerPlayerEntity, Int) -> Unit>()
        for ((slot, type) in slotMap) {
            clicks[slot] = { p, _ ->
                SoundService.playUiSound(p, SoundEvents.UI_BUTTON_CLICK)
                val res = PartyService.selectDungeon(p, type)
                p.sendDungeonMessage(
                    res.message,
                    if (res.success) DungeonMessageType.NORMAL else DungeonMessageType.ERROR
                )
            }
        }
        inv.setStack(idx(27), headByTexture("Back", BACK_TEXTURE))
        clicks[idx(27)] = { p, _ ->
            SoundService.playUiSound(p, SoundEvents.UI_BUTTON_CLICK)
            openMainMenu(p)
        }
        openLockedChest(player, inv, Text.literal("Select Dungeon"), clicks)
    }

    fun refreshInvitesGui(target: UUID) {
        if (target in inviteWatchers) {
            PartyService.server?.playerManager?.getPlayer(target)?.let { openInvitesGui(it) }
        }
    }

    fun refreshPartyGui(party: PartyService.Party) {
        val srv = PartyService.server ?: return
        party.order.forEach { member ->
            if (member in partyWatchers) {
                srv.playerManager.getPlayer(member)?.let { openPartyGui(it) }
            }
        }
    }

    fun refreshPublicPartiesGui() {
        val srv = PartyService.server ?: return
        publicWatchers.forEach { uuid ->
            srv.playerManager.getPlayer(uuid)?.let { openPublicPartiesGui(it) }
        }
    }

    /** Resolve a GameProfile by name, keeping the UUID solid via cache or offline fallback. */
    private fun resolveProfileByName(name: String): GameProfile {
        // check online first—live players already have the good profile data.
        PartyService.server?.playerManager?.getPlayer(name)?.gameProfile?.let { return it }

        // hit the user cache next; your mappings wrap it in an optional.
        val opt = PartyService.server?.userCache?.findByName(name)
        val cached: GameProfile? = opt?.orElse(null)
        if (cached != null) return cached

        // final fallback: deterministic offline UUID to mirror vanilla.
        val id = UUID.nameUUIDFromBytes(("OfflinePlayer:$name").toByteArray(StandardCharsets.UTF_8))
        return GameProfile(id, name)
    }

    /** Resolve a GameProfile by UUID, preferring cached/online data before a bare stub. */
    private fun resolveProfileByUuid(id: UUID, name: String): GameProfile {
        // if they’re online, the profile’s already loaded—use it.
        PartyService.server?.playerManager?.getPlayer(id)?.gameProfile?.let { return it }

        // otherwise ask the cache; again wrapped optional in these mappings.
        val opt = PartyService.server?.userCache?.getByUuid(id)
        val cached: GameProfile? = opt?.orElse(null)
        val profile: GameProfile = cached ?: GameProfile(id, name)

        // best effort: poke sessionService.fillProfileProperties if we can reach it.
        try {
            val ss = PartyService.server?.sessionService
            if (ss != null) {
                val maybe = ss.javaClass.methods.firstOrNull { m ->
                    m.name == "fillProfileProperties" &&
                            m.parameterTypes.size == 2 &&
                            GameProfile::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                            (m.parameterTypes[1] == java.lang.Boolean.TYPE || m.parameterTypes[1] == java.lang.Boolean::class.java)
                }
                if (maybe != null) {
                    maybe.isAccessible = true
                    // return type is wild; we only care that textures get stuffed into profile.
                    maybe.invoke(ss, profile, true)
                }
            }
        } catch (_: Exception) {
            // shrug off lookup failures—better to keep whatever data we managed.
        }

        return profile
    }



    /** Open a locked-chest style inventory wired to our click callbacks. */
    private fun openLockedChest(
        player: ServerPlayerEntity,
        chestInv: SimpleInventory,
        title: Text,
        clickActions: MutableMap<Int, (ServerPlayerEntity, Int) -> Unit>,
        onClose: (ServerPlayerEntity) -> Unit = {}
    ) {
        fillEmptySlots(chestInv)
        val factory = object : NamedScreenHandlerFactory {
            override fun getDisplayName(): Text = title
            override fun createMenu(syncId: Int, playerInv: PlayerInventory, playerEntity: PlayerEntity): ScreenHandler {
                return LockedChestHandler(syncId, playerInv, chestInv, 3, clickActions, onClose)
            }
        }
        player.openHandledScreen(factory)
    }
}
