package me.bloo.dungeons.message

import com.google.gson.GsonBuilder
import io.github.miniplaceholders.api.MiniPlaceholders
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import me.bloo.dungeons.config.DungeonConfigPaths
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text

enum class DungeonMessageType {
    NORMAL,
    ERROR
}

private const val DEFAULT_PREFIX = "<gold><bold>[ DUNGEONS ]</bold></gold> <yellow>"
private const val DEFAULT_ERROR_PREFIX = "<gold><bold>[ DUNGEONS ]</bold></gold> <red>"

/** Centralized MiniMessage + MiniPlaceholders message service for Dungeons. */
object DungeonMessages {
    private data class DungeonMessagesConfig(val messages: Map<String, String> = emptyMap())

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val miniMessage = MiniMessage.miniMessage()
    private val legacySerializer = LegacyComponentSerializer.legacySection()
    private val path = DungeonConfigPaths.resolve("dungeon_messages.json")
    private val defaults: MutableMap<String, String> = ConcurrentHashMap(
        mapOf(
            "prefix.normal" to DEFAULT_PREFIX,
            "prefix.error" to DEFAULT_ERROR_PREFIX,
            "party.member_joined" to "<yellow><player></yellow> joined <yellow><party></yellow>.",
            "party.member_left" to "<yellow><player></yellow> left <yellow><party></yellow>.",
            "party.member_removed" to "<yellow><player></yellow> was removed from <yellow><party></yellow>.",
            "party.member_disconnected" to "<yellow><player></yellow> disconnected from <yellow><party></yellow>.",
            "party.ready.summary" to "<yellow><summary></yellow>",
            "party.type.select" to "Select a dungeon type first.",
            "party.start.requirements" to "<red><summary></red>",
            "party.start.blocked" to "<red>⛔ <reason></red>",
            "party.start.fail" to "<red>⛔ <reason></red>",
            "party.dungeon.selected" to "<yellow><player></yellow> selected <aqua><dungeon></aqua>.",
            "party.invite.received" to "You have a party invite from <yellow><player></yellow>. Use /dungeons party invites."
        )
    )

    @Volatile
    private var messages: MutableMap<String, String> = load().toMutableMap()

    fun reload() {
        messages = load().toMutableMap()
    }

    fun placeholder(key: String, value: String): TagResolver =
        TagResolver.resolver(key) { _, _ -> Tag.inserting(Component.text(value)) }

    fun component(
        key: String,
        fallback: String,
        type: DungeonMessageType = DungeonMessageType.NORMAL,
        prefixed: Boolean = true,
        vararg placeholders: TagResolver
    ): Component {
        val template = resolveTemplate(key, fallback)
        val prefixKey = if (type == DungeonMessageType.ERROR) "prefix.error" else "prefix.normal"
        val prefix = if (prefixed) resolveTemplate(prefixKey, if (type == DungeonMessageType.ERROR) DEFAULT_ERROR_PREFIX else DEFAULT_PREFIX) else ""
        val fullTemplate = prefix + template
        val resolverBuilder = TagResolver.builder()
        resolverBuilder.resolver(MiniPlaceholders.getGlobalPlaceholders())
        placeholders.forEach { resolverBuilder.resolver(it) }
        val component = miniMessage.deserialize(fullTemplate, resolverBuilder.build())
        return component
    }

    fun send(
        player: ServerPlayerEntity,
        key: String,
        fallback: String,
        type: DungeonMessageType = DungeonMessageType.NORMAL,
        vararg placeholders: TagResolver
    ) {
        player.sendMessage(toText(component(key, fallback, type, true, *placeholders)), false)
    }

    fun broadcast(
        source: ServerCommandSource,
        key: String,
        fallback: String,
        type: DungeonMessageType = DungeonMessageType.NORMAL,
        vararg placeholders: TagResolver
    ) {
        val text = toText(component(key, fallback, type, true, *placeholders))
        source.server.playerManager.playerList.forEach { player ->
            player.sendMessage(text, false)
        }
    }

    fun feedback(
        source: ServerCommandSource,
        key: String,
        fallback: String,
        type: DungeonMessageType = DungeonMessageType.NORMAL,
        broadcastToOps: Boolean = false,
        vararg placeholders: TagResolver
    ) {
        source.sendFeedback({ toText(component(key, fallback, type, true, *placeholders)) }, broadcastToOps)
    }

    fun text(
        key: String,
        fallback: String,
        type: DungeonMessageType = DungeonMessageType.NORMAL,
        prefixed: Boolean = true,
        vararg placeholders: TagResolver
    ): Text = toText(component(key, fallback, type, prefixed, *placeholders))

    private fun resolveTemplate(key: String, fallback: String): String {
        defaults.putIfAbsent(key, fallback)
        val existing = messages[key]
        if (existing != null) return existing
        messages[key] = fallback
        save()
        return fallback
    }

    private fun load(): Map<String, String> {
        return try {
            if (Files.exists(path)) {
                Files.newBufferedReader(path).use { reader ->
                    val loaded = gson.fromJson(reader, DungeonMessagesConfig::class.java)?.messages ?: emptyMap()
                    val merged = defaults.toMutableMap()
                    merged.putAll(loaded)
                    save(merged)
                    merged
                }
            } else {
                save(defaults)
                defaults
            }
        } catch (e: Exception) {
            e.printStackTrace()
            defaults
        }
    }

    private fun save(current: Map<String, String> = messages) {
        try {
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(path).use { writer ->
                gson.toJson(DungeonMessagesConfig(current), writer)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toText(component: Component): Text = Text.literal(legacySerializer.serialize(component))
}

fun formatMessageKey(raw: String): String {
    return raw.lowercase(Locale.getDefault())
        .replace(Regex("[^a-z0-9]+"), ".")
        .trim('.')
        .ifBlank { "message" }
}

fun ServerPlayerEntity.sendDungeonMessage(
    key: String,
    fallback: String,
    type: DungeonMessageType = DungeonMessageType.NORMAL,
    vararg placeholders: TagResolver
) {
    DungeonMessages.send(this, key, fallback, type, *placeholders)
}

fun ServerPlayerEntity.sendDungeonMessage(
    fallback: String,
    type: DungeonMessageType = DungeonMessageType.NORMAL,
    vararg placeholders: TagResolver
) {
    sendDungeonMessage(formatMessageKey(fallback), fallback, type, *placeholders)
}

fun ServerCommandSource.sendDungeonFeedback(
    key: String,
    fallback: String,
    type: DungeonMessageType = DungeonMessageType.NORMAL,
    broadcastToOps: Boolean = false,
    vararg placeholders: TagResolver
) {
    DungeonMessages.feedback(this, key, fallback, type, broadcastToOps, *placeholders)
}

fun ServerCommandSource.sendDungeonFeedback(
    fallback: String,
    type: DungeonMessageType = DungeonMessageType.NORMAL,
    broadcastToOps: Boolean = false,
    vararg placeholders: TagResolver
) {
    sendDungeonFeedback(formatMessageKey(fallback), fallback, type, broadcastToOps, *placeholders)
}

fun ServerCommandSource.sendDungeonError(key: String, fallback: String) {
    sendDungeonFeedback(key, fallback, DungeonMessageType.ERROR, false)
}

fun ServerCommandSource.sendDungeonError(fallback: String) {
    sendDungeonError(formatMessageKey(fallback), fallback)
}
