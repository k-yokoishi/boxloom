package dev.boxloom.fabric

import dev.boxloom.server.core.ApiException
import dev.boxloom.server.core.PlayerUseResult
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.advancements.triggers.CriteriaTriggers
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

/** Server-thread-only, main-hand right-click using the latest server-side view. */
internal object PlayerUseActions {
    fun use(player: ServerPlayer): PlayerUseResult {
        val level = player.level()
        val hand = InteractionHand.MAIN_HAND
        val stack = player.mainHandItem
        if (!player.isAlive || player.isSpectator || !player.canInteractWithLevel() || player.isUsingItem ||
            !stack.isItemEnabled(level.enabledFeatures())
        ) {
            throw ApiException(409, "PLAYER_CANNOT_USE", "The player cannot use items in their current state")
        }

        PlayerMiningActions.stop(player)
        PlayerBlockActions.abortMining(player)
        player.resetLastActionTime()
        val hit = PlayerBlockActions.raycastFromPlayerView(player, player.entityInteractionRange())
        val blockHit = (hit as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }
        val entityHit = (hit as? EntityHitResult)?.takeIf {
            player.isWithinEntityInteractionRange(it.entity, 0.0) &&
                level.worldBorder.isWithinBounds(it.entity.blockPosition())
        }
        val target = blockHit?.let { PlayerBlockActions.snapshot(player, it) }
        val before = stack.copy()
        val animation = stack.interactAnimation
        var result: InteractionResult = InteractionResult.PASS

        if (blockHit != null) {
            val pos = blockHit.blockPos
            if (level.isOutsideBuildHeight(pos) || !level.mayInteract(player, pos) ||
                level.server.isUnderSpawnProtection(level, pos, player)
            ) {
                return PlayerUseResult(false, false, target)
            }
            // Includes Fabric UseBlockCallback, native block interaction and item use-on.
            result = player.gameMode.useItemOn(player, level, stack, hand, blockHit)
            if (result.consumesAction()) {
                CriteriaTriggers.ANY_BLOCK_USE.trigger(player, pos, before)
            }
        } else if (entityHit != null) {
            // Fabric 26.3 hooks entity use in the packet listener, not Player.interactOn.
            result = UseEntityCallback.EVENT.invoker().interact(player, level, hand, entityHit.entity, entityHit)
            if (result == InteractionResult.PASS) {
                result = player.interactOn(entityHit.entity, hand, entityHit.location.subtract(entityHit.entity.position()))
            }
            if (result is InteractionResult.Success) {
                CriteriaTriggers.PLAYER_INTERACTED_WITH_ENTITY.trigger(
                    player, if (result.wasItemInteraction()) before else net.minecraft.world.item.ItemStack.EMPTY,
                    entityHit.entity,
                )
            }
        }

        // PASS permits in-air use, while FAIL (including a mod cancellation) stops the action.
        if (result == InteractionResult.PASS && !player.mainHandItem.isEmpty) {
            result = player.gameMode.useItem(player, level, player.mainHandItem, hand)
        }
        val swung = result is InteractionResult.Success && result.shouldSwing() &&
            player.swing(hand, animation, true)
        if (swung) player.resetAttackStrengthTicker()
        // Use is not an attack swing: do not publish player.swing.
        player.inventoryMenu.sendAllDataToRemote()
        return PlayerUseResult(
            result.consumesAction(), swung, target,
            entityHit?.let { PlayerBlockActions.entitySnapshot(it.entity) },
        )
    }
}
