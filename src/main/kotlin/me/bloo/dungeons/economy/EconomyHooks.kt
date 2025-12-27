package me.bloo.dungeons.economy

import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors

object EconomyHooks {
    internal val executor: Executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "dungeons-economy").apply { isDaemon = true }
    }

    fun hasBalance(uuid: UUID, currencyId: String, amount: Long): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync({
            val currency = Economy.currency(currencyId) ?: return@supplyAsync false
            val account = Economy.account(uuid, currency).join() ?: return@supplyAsync false
            val balance = extractBalance(account, currency)
            balance != null && balance >= amount
        }, executor).exceptionally { false }
    }

    fun tryWithdraw(uuid: UUID, currencyId: String, amount: Long): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync({
            val currency = Economy.currency(currencyId) ?: return@supplyAsync false
            val account = Economy.account(uuid, currency).join() ?: return@supplyAsync false
            performTransaction(account, currency, amount, "withdraw")
        }, executor).exceptionally { false }
    }

    fun tryDeposit(uuid: UUID, currencyId: String, amount: Long): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync({
            val currency = Economy.currency(currencyId) ?: return@supplyAsync false
            val account = Economy.account(uuid, currency).join() ?: return@supplyAsync false
            performTransaction(account, currency, amount, "deposit")
        }, executor).exceptionally { false }
    }

    private fun extractBalance(account: Any, currency: Any): Long? {
        val withCurrency = account.javaClass.methods.firstOrNull {
            it.name == "balance" && it.parameterTypes.size == 1 && it.parameterTypes[0].isInstance(currency)
        }
        val noArg = account.javaClass.methods.firstOrNull { it.name == "balance" && it.parameterCount == 0 }
        val raw = when {
            withCurrency != null -> runCatching { withCurrency.invoke(account, currency) }.getOrNull()
            noArg != null -> runCatching { noArg.invoke(account) }.getOrNull()
            else -> null
        }
        return amountAsLong(raw)
    }

    private fun performTransaction(account: Any, currency: Any, amount: Long, methodName: String): Boolean {
        val candidates = account.javaClass.methods.filter { it.name == methodName }
        for (method in candidates) {
            val args = method.parameterTypes.mapIndexed { index, type ->
                when {
                    type.isInstance(currency) -> currency
                    type == Long::class.javaPrimitiveType || type == Long::class.java -> amount
                    type == Int::class.javaPrimitiveType || type == Int::class.java -> amount.toInt()
                    type == BigDecimal::class.java -> BigDecimal.valueOf(amount)
                    type.name.endsWith("Amount") -> createAmount(type, amount)
                    else -> null
                }
            }.toTypedArray()
            if (args.any { it == null }) continue
            val result = runCatching { method.invoke(account, *args) }.getOrNull() ?: continue
            val interpreted = interpretResult(result)
            if (interpreted != null) {
                return interpreted
            }
        }
        return false
    }

    private fun interpretResult(result: Any?): Boolean? {
        return when (result) {
            is Boolean -> result
            null -> false
            else -> {
                val methods = result.javaClass.methods
                val successMethod = methods.firstOrNull { it.name in setOf("isSuccessful", "successful", "success", "isSuccess") && it.parameterCount == 0 }
                successMethod?.let { runCatching { it.invoke(result) as? Boolean }.getOrNull() }
            }
        }
    }

    private fun createAmount(type: Class<*>, amount: Long): Any? {
        val ofLong = runCatching { type.getMethod("of", Long::class.javaPrimitiveType) }.getOrNull()
        if (ofLong != null) {
            return runCatching { ofLong.invoke(null, amount) }.getOrNull()
        }
        val ofInt = runCatching { type.getMethod("of", Int::class.javaPrimitiveType) }.getOrNull()
        if (ofInt != null) {
            return runCatching { ofInt.invoke(null, amount.toInt()) }.getOrNull()
        }
        val builder = runCatching { type.getMethod("of", BigDecimal::class.java) }.getOrNull()
        return builder?.let { runCatching { it.invoke(null, BigDecimal.valueOf(amount)) }.getOrNull() }
    }

    private fun amountAsLong(value: Any?): Long? {
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Number -> value.toLong()
            is BigDecimal -> value.toLong()
            null -> null
            else -> {
                val asLong = runCatching { value.javaClass.getMethod("asLong").invoke(value) as? Long }.getOrNull()
                asLong ?: runCatching { value.javaClass.getMethod("longValue").invoke(value) as? Long }.getOrNull()
            }
        }
    }
}
