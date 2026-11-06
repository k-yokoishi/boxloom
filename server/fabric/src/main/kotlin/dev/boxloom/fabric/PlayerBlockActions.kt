package dev.boxloom.fabric

import dev.boxloom.fabric.mixin.ServerPlayerGameModeAccessor
import dev.boxloom.server.core.ApiException
import dev.boxloom.server.core.PlayerAttackResult
import dev.boxloom.server.core.TargetedBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

/** Server-thread-only, single-click block actions. No client input or sustained mining. */
internal object PlayerBlockActions {
    fun targetedBlock(player: ServerPlayer): TargetedBlock? =
        (pick(player) as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }
            ?.let { snapshot(player, it) }

    fun attack(player: ServerPlayer): PlayerAttackResult {
        val stack = player.mainHandItem
        if (!player.isAlive || player.isSpectator || player.isUsingItem ||
            !stack.isItemEnabled(player.level().enabledFeatures()) || player.cannotAttackWithItem(stack, 0)
        ) {
            throw ApiException(409, "PLAYER_CANNOT_ATTACK", "The player cannot attack in their current state")
        }
        // Piercing weapons have a different native attack path, not a block left-click.
        if (stack.has(DataComponents.PIERCING_WEAPON)) {
            throw ApiException(409, "UNSUPPORTED_ATTACK_TARGET", "Piercing weapon attacks are not supported")
        }
        val hit = pick(player)
        if (hit is EntityHitResult) {
            throw ApiException(409, "UNSUPPORTED_ATTACK_TARGET", "Entity attacks are not supported; aim at a block or empty space")
        }
        val blockHit = (hit as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }
        val target = blockHit?.let { snapshot(player, it) }
        var broken = false
        if (blockHit != null) {
            val pos = blockHit.blockPos
            val before = player.level().getBlockState(pos)
            try {
                // This path preserves game-mode, tool, spawn-protection and Fabric event checks.
                player.gameMode.handleBlockBreakAction(
                    pos, Action.START_DESTROY_BLOCK, blockHit.direction, player.level().maxY, 0,
                )
            } finally {
                // A click must never leave the server mining after this request returns.
                val mining = player.gameMode as ServerPlayerGameModeAccessor
                if (mining.`boxloom$isDestroyingBlock`()) {
                    player.gameMode.handleBlockBreakAction(
                        mining.`boxloom$destroyPos`(), Action.ABORT_DESTROY_BLOCK,
                        blockHit.direction, player.level().maxY, 0,
                    )
                }
            }
            broken = player.level().getBlockState(pos).block != before.block
        }
        val swung = player.swing(InteractionHand.MAIN_HAND, stack.attackAnimation, true)
        player.resetAttackStrengthTicker()
        BoxloomMod.publishPlayerSwing(player)
        return PlayerAttackResult(swung, target, broken)
    }

    private fun pick(player: ServerPlayer): HitResult {
        val range = player.blockInteractionRange()
        val blockHit = player.pick(range, 1.0f, false)
        val eye = player.eyePosition
        val ray = player.getViewVector(1.0f).scale(range)
        // Do not attack a block through an entity that occupies the crosshair.
        return ProjectileUtil.getEntityHitResult(
            player, eye, eye.add(ray), player.boundingBox.expandTowards(ray).inflate(1.0),
            { !it.isSpectator && it.isPickable }, eye.distanceToSqr(blockHit.location),
        ) ?: blockHit
    }

    private fun snapshot(player: ServerPlayer, hit: BlockHitResult): TargetedBlock {
        val pos: BlockPos = hit.blockPos
        return TargetedBlock(
            player.level().dimension().identifier().toString(), pos.x, pos.y, pos.z,
            BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(pos).block).toString(),
            hit.direction.serializedName, player.eyePosition.distanceTo(hit.location),
        )
    }
}
