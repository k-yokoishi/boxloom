package dev.boxloom.fabric;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import dev.boxloom.server.core.ApiException;
import dev.boxloom.server.core.TargetedBlock;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

public class PlayerBlockActionsGameTest {
    private static final BlockPos TARGET = new BlockPos(1, 3, 4);
    private static final Set<BlockPos> DENIED = new HashSet<>();
    private static final Map<UUID, EmbeddedChannel> CHANNELS = new HashMap<>();
    private static final Set<UUID> DENIED_ENTITIES = new HashSet<>();

    static {
        AttackBlockCallback.EVENT.register((player, level, hand, pos, face) ->
            DENIED.contains(pos) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
            DENIED_ENTITIES.contains(entity.getUUID()) ? InteractionResult.FAIL : InteractionResult.PASS);
    }

    @GameTest
    public void targetsNearestBlockFaceAndDistance(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        test.setBlock(TARGET, Blocks.STONE);
        test.setBlock(1, 3, 3, Blocks.DIRT);
        TargetedBlock target = PlayerBlockActions.INSTANCE.targetedBlock(player);
        test.assertTrue(target != null, "Expected a block under the crosshair");
        test.assertValueEqual(target.getBlock(), "minecraft:dirt", "nearest block");
        test.assertValueEqual(target.getFace(), "north", "hit face");
        test.assertValueEqual(target.getZ(), test.absolutePos(new BlockPos(1, 3, 3)).getZ(), "absolute z");
        test.assertTrue(Math.abs(target.getDistance() - 1.5) < 0.001,
            "Distance to hit surface: " + target.getDistance() + ", eye=" + player.getEyePosition()
                + ", view=" + player.getViewVector(1.0f));
        test.succeed();
    }

    @GameTest
    public void targetsUsingHeadRotation(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        test.setBlock(1, 3, 3, Blocks.DIRT);
        // snapTo changes body yaw, but native picking reads the independent head yaw.
        player.setYHeadRot(30.0f);
        var turned = PlayerBlockActions.INSTANCE.targetedBlock(player);
        var original = test.absolutePos(new BlockPos(1, 3, 3));
        test.assertTrue(turned == null || turned.getX() != original.getX()
                || turned.getY() != original.getY() || turned.getZ() != original.getZ(),
            "Turning the head must move the crosshair away from the original block");
        player.setYHeadRot(0.0f);
        var target = PlayerBlockActions.INSTANCE.targetedBlock(player);
        test.assertTrue(target != null && Math.abs(target.getDistance() - 1.5) < 0.001,
            "Aligning head yaw restores the straight 1.5-block ray");
        test.succeed();
    }

    @GameTest
    public void doesNotTargetBeyondReach(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        var channel = CHANNELS.get(player.getUUID());
        channel.outboundMessages().clear();
        test.setBlock(1, 3, 7, Blocks.STONE);
        test.assertTrue(PlayerBlockActions.INSTANCE.targetedBlock(player) == null, "Far block is not reachable");
        var result = PlayerBlockActions.INSTANCE.attack(player);
        test.assertTrue(result.getTarget() == null && !result.getBroken(), "Out of reach is a miss");
        test.assertTrue(result.getSwung() && player.isSwinging(), "A miss still swings");
        channel.runPendingTasks();
        test.assertTrue(channel.outboundMessages().stream().anyMatch(packet -> packet instanceof ClientboundSwingAnimationPacket),
            "The player's own client must receive the arm swing packet");
        test.assertBlockPresent(Blocks.STONE, 1, 3, 7);
        test.succeed();
    }

    @GameTest
    public void survivalClickDoesNotLeaveMiningActive(GameTestHelper test) throws ReflectiveOperationException {
        var player = player(test, GameType.SURVIVAL);
        test.setBlock(TARGET, Blocks.STONE);
        var result = PlayerBlockActions.INSTANCE.attack(player);
        test.assertTrue(result.getTarget() != null && !result.getBroken(), "A stone block needs sustained mining");
        test.assertTrue(result.getSwung(), "Attack swings the arm");
        Field mining = ServerPlayerGameMode.class.getDeclaredField("isDestroyingBlock");
        mining.setAccessible(true);
        test.assertTrue(!mining.getBoolean(player.gameMode), "Single click must end mining");
        test.assertBlockPresent(Blocks.STONE, TARGET);
        test.succeed();
    }

    @GameTest
    public void creativeClickBreaksBlock(GameTestHelper test) {
        var player = player(test, GameType.CREATIVE);
        test.setBlock(TARGET, Blocks.STONE);
        var result = PlayerBlockActions.INSTANCE.attack(player);
        test.assertTrue(result.getBroken() && result.getTarget() != null, "Creative click breaks its target");
        test.assertBlockPresent(Blocks.AIR, TARGET);
        test.succeed();
    }

