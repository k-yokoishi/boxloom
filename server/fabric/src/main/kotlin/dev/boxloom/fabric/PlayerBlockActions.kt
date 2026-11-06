package dev.boxloom.fabric

import dev.boxloom.fabric.mixin.ServerPlayerGameModeAccessor
import dev.boxloom.server.core.ApiException
import dev.boxloom.server.core.PlayerAttackResult
import dev.boxloom.server.core.TargetedBlock
import dev.boxloom.server.core.EntitySnapshot
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.component.AttackRange
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

/** Server-thread-only targeting and single-click actions. */
internal object PlayerBlockActions {
    fun targetedBlock(player: ServerPlayer): TargetedBlock? =
        findTargetedBlockHit(player)?.let { snapshot(player, it) }

    fun attack(player: ServerPlayer): PlayerAttackResult {
        requireCanAttack(player)
        PlayerMiningActions.stop(player)
        abortMining(player)
        val stack = player.mainHandItem
        val attackRange = stack.get(DataComponents.ATTACK_RANGE) ?: AttackRange.defaultFor(player)
        val hit = raycastFromPlayerView(player, attackRange.effectiveMaxRange(player).toDouble())
        val blockHit = (hit as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }
        val target = blockHit?.let { snapshot(player, it) }
        var broken = false
        var entity: EntitySnapshot? = null
        if (hit is EntityHitResult &&
            player.isWithinAttackRange(stack, hit.entity.boundingBox, 0.0) &&
            player.level().worldBorder.isWithinBounds(hit.entity.blockPosition())
        ) {
            // Player.attack also invokes Fabric's AttackEntityCallback and normal damage/PvP rules.
            player.attack(hit.entity)
            entity = entitySnapshot(hit.entity)
        } else if (blockHit != null) {
            val pos = blockHit.blockPos
            val before = player.level().getBlockState(pos)
            try {
                blockAction(player, blockHit, Action.START_DESTROY_BLOCK)
            } finally {
                abortMining(player)
            }
            broken = player.level().getBlockState(pos).block != before.block
        }
        return PlayerAttackResult(swing(player), target, broken, entity)
    }

    fun requireCanAttack(player: ServerPlayer) {
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
    }

    fun swing(player: ServerPlayer): Boolean {
        val swung = player.swing(InteractionHand.MAIN_HAND, player.mainHandItem.attackAnimation, true)
        player.resetAttackStrengthTicker()
        BoxloomMod.publishPlayerSwing(player)
        return swung
    }

    /** Returns a native block hit under the crosshair, or null for entities and misses. */
    fun findTargetedBlockHit(player: ServerPlayer): BlockHitResult? =
        (raycastFromPlayerView(player) as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }

    fun blockAction(player: ServerPlayer, hit: BlockHitResult, action: Action) {
        player.gameMode.handleBlockBreakAction(hit.blockPos, action, hit.direction, player.level().maxY, 0)
    }

    fun abortMining(player: ServerPlayer, pos: BlockPos? = null) {
        val mining = player.gameMode as ServerPlayerGameModeAccessor
        if (mining.`boxloom$isDestroyingBlock`() && (pos == null || mining.`boxloom$destroyPos`() == pos)) {
            player.gameMode.handleBlockBreakAction(
                mining.`boxloom$destroyPos`(), Action.ABORT_DESTROY_BLOCK,
                net.minecraft.core.Direction.DOWN, player.level().maxY, 0,
            )
        }
        if (mining.`boxloom$hasDelayedDestroy`() && (pos == null || mining.`boxloom$delayedDestroyPos`() == pos)) {
            mining.`boxloom$setHasDelayedDestroy`(false)
            player.level().destroyBlockProgress(player.id, mining.`boxloom$delayedDestroyPos`(), -1)
        }
    }

    /** Traces the player's view from their eyes, returning a block/entity hit or a miss. */
    private fun raycastFromPlayerView(player: ServerPlayer, entityReach: Double = player.blockInteractionRange()): HitResult {
        val range = maxOf(player.blockInteractionRange(), entityReach)
        val blockHit = player.pick(range, 1.0f, false)
        val eye = player.eyePosition
        val ray = player.getViewVector(1.0f).scale(range)
        // Do not attack a block through an entity that occupies the crosshair.
        val entityHit = ProjectileUtil.getEntityHitResult(
            player, eye, eye.add(ray), player.boundingBox.expandTowards(ray).inflate(1.0),
            { !it.isSpectator && it.isPickable }, eye.distanceToSqr(blockHit.location),
        )
        if (entityHit != null) return entityHit
        if (blockHit is BlockHitResult &&
            eye.distanceToSqr(blockHit.location) > player.blockInteractionRange() * player.blockInteractionRange()
        ) {
            return BlockHitResult.miss(blockHit.location, blockHit.direction, blockHit.blockPos)
        }
        return blockHit
    }

    fun snapshot(player: ServerPlayer, hit: BlockHitResult): TargetedBlock {
        val pos: BlockPos = hit.blockPos
        return TargetedBlock(
            player.level().dimension().identifier().toString(), pos.x, pos.y, pos.z,
            BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(pos).block).toString(),
            hit.direction.serializedName, player.eyePosition.distanceTo(hit.location),
        )
    }

    private fun entitySnapshot(entity: Entity): EntitySnapshot = EntitySnapshot(
        entity.uuid.toString(), EntityType.getKey(entity.type).toString(),
        entity.level().dimension().identifier().toString(), entity.x, entity.y, entity.z,
        entity.yRot, entity.xRot, entity.name.string, (entity as? LivingEntity)?.health,
    )
}
