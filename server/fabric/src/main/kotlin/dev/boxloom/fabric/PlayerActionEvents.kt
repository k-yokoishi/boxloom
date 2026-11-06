package dev.boxloom.fabric

import dev.boxloom.server.core.BlockTarget
import dev.boxloom.server.core.BoxloomEventBroker
import dev.boxloom.server.core.PlayerActionContext
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult

internal class PlayerActionEvents(private val events: BoxloomEventBroker) {
    fun register() {
        AttackBlockCallback.EVENT.register { player, _, hand, pos, face ->
            if (player is ServerPlayer && !player.isSpectator) {
                events.publishBlockAttack(context(player, hand), target(player, pos, face))
            }
            InteractionResult.PASS
        }
        UseBlockCallback.EVENT.register { player, _, hand, hit ->
            if (player is ServerPlayer && !player.isSpectator) {
                events.publishBlockUse(context(player, hand), target(player, hit.blockPos, hit.direction))
            }
            InteractionResult.PASS
        }
        UseItemCallback.EVENT.register { player, _, hand ->
            if (player is ServerPlayer && !player.isSpectator && !player.getItemInHand(hand).isEmpty) {
                events.publishItemUse(context(player, hand))
            }
            InteractionResult.PASS
        }
    }

    fun swing(player: ServerPlayer) {
        if (!player.isSpectator) {
            events.publishPlayerSwing(context(player, InteractionHand.MAIN_HAND))
        }
    }

    private fun context(player: ServerPlayer, hand: InteractionHand): PlayerActionContext {
        val stack = player.getItemInHand(hand)
        return PlayerActionContext(
            player.name.string,
            player.uuid.toString(),
            player.level().dimension().identifier().toString(),
            player.x,
            player.y,
            player.z,
            if (hand == InteractionHand.MAIN_HAND) "main_hand" else "off_hand",
            if (stack.isEmpty) null else BuiltInRegistries.ITEM.getKey(stack.item).toString(),
        )
    }

    private fun target(player: ServerPlayer, pos: BlockPos, face: Direction): BlockTarget =
        BlockTarget(
            BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(pos).block).toString(),
            pos.x,
            pos.y,
            pos.z,
            face.serializedName,
        )
}