    @GameTest
    public void survivalClickBreaksInstantMineBlock(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        test.setBlock(TARGET, Blocks.TNT);
        test.assertTrue(PlayerBlockActions.INSTANCE.attack(player).getBroken(), "TNT can break in one click");
        test.assertBlockPresent(Blocks.AIR, TARGET);
        test.succeed();
    }

    @GameTest
    public void honorsFabricAttackCancellation(GameTestHelper test) {
        var player = player(test, GameType.CREATIVE);
        test.setBlock(TARGET, Blocks.STONE);
        var absolute = test.absolutePos(TARGET).immutable();
        DENIED.add(absolute);
        try {
            test.assertTrue(!PlayerBlockActions.INSTANCE.attack(player).getBroken(), "Cancelled attack must not break");
            test.assertBlockPresent(Blocks.STONE, TARGET);
        } finally {
            DENIED.remove(absolute);
        }
        test.succeed();
    }

    @GameTest
    public void spectatorCannotAttack(GameTestHelper test) {
        var player = player(test, GameType.SPECTATOR);
        test.setBlock(TARGET, Blocks.STONE);
        try {
            PlayerBlockActions.INSTANCE.attack(player);
            test.assertTrue(false, "Spectator attack must fail");
        } catch (ApiException error) {
            test.assertValueEqual(error.getCode(), "PLAYER_CANNOT_ATTACK", "failure code");
        }
        test.assertBlockPresent(Blocks.STONE, TARGET);
        test.succeed();
    }

    @GameTest
    public void doesNotAttackThroughAnEntity(GameTestHelper test) {
        var player = player(test, GameType.CREATIVE);
        test.setBlock(TARGET, Blocks.STONE);
        var entity = test.spawn(EntityTypes.ARMOR_STAND, new BlockPos(1, 2, 3));
        test.assertTrue(PlayerBlockActions.INSTANCE.targetedBlock(player) == null, "Entity occludes the block");
        var result = PlayerBlockActions.INSTANCE.attack(player);
        test.assertTrue(result.getEntity() != null && result.getEntity().getUuid().equals(entity.getUUID().toString()),
            "Attack the intervening entity instead of the block");
        test.assertBlockPresent(Blocks.STONE, TARGET);
        test.succeed();
    }

    @GameTest
    public void damagesEnemyOnceWithNativeDamageCooldown(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        var zombie = test.spawn(EntityTypes.ZOMBIE, new BlockPos(1, 2, 3));
        zombie.setNoAi(true);
        float before = zombie.getHealth();
        var result = PlayerBlockActions.INSTANCE.attack(player);
        test.assertTrue(result.getEntity() != null && result.getTarget() == null, "Return the attacked entity snapshot");
        test.assertTrue(zombie.getHealth() < before, "Enemy takes native attack damage");
        float after = zombie.getHealth();
        PlayerBlockActions.INSTANCE.attack(player);
        test.assertValueEqual(zombie.getHealth(), after, "Immediate repeat respects native damage cooldown");
        test.succeed();
    }

    @GameTest
    public void honorsEntityAttackCancellation(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        var zombie = test.spawn(EntityTypes.ZOMBIE, new BlockPos(1, 2, 3));
        zombie.setNoAi(true);
        float before = zombie.getHealth();
        DENIED_ENTITIES.add(zombie.getUUID());
        try {
            PlayerBlockActions.INSTANCE.attack(player);
            test.assertValueEqual(zombie.getHealth(), before, "Fabric can cancel enemy damage");
        } finally {
            DENIED_ENTITIES.remove(zombie.getUUID());
        }
        test.succeed();
    }

    @GameTest
    public void enemyOutsideAttackReachIsNotDamaged(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        var zombie = test.spawn(EntityTypes.ZOMBIE, new BlockPos(1, 2, 5));
        zombie.setNoAi(true);
        float before = zombie.getHealth();
        var result = PlayerBlockActions.INSTANCE.attack(player);
        test.assertTrue(result.getEntity() == null, "Block reach must not extend entity attack reach");
        test.assertValueEqual(zombie.getHealth(), before, "Out-of-reach enemy remains unharmed");
        test.succeed();
    }

    @GameTest
    public void wallOccludesEnemyDamage(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        test.setBlock(1, 3, 3, Blocks.STONE);
        var zombie = test.spawn(EntityTypes.ZOMBIE, TARGET.below());
        zombie.setNoAi(true);
        float before = zombie.getHealth();
        var result = PlayerBlockActions.INSTANCE.attack(player);
        test.assertTrue(result.getEntity() == null && result.getTarget() != null, "The wall is targeted first");
        test.assertValueEqual(zombie.getHealth(), before, "Cannot attack an enemy through a wall");
        test.succeed();
    }

