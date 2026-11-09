package dev.boxloom.fabric

import dev.boxloom.server.core.ApiException
import dev.boxloom.server.core.PlayerItemUseResult
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType
import java.util.UUID

/** Owns only API-started item use. Native ticks perform charging and consumption. */
internal object PlayerItemUseActions {
    private data class Session(
        val player: ServerPlayer,
        val level: ServerLevel,
        val mode: GameType,
        val slot: Int,
        val stack: ItemStack,
    )

    private val sessions = mutableMapOf<UUID, Session>()
    private fun inactive() = PlayerItemUseResult(false, null, 0)

    fun start(player: ServerPlayer): PlayerItemUseResult {
        val existing = sessions[player.uuid]
        if (existing != null && valid(existing)) return snapshot(player)
        cancel(player)
        val stack = player.mainHandItem
        if (!player.isAlive || player.isSpectator || player.hasDisconnected() ||
            !player.canInteractWithLevel() || player.isUsingItem ||
            !stack.isItemEnabled(player.level().enabledFeatures())
        ) {
            throw ApiException(409, "PLAYER_CANNOT_USE", "The player cannot start using an item in their current state")
        }
        if (stack.isEmpty || stack.getUseDuration(player) <= 0) {
            throw ApiException(409, "ITEM_NOT_CONTINUOUSLY_USABLE", "The main-hand item has no continuous use")
        }
        PlayerMiningActions.stop(player)
        PlayerBlockActions.abortMining(player)
        player.resetLastActionTime()
        try {
            // Use the item itself; looking at a chest must not open it instead of eating.
            val result = player.gameMode.useItem(player, player.level(), stack, InteractionHand.MAIN_HAND)
            if (!result.consumesAction() || !player.isUsingItem || player.usedItemHand != InteractionHand.MAIN_HAND) {
                return inactive()
            }
            sessions[player.uuid] = Session(
                player, player.level(), player.gameMode(), player.inventory.selectedSlot, player.useItem,
            )
            return snapshot(player)
        } catch (error: Exception) {
            // A failing mod callback must not leave a half-started use running.
            if (player.isUsingItem && player.usedItemHand == InteractionHand.MAIN_HAND) player.stopUsingItem()
            throw error
        }
    }

    fun stop(player: ServerPlayer): PlayerItemUseResult {
        val session = sessions.remove(player.uuid) ?: return inactive()
        if (valid(session)) {
            // Release fires a charged bow; food and drink are interrupted without consumption.
            try {
                player.releaseUsingItem()
            } finally {
                cancelNativeUse(session)
                player.inventoryMenu.sendAllDataToRemote()
            }
        } else {
            cancelNativeUse(session)
        }
        return inactive()
    }

    /** End ownership without release effects (e.g. never fire a bow on disconnect). */
    fun cancel(player: ServerPlayer) {
        sessions.remove(player.uuid)?.let(::cancelNativeUse)
    }

    /** Drop ownership immediately on native completion, before a new client use can begin. */
    fun onNativeStop(player: ServerPlayer) {
        if (sessions[player.uuid]?.player === player) sessions.remove(player.uuid)
    }

    fun suppressClientRelease(player: ServerPlayer): Boolean {
        val session = sessions[player.uuid] ?: return false
        if (valid(session)) return true
        cancel(player)
        return false
    }

    fun tick(server: MinecraftServer) {
        for (session in sessions.values.toList()) {
            if (server.playerList.getPlayer(session.player.uuid) !== session.player || !valid(session)) {
                cancel(session.player)
            }
        }
    }

    fun clear() {
        sessions.values.toList().forEach { cancel(it.player) }
    }

    private fun valid(session: Session): Boolean {
        val player = session.player
        return player.isAlive && !player.hasDisconnected() && !player.isSpectator &&
            player.canInteractWithLevel() && player.level() === session.level && player.gameMode() == session.mode &&
            player.inventory.selectedSlot == session.slot && player.mainHandItem === session.stack &&
            player.isUsingItem && player.usedItemHand == InteractionHand.MAIN_HAND && player.useItem === session.stack &&
            session.stack.isItemEnabled(player.level().enabledFeatures())
    }

    private fun cancelNativeUse(session: Session) {
        val player = session.player
        if (player.isUsingItem && player.usedItemHand == InteractionHand.MAIN_HAND && player.useItem === session.stack) {
            player.stopUsingItem()
        }
    }

    private fun snapshot(player: ServerPlayer) = PlayerItemUseResult(
        true, BuiltInRegistries.ITEM.getKey(player.useItem.item).toString(), player.useItemRemainingTicks.coerceAtLeast(0),
    )
}
