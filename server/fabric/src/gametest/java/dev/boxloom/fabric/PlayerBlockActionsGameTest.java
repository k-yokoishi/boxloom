package dev.boxloom.fabric;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import dev.boxloom.server.core.ApiException;
import dev.boxloom.server.core.TargetedBlock;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.EntityTypes;

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

    static {
        AttackBlockCallback.EVENT.register((player, level, hand, pos, face) ->
            DENIED.contains(pos) ? InteractionResult.FAIL : InteractionResult.PASS);
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
        test.spawn(EntityTypes.ARMOR_STAND, new BlockPos(1, 2, 3));
        test.assertTrue(PlayerBlockActions.INSTANCE.targetedBlock(player) == null, "Entity occludes the block");
        try {
            PlayerBlockActions.INSTANCE.attack(player);
            test.assertTrue(false, "Entity attacks are not supported by the block attack API");
        } catch (ApiException error) {
            test.assertValueEqual(error.getCode(), "UNSUPPORTED_ATTACK_TARGET", "failure code");
        }
        test.assertBlockPresent(Blocks.STONE, TARGET);
        test.succeed();
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
        return player;
    }
}
