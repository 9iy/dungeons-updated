package me.bloo.dungeons.economy

import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

object Economy {

    @Volatile
    private var cachedService: Any? = null

    fun service(): Any? {
        val cached = cachedService
        if (cached != null) {
            return cached
        }
        val resolved = resolveService()
        if (resolved != null) {
            cachedService = resolved
        }
        return resolved
    }

    private fun resolveService(): Any? {
        val impactorClass = runCatching { Class.forName("net.impactdev.impactor.api.Impactor") }.getOrNull()
            ?: return null
        val economyServiceClass = runCatching {
            Class.forName("net.impactdev.impactor.api.economy.EconomyService")
        }.getOrNull() ?: return null
        return try {
            val instanceMethod = runCatching { impactorClass.getMethod("instance") }.getOrNull()
            if (instanceMethod != null) {
                val instance = instanceMethod.invoke(null)
                val services = runCatching { instance.javaClass.getMethod("services").invoke(instance) }.getOrNull()
                val provide = services?.javaClass?.methods?.firstOrNull {
                    it.name == "provide" && it.parameterTypes.size == 1
                }
                val supplied = provide?.invoke(services, economyServiceClass)
                unwrapOptional(supplied)
            } else {
                val getService = impactorClass.methods.firstOrNull {
                    it.name == "getService" && it.parameterTypes.size == 1
                }
                val supplied = getService?.invoke(null, economyServiceClass)
                unwrapOptional(supplied)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun currency(id: String): Any? {
        val service = service() ?: return null
        val namespaced = normalizeCurrencyId(id)
        val currencies = runCatching { service.javaClass.getMethod("currencies").invoke(service) }.getOrNull()
            ?: return null
        val findMethod = currencies.javaClass.methods.firstOrNull {
            it.name == "find" && it.parameterTypes.size == 1
        }
        if (findMethod != null) {
            val result = runCatching { findMethod.invoke(currencies, namespaced) }.getOrNull()
            val unwrapped = unwrapOptional(result)
            if (unwrapped != null) {
                return unwrapped
            }
        }
        val currencyMethod = currencies.javaClass.methods.firstOrNull {
            it.name == "currency" && it.parameterTypes.size == 1
        }
        if (currencyMethod != null) {
            val key = createKey(namespaced)
            if (key != null) {
                val result = runCatching { currencyMethod.invoke(currencies, key) }.getOrNull()
                val unwrapped = unwrapOptional(result)
                if (unwrapped != null) {
                    return unwrapped
                }
            }
        }
        return null
    }

    fun account(uuid: UUID, currency: Any): CompletableFuture<Any?> {
        val service = service() ?: return CompletableFuture.completedFuture(null)
        val direct = service.javaClass.methods.firstOrNull {
            it.name == "account" && it.parameterTypes.size == 2
        }
        if (direct != null) {
            return CompletableFuture.supplyAsync({
                val result = runCatching { direct.invoke(service, currency, uuid) }.getOrNull()
                unwrapCompletionStage(result)
            }, EconomyHooks.executor)
        }
        val accounts = runCatching { service.javaClass.getMethod("accounts").invoke(service) }.getOrNull()
            ?: return CompletableFuture.completedFuture(null)
        return CompletableFuture.supplyAsync({
            val accountMethod = accounts.javaClass.methods.firstOrNull {
                it.name == "account" && it.parameterTypes.size == 1 && it.parameterTypes[0] == UUID::class.java
            }
            val createMethod = accounts.javaClass.methods.firstOrNull {
                it.name == "create" && it.parameterTypes.size == 1 && it.parameterTypes[0] == UUID::class.java
            }
            val existing = accountMethod?.let { runCatching { it.invoke(accounts, uuid) }.getOrNull() }
            val resolved = unwrapOptional(existing)
            resolved ?: createMethod?.let { runCatching { it.invoke(accounts, uuid) }.getOrNull() }
        }, EconomyHooks.executor)
    }

    internal fun unwrapOptional(candidate: Any?): Any? {
        return when (candidate) {
            is Optional<*> -> candidate.orElse(null)
            else -> candidate
        }
    }

    private fun unwrapCompletionStage(candidate: Any?): Any? {
        return when (candidate) {
            is CompletionStage<*> -> candidate.toCompletableFuture().get()
            is CompletableFuture<*> -> candidate.get()
            is Optional<*> -> candidate.orElse(null)
            else -> candidate
        }
    }

    private fun createKey(namespaced: String): Any? {
        val namespace = namespaced.substringBefore(':')
        val value = namespaced.substringAfter(':')
        val keyClass = runCatching { Class.forName("net.kyori.adventure.key.Key") }.getOrNull() ?: return null
        val factory = keyClass.methods.firstOrNull { it.name == "key" && it.parameterTypes.size == 2 }
        return runCatching { factory?.invoke(null, namespace, value) }.getOrNull()
    }
}

fun normalizeCurrencyId(id: String): String {
    val trimmed = id.trim()
    if (trimmed.isEmpty()) return ""
    return if (':' in trimmed) trimmed else "impactor:$trimmed"
}
