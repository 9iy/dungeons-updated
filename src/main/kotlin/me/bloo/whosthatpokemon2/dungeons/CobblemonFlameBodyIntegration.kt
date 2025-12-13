package me.bloo.whosthatpokemon2.dungeons

import net.minecraft.entity.Entity
import net.minecraft.server.network.ServerPlayerEntity
import java.lang.reflect.Method
import java.util.UUID
import kotlin.jvm.functions.Function1

object CobblemonFlameBodyIntegration {
    fun init() {
        val eventsClass = runCatching { Class.forName("com.cobblemon.mod.common.api.events.CobblemonEvents") }.getOrNull()
            ?: return
        val eventField = runCatching { eventsClass.getField("POKEMON_SENT_POST") }.getOrNull() ?: return
        val eventInstance = runCatching { eventField.get(null) }.getOrNull() ?: return
        val subscribeMethod = findSubscribeMethod(eventInstance) ?: return
        val handler = object : Function1<Any, Unit> {
            override fun invoke(p1: Any): Unit {
                handlePokemonSent(p1)
                return Unit
            }
        }
        runCatching { subscribeMethod.invoke(eventInstance, handler) }
    }

    private fun handlePokemonSent(event: Any) {
        val player = extractPlayer(event) ?: return
        val pokemon = callMethod(event, "getPokemon") ?: return
        val ability = callMethod(pokemon, "getAbility") ?: return
        val abilityName = callMethod(ability, "getName") as? String
        val entity = callMethod(event, "getPokemonEntity") ?: callMethod(event, "getEntity")
        val entityUuid = extractEntityUuid(entity)
        DungeonRuntime.onPokemonSent(player, abilityName, entityUuid)
    }

    private fun extractPlayer(event: Any): ServerPlayerEntity? {
        val direct = callMethod(event, "getPlayer")
        if (direct is ServerPlayerEntity) return direct
        val trainer = callMethod(event, "getTrainer") ?: return null
        if (trainer is ServerPlayerEntity) return trainer
        val nestedPlayer = callMethod(trainer, "getPlayer")
        return nestedPlayer as? ServerPlayerEntity
    }

    private fun extractEntityUuid(entity: Any?): UUID? {
        if (entity is Entity) {
            return entity.uuid
        }
        val uuidResult = callMethod(entity, "getUuid")
        return uuidResult as? UUID
    }

    private fun callMethod(target: Any?, name: String): Any? {
        if (target == null) return null
        val method = findMethod(target.javaClass, name) ?: return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun findMethod(clazz: Class<*>, name: String): Method? {
        return runCatching { clazz.getMethod(name) }.getOrNull()
            ?: clazz.declaredMethods.firstOrNull { it.name == name }?.also { it.isAccessible = true }
    }

    private fun findSubscribeMethod(eventInstance: Any): Method? {
        val methods = eventInstance.javaClass.methods.filter { it.name == "subscribe" && it.parameterCount == 1 }
        if (methods.isEmpty()) return null
        return methods.first()
    }
}