    @GameTest(maxTicks = 80)
    public void miningContinuesToTheNextBlock(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        test.setBlock(TARGET, Blocks.DIRT);
        test.setBlock(1, 3, 5, Blocks.DIRT);
        var started = PlayerMiningActions.INSTANCE.start(player);
        test.assertTrue(started.getActive() && !started.getBroken(), "Mining starts asynchronously");
        // Repeated start must not keep resetting progress.
        test.runAfterDelay(5, () -> {
            test.assertBlockPresent(Blocks.DIRT, TARGET);
            test.assertTrue(PlayerMiningActions.INSTANCE.start(player).getActive(), "Start is idempotent for the same target");
        });
        test.runAfterDelay(50, () -> {
            test.assertBlockPresent(Blocks.AIR, TARGET);
            test.assertBlockPresent(Blocks.AIR, 1, 3, 5);
            PlayerMiningActions.INSTANCE.stop(player);
            test.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void miningCanStartWithoutATarget(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        var started = PlayerMiningActions.INSTANCE.start(player);
        test.assertTrue(started.getActive() && started.getTarget() == null, "Holding can begin with no block targeted");
        test.runAfterDelay(5, () -> test.setBlock(TARGET, Blocks.DIRT));
        test.runAfterDelay(30, () -> {
            test.assertBlockPresent(Blocks.AIR, TARGET);
            PlayerMiningActions.INSTANCE.stop(player);
            test.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void stoppingWhileWaitingPreventsLaterMining(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        PlayerMiningActions.INSTANCE.start(player);
        var stopped = PlayerMiningActions.INSTANCE.stop(player);
        test.assertTrue(!stopped.getActive() && stopped.getTarget() == null, "Stop releases holding with no target");
        test.setBlock(TARGET, Blocks.DIRT);
        test.runAfterDelay(30, () -> {
            test.assertBlockPresent(Blocks.DIRT, TARGET);
            test.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void instantMiningStaysActiveWithBreakDelay(GameTestHelper test) {
        var player = player(test, GameType.CREATIVE);
        test.setBlock(TARGET, Blocks.STONE);
        test.setBlock(1, 3, 5, Blocks.STONE);
        var started = PlayerMiningActions.INSTANCE.start(player);
        test.assertTrue(started.getActive() && started.getBroken(), "An instant break keeps holding enabled");
        test.runAfterDelay(3, () -> test.assertBlockPresent(Blocks.STONE, 1, 3, 5));
        test.runAfterDelay(12, () -> {
            test.assertBlockPresent(Blocks.AIR, 1, 3, 5);
            PlayerMiningActions.INSTANCE.stop(player);
            test.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void stoppingMiningCancelsProgressAndPackets(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        var channel = CHANNELS.get(player.getUUID());
        channel.outboundMessages().clear();
        test.setBlock(TARGET, Blocks.DIRT);
        PlayerMiningActions.INSTANCE.start(player);
        test.runAfterDelay(3, () -> {
            var result = PlayerMiningActions.INSTANCE.stop(player);
            test.assertTrue(!result.getActive() && result.getTarget() != null, "Stop cancels the current session");
            test.assertTrue(PlayerMiningActions.INSTANCE.stop(player).getTarget() == null, "Stop is idempotent");
        });
        test.runAfterDelay(30, () -> {
            test.assertBlockPresent(Blocks.DIRT, TARGET);
            channel.runPendingTasks();
            test.assertTrue(channel.outboundMessages().stream().anyMatch(packet ->
                packet instanceof ClientboundBlockDestructionPacket cracks && cracks.getProgress() >= 0), "Owner receives mining cracks");
            test.assertTrue(channel.outboundMessages().stream().anyMatch(packet ->
                packet instanceof ClientboundBlockDestructionPacket cracks && cracks.getProgress() == -1), "Owner receives crack reset");
            test.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void lookingAwayResetsProgressAndReturningResumesMining(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        test.setBlock(TARGET, Blocks.DIRT);
        PlayerMiningActions.INSTANCE.start(player);
        test.runAfterDelay(3, () -> player.setYHeadRot(90.0f));
        test.runAfterDelay(15, () -> {
            test.assertBlockPresent(Blocks.DIRT, TARGET);
            player.setYHeadRot(0.0f);
        });
        test.runAfterDelay(40, () -> {
            test.assertBlockPresent(Blocks.AIR, TARGET);
            PlayerMiningActions.INSTANCE.stop(player);
            test.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void changingToolRestartsMiningWithTheNewTool(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        test.setBlock(TARGET, Blocks.STONE);
        PlayerMiningActions.INSTANCE.start(player);
        var tool = new ItemStack(Items.IRON_PICKAXE);
        test.runAfterDelay(3, () -> player.setItemInHand(InteractionHand.MAIN_HAND, tool));
        test.runAfterDelay(30, () -> {
            test.assertBlockPresent(Blocks.AIR, TARGET);
            test.assertTrue(tool.getDamageValue() > 0, "The new tool is used for mining");
            PlayerMiningActions.INSTANCE.stop(player);
            test.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void changingTargetMinesTheNewBlock(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        test.setBlock(TARGET, Blocks.DIRT);
        test.setBlock(4, 3, 1, Blocks.DIRT);
        PlayerMiningActions.INSTANCE.start(player);
        test.runAfterDelay(3, () -> player.setYHeadRot(-90.0f));
        test.runAfterDelay(30, () -> {
            test.assertBlockPresent(Blocks.DIRT, TARGET);
            test.assertBlockPresent(Blocks.AIR, 4, 3, 1);
            PlayerMiningActions.INSTANCE.stop(player);
            test.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void singleAttackReleasesHeldMining(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        test.setBlock(TARGET, Blocks.DIRT);
        PlayerMiningActions.INSTANCE.start(player);
        test.runAfterDelay(3, () -> PlayerBlockActions.INSTANCE.attack(player));
        test.runAfterDelay(30, () -> {
            test.assertBlockPresent(Blocks.DIRT, TARGET);
            test.assertTrue(PlayerMiningActions.INSTANCE.stop(player).getTarget() == null, "Attack releases the hold");
            test.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void minesStoneWithToolAndNativeDurability(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        test.setBlock(TARGET, Blocks.STONE);
        test.setBlock(1, 3, 5, Blocks.STONE);
        var tool = new ItemStack(Items.IRON_PICKAXE);
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        PlayerMiningActions.INSTANCE.start(player);
        test.runAfterDelay(40, () -> {
            test.assertBlockPresent(Blocks.AIR, TARGET);
            test.assertBlockPresent(Blocks.AIR, 1, 3, 5);
            test.assertTrue(tool.getDamageValue() >= 2, "Native durability changes must allow the next block to be mined");
            PlayerMiningActions.INSTANCE.stop(player);
            test.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void stopCancelsDelayedNativeCompletion(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        test.setBlock(TARGET, Blocks.DIRT);
        PlayerMiningActions.INSTANCE.start(player);
        test.runAfterDelay(3, () -> {
            PlayerBlockActions.INSTANCE.blockAction(player, PlayerBlockActions.INSTANCE.blockHit(player),
                net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
            PlayerMiningActions.INSTANCE.stop(player);
        });
        test.runAfterDelay(30, () -> {
            test.assertBlockPresent(Blocks.DIRT, TARGET);
            test.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void disconnectCancelsMining(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        test.setBlock(TARGET, Blocks.DIRT);
        PlayerMiningActions.INSTANCE.start(player);
        test.runAfterDelay(3, player::disconnect);
        test.runAfterDelay(30, () -> {
            test.assertBlockPresent(Blocks.DIRT, TARGET);
            test.assertTrue(PlayerMiningActions.INSTANCE.stop(player).getTarget() == null, "Disconnect clears the session");
            test.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void miningHonorsProtectionAndUnbreakableBlocks(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        test.setBlock(TARGET, Blocks.DIRT);
        var absolute = test.absolutePos(TARGET).immutable();
        DENIED.add(absolute);
        try {
            var started = PlayerMiningActions.INSTANCE.start(player);
            test.assertTrue(started.getActive() && !started.getBroken(), "Holding stays enabled at a protected block");
            test.assertBlockPresent(Blocks.DIRT, TARGET);
        } finally {
            DENIED.remove(absolute);
        }
        test.setBlock(TARGET, Blocks.BEDROCK);
        test.runAfterDelay(5, () -> test.assertBlockPresent(Blocks.BEDROCK, TARGET));
        test.runAfterDelay(10, () -> test.setBlock(TARGET, Blocks.DIRT));
        test.runAfterDelay(35, () -> {
            test.assertBlockPresent(Blocks.AIR, TARGET);
            PlayerMiningActions.INSTANCE.stop(player);
            test.succeed();
        });
    }

    private static ServerPlayer player(GameTestHelper test, GameType mode) {
        var level = test.getLevel();
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "boxloom_test"), false);
        // GameTestHelper's mock overrides gameMode() to CREATIVE, even after setGameMode().
        var player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        CHANNELS.put(player.getUUID(), new EmbeddedChannel(connection));
        level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(mode);
        BlockPos position = test.absolutePos(new BlockPos(1, 2, 1));
        player.snapTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5, 0.0f, 0.0f);
        // Native picking uses head yaw, which otherwise retains the randomized spawn angle.
        player.setYHeadRot(0.0f);
        player.setOnGround(true);
        return player;
    }
}
