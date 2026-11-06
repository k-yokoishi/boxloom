package dev.boxloom.fabric

import dev.boxloom.fabric.mixin.ServerPlayerGameModeAccessor
import dev.boxloom.server.core.PlayerMiningResult
import dev.boxloom.server.core.TargetedBlock
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.BlockHitResult
import java.util.UUID

/** All access, including lifecycle callbacks, happens on the Minecraft server thread. */
internal object PlayerMiningActions {
    private data class BlockProgress(
        val target: TargetedBlock,
        val hit: BlockHitResult,
        val state: BlockState,
        val tool: ItemStack,
        val slot: Int,
        val accepted: Boolean,
        var lastStage: Int = -1,
    )

    private data class Session(
        val player: ServerPlayer,
        val level: ServerLevel,
        val mode: GameType,
        var block: BlockProgress? = null,
        var delay: Int = 0,
    )

    private val sessions = mutableMapOf<UUID, Session>()

    fun start(player: ServerPlayer): PlayerMiningResult {
        PlayerBlockActions.requireCanAttack(player)
        val existing = sessions[player.uuid]
        if (existing != null && valid(existing)) {
            return PlayerMiningResult(true, existing.block?.target, false)
        }
        stop(player)
        PlayerBlockActions.abortMining(player)
        val session = Session(player, player.level(), player.gameMode())
        sessions[player.uuid] = session
        try {
            return beginBlock(session, PlayerBlockActions.blockHit(player))
        } catch (error: Exception) {
            stop(player)
            throw error
        }
    }

    private fun beginBlock(session: Session, hit: BlockHitResult?): PlayerMiningResult {
        if (hit == null) return PlayerMiningResult(true, null, false)
        val player = session.player
        val state = player.level().getBlockState(hit.blockPos)
        val target = PlayerBlockActions.snapshot(player, hit)
        if (!player.isCreative && state.getDestroyProgress(player, player.level(), hit.blockPos) <= 0) {
            session.block = progress(player, target, hit, state, false)
            return PlayerMiningResult(true, target, false)
        }
        PlayerBlockActions.blockAction(player, hit, Action.START_DESTROY_BLOCK)
        PlayerBlockActions.swing(player)
        val broken = player.level().getBlockState(hit.blockPos).block != state.block
        if (broken) {
            PlayerBlockActions.abortMining(player)
            session.delay = 5
            return PlayerMiningResult(true, target, true)
        }
        val mining = player.gameMode as ServerPlayerGameModeAccessor
        val accepted = mining.`boxloom$isDestroyingBlock`() && mining.`boxloom$destroyPos`() == hit.blockPos
        if (!accepted) {
            PlayerBlockActions.abortMining(player)
        }
        val block = progress(player, target, hit, state, accepted)
        session.block = block
        if (accepted) showProgress(session, block, 0)
        return PlayerMiningResult(true, target, false)
    }

    private fun progress(
        player: ServerPlayer, target: TargetedBlock, hit: BlockHitResult, state: BlockState, accepted: Boolean,
    ) =
        BlockProgress(target, hit, state, player.mainHandItem.copy(), player.inventory.selectedSlot, accepted)

    fun stop(player: ServerPlayer): PlayerMiningResult {
        val session = sessions.remove(player.uuid) ?: return PlayerMiningResult(false, null, false)
        val target = session.block?.target
        cancelBlock(session)
        return PlayerMiningResult(false, target, false)
    }

    fun tick(server: MinecraftServer) {
        // A copied view permits stop() to remove sessions during iteration.
        for (session in sessions.values.toList()) {
            val player = session.player
            if (server.playerList.getPlayer(player.uuid) !== player || !valid(session)) {
                stop(player)
                continue
            }
            if (player.isUsingItem || !player.mainHandItem.isItemEnabled(player.level().enabledFeatures()) ||
                player.cannotAttackWithItem(player.mainHandItem, 0) ||
                player.mainHandItem.has(DataComponents.PIERCING_WEAPON)
            ) {
                cancelBlock(session)
                continue
            }
            if (session.delay > 0) {
                session.delay--
                continue
            }
            val hit = PlayerBlockActions.blockHit(player)
            val block = session.block
            val mining = player.gameMode as ServerPlayerGameModeAccessor
            if (block?.accepted == true && mining.`boxloom$isDestroyingBlock`() &&
                mining.`boxloom$destroyPos`() != block.hit.blockPos
            ) {
                stop(player)
                continue
            }
            if (block == null || hit?.blockPos != block.hit.blockPos ||
                player.level().getBlockState(block.hit.blockPos) != block.state ||
                player.inventory.selectedSlot != block.slot || !ItemStack.matches(player.mainHandItem, block.tool)
            ) {
                cancelBlock(session)
                beginBlock(session, hit)
                continue
            }
            // A rejected/unbreakable target waits until the aim, block or tool changes.
            if (!block.accepted) continue
            if (!mining.`boxloom$isDestroyingBlock`() || mining.`boxloom$destroyPos`() != block.hit.blockPos) {
                stop(player)
                continue
            }
            val elapsed = mining.`boxloom$gameTicks`() - mining.`boxloom$destroyProgressStart`() + 1
            val progress = block.state.getDestroyProgress(player, player.level(), block.hit.blockPos) * elapsed
            if (!progress.isFinite() || progress <= 0) {
                cancelBlock(session)
                continue
            }
            if (progress >= 1.0f) {
                try {
                    // Wait for full native progress, rather than the server's lenient 70% packet threshold.
                    PlayerBlockActions.blockAction(player, block.hit, Action.STOP_DESTROY_BLOCK)
                } finally {
                    cancelBlock(session)
                    session.delay = 5
                }
            } else {
                showProgress(session, block, (progress * 10).toInt().coerceIn(0, 9))
                PlayerBlockActions.swing(player)
            }
        }
    }

    fun clear() {
        sessions.values.toList().forEach { stop(it.player) }
    }

    private fun valid(session: Session): Boolean {
        val player = session.player
        return player.isAlive && !player.hasDisconnected() && !player.isSpectator &&
            player.gameMode() == session.mode && player.level() === session.level
    }

    private fun cancelBlock(session: Session) {
        val block = session.block ?: return
        session.block = null
        PlayerBlockActions.abortMining(session.player, block.hit.blockPos)
        session.level.destroyBlockProgress(session.player.id, block.hit.blockPos, -1)
        session.player.connection.send(ClientboundBlockDestructionPacket(session.player.id, block.hit.blockPos, -1))
    }

    private fun showProgress(session: Session, block: BlockProgress, stage: Int) {
        if (stage == block.lastStage) return
        block.lastStage = stage
        // Vanilla sends progress only to observers; the controlled player also needs the cracks.
        session.player.connection.send(ClientboundBlockDestructionPacket(session.player.id, block.hit.blockPos, stage))
    }
}
