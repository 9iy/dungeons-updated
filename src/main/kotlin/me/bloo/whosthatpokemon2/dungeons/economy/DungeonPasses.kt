package me.bloo.whosthatpokemon2.dungeons.economy

import java.util.UUID

object DungeonPasses {
    private const val PASS_CURRENCY_ID = "impactor:dungeon_pass"
    private const val PASS_COST = 1L

    fun canBypassCooldown(uuid: UUID): Boolean {
        if (!isPassCurrencyAvailable()) {
            return false
        }
        return EconomyHooks.hasBalance(uuid, PASS_CURRENCY_ID, PASS_COST).join()
    }

    fun consumePass(uuid: UUID): Boolean {
        if (!isPassCurrencyAvailable()) {
            return false
        }
        return EconomyHooks.tryWithdraw(uuid, PASS_CURRENCY_ID, PASS_COST).join()
    }

    fun refundPass(uuid: UUID): Boolean {
        if (!isPassCurrencyAvailable()) {
            return false
        }
        return EconomyHooks.tryDeposit(uuid, PASS_CURRENCY_ID, PASS_COST).join()
    }

    private fun isPassCurrencyAvailable(): Boolean {
        if (Economy.service() == null) {
            return false
        }
        return Economy.currency(PASS_CURRENCY_ID) != null
    }
}
