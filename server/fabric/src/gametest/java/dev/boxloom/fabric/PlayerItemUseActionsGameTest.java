package dev.boxloom.fabric;

import dev.boxloom.server.core.ApiException;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static dev.boxloom.fabric.PlayerBlockActionsGameTest.player;

public class PlayerItemUseActionsGameTest {
    private static final Set<UUID> DENIED = new HashSet<>();
    private static final List<ServerPlayer> PLAYERS = new ArrayList<>();
    static {
        UseItemCallback.EVENT.register((p, level, hand) ->
            DENIED.contains(p.getUUID()) ? InteractionResult.FAIL : InteractionResult.PASS);
        // Embedded test connections are not in ServerConnectionListener's socket list.
        // Real connections call doTick from their packet listener each server tick.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var player : PLAYERS) {
                if (player.isAlive() && !player.hasDisconnected()) player.doTick();
            }
        });
    }

    @GameTest
    public void singleUseIsStillReleasedByAnUnpressedClientKey(GameTestHelper test) {
        var player = hungryPlayer(test);
        test.assertTrue(PlayerUseActions.INSTANCE.use(player).getUsed() && player.isUsingItem(), "Single use starts eating");
        clientRelease(player);
        test.assertTrue(!player.isUsingItem(), "An ordinary client release cancels unowned use");
        test.assertValueEqual(player.getMainHandItem().getCount(), 2, "No instant food consumption");
        test.succeed();
    }

    @GameTest(maxTicks = 90)
    public void finishesOneAppleDespiteClientReleasePackets(GameTestHelper test) {
        var player = hungryPlayer(test);
        var duration = player.getMainHandItem().getUseDuration(player);
        var started = PlayerItemUseActions.INSTANCE.start(player);
        test.assertTrue(started.getActive(), "Eating is API-owned");
        test.assertValueEqual(started.getItem(), "minecraft:apple", "Started item");
        test.assertValueEqual(started.getRemainingTicks(), duration, "Native duration");
        // The actual packet handler and Mixin run, after a real loaded-client handshake.
        for (int tick : new int[] {1, 5, 15}) {
            test.runAfterDelay(tick, () -> {
                clientRelease(player);
                test.assertTrue(player.isUsingItem(), "Automatic client release must not cancel API eating");
            });
        }
        test.runAfterDelay(duration + 12, () -> {
            test.assertValueEqual(player.getMainHandItem().getCount(), 1, "Exactly one apple consumed");
            test.assertTrue(player.getFoodData().getFoodLevel() > 10, "Native hunger recovery");
            test.assertTrue(!player.isUsingItem() && !BoxloomMod.suppressApiItemRelease(player), "Natural completion ends ownership");
            test.succeed();
        });
    }

    @GameTest(maxTicks = 70)
    public void drinkingCompletesAndReturnsTheContainer(GameTestHelper test) {
        var player = hungryPlayer(test);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.MILK_BUCKET));
        var duration = player.getMainHandItem().getUseDuration(player);
        PlayerItemUseActions.INSTANCE.start(player);
        clientRelease(player);
        test.runAfterDelay(duration + 3, () -> {
            test.assertTrue(player.getMainHandItem().is(Items.BUCKET), "Native use returns an empty bucket");
            test.assertTrue(!BoxloomMod.suppressApiItemRelease(player), "Drinking completed");
            test.succeed();
        });
    }

    @GameTest(maxTicks = 30)
    public void repeatingStartPreservesProgressAndStopInterruptsFood(GameTestHelper test) {
        var player = hungryPlayer(test);
        int initial = PlayerItemUseActions.INSTANCE.start(player).getRemainingTicks();
        test.runAfterDelay(5, () -> {
            int remaining = player.getUseItemRemainingTicks();
            var repeated = PlayerItemUseActions.INSTANCE.start(player);
            test.assertTrue(remaining < initial, "Native ticks advanced eating");
            test.assertValueEqual(repeated.getRemainingTicks(), remaining, "Start does not reset duration");
            var stopped = PlayerItemUseActions.INSTANCE.stop(player);
            test.assertTrue(!stopped.getActive() && stopped.getItem() == null && stopped.getRemainingTicks() == 0, "Inactive result");
            test.assertTrue(!player.isUsingItem(), "Food use interrupted");
            test.assertValueEqual(player.getMainHandItem().getCount(), 2, "Interrupted food not consumed");
            test.assertTrue(!PlayerItemUseActions.INSTANCE.stop(player).getActive(), "Stop is idempotent");
            test.succeed();
        });
    }

    @GameTest(maxTicks = 40)
    public void stopReleasesAChargedBowButClientReleaseDoesNot(GameTestHelper test) {
        var player = bowPlayer(test);
        PlayerItemUseActions.INSTANCE.start(player);
        test.runAfterDelay(20, () -> {
            clientRelease(player);
            test.assertTrue(player.isUsingItem(), "Client release keeps charging");
            test.assertTrue(arrows(player) == 0, "Client release did not fire");
            PlayerItemUseActions.INSTANCE.stop(player);
            test.assertTrue(!player.isUsingItem() && arrows(player) == 1, "API stop fires exactly one arrow");
            PlayerItemUseActions.INSTANCE.stop(player);
            test.assertValueEqual(arrows(player), 1, "Repeated stop does not fire again");
            test.succeed();
        });
    }

    @GameTest
    public void fullHungerCooldownAndFabricCancellationDoNotOwnUse(GameTestHelper test) {
        var player = hungryPlayer(test);
        player.getFoodData().setFoodLevel(20);
        test.assertTrue(!PlayerItemUseActions.INSTANCE.start(player).getActive(), "Full hunger refuses normal food");
        player.getFoodData().setFoodLevel(10);
        player.getCooldowns().addCooldown(player.getMainHandItem(), 20);
        test.assertTrue(!PlayerItemUseActions.INSTANCE.start(player).getActive(), "Native cooldown applies");
        player.getCooldowns().removeCooldown(player.getCooldowns().getCooldownGroup(player.getMainHandItem()));
        DENIED.add(player.getUUID());
        try {
            test.assertTrue(!PlayerItemUseActions.INSTANCE.start(player).getActive(), "Mod can cancel start");
            test.assertTrue(!BoxloomMod.suppressApiItemRelease(player), "Cancelled start never suppresses packets");
            test.assertValueEqual(player.getMainHandItem().getCount(), 2, "No consumption");
        } finally {
            DENIED.remove(player.getUUID());
        }
        test.succeed();
    }

    @GameTest
    public void rejectsInstantItemsInvalidPlayersAndNativeBusyUse(GameTestHelper test) {
        var player = hungryPlayer(test);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ENDER_PEARL, 3));
        assertError(test, player, "ITEM_NOT_CONTINUOUSLY_USABLE");
        test.assertValueEqual(player.getMainHandItem().getCount(), 3, "Rejected start does not throw a pearl");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.APPLE, 2));
        player.gameMode.useItem(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
        assertError(test, player, "PLAYER_CANNOT_USE");
        test.assertTrue(!PlayerItemUseActions.INSTANCE.stop(player).getActive() && player.isUsingItem(), "API stop leaves native use alone");
        clientRelease(player);
        test.assertTrue(!player.isUsingItem(), "Native client release is unchanged");
        player.setGameMode(GameType.SPECTATOR);
        assertError(test, player, "PLAYER_CANNOT_USE");
        player.setGameMode(GameType.SURVIVAL);
        player.setHealth(0);
        assertError(test, player, "PLAYER_CANNOT_USE");
        test.succeed();
    }

    @GameTest(maxTicks = 40)
    public void slotChangeAndStackReplacementCancelWithoutFiring(GameTestHelper test) {
        var player = bowPlayer(test);
        PlayerItemUseActions.INSTANCE.start(player);
        test.runAfterDelay(20, () -> {
            player.getInventory().setSelectedSlot(1);
            PlayerItemUseActions.INSTANCE.tick(player.level().getServer());
            test.assertTrue(!player.isUsingItem() && arrows(player) == 0, "Switching slot cancels without firing");
            player.getInventory().setSelectedSlot(0);
            PlayerItemUseActions.INSTANCE.start(player);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
            clientRelease(player);
            test.assertTrue(!player.isUsingItem() && arrows(player) == 0, "Replacing a stack clears ownership without firing");
            test.succeed();
        });
    }

    @GameTest
    public void staleSessionDoesNotStopANewNativeUse(GameTestHelper test) {
        var player = bowPlayer(test);
        PlayerItemUseActions.INSTANCE.start(player);
        player.stopUsingItem();
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.APPLE, 2));
        player.gameMode.useItem(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
        PlayerItemUseActions.INSTANCE.stop(player);
        test.assertTrue(player.isUsingItem(), "Stopping an old API session preserves a new native use");
        clientRelease(player);
        test.assertTrue(!player.isUsingItem(), "No release suppression leaks from the old session");
        test.succeed();
    }

    @GameTest
    public void naturalCompletionDoesNotClaimTheNextNativeUseOfTheSameStack(GameTestHelper test) {
        var player = hungryPlayer(test);
        int duration = player.getMainHandItem().getUseDuration(player);
        PlayerItemUseActions.INSTANCE.start(player);
        for (int tick = 0; tick < duration; tick++) player.doTick();
        test.assertTrue(!player.isUsingItem(), "The API-started apple finished");
        test.assertValueEqual(player.getMainHandItem().getCount(), 1, "One apple remains in the same stack");
        // Start native use before the next controller tick gets a chance to clear stale sessions.
        player.gameMode.useItem(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
        test.assertTrue(player.isUsingItem(), "The client can start the remaining apple");
        test.assertTrue(!BoxloomMod.suppressApiItemRelease(player), "The new native use is not API-owned");
        PlayerItemUseActions.INSTANCE.stop(player);
        test.assertTrue(player.isUsingItem(), "API stop cannot interrupt this new native use");
        clientRelease(player);
        test.assertTrue(!player.isUsingItem(), "Client release still works");
        test.succeed();
    }

    @GameTest
    public void deathModeChangesAndDisconnectClearOwnership(GameTestHelper test) {
        for (int condition = 0; condition < 3; condition++) {
            var player = bowPlayer(test);
            PlayerItemUseActions.INSTANCE.start(player);
            if (condition == 0) player.setHealth(0);
            else if (condition == 1) player.setGameMode(GameType.CREATIVE);
            else player.disconnect();
            PlayerItemUseActions.INSTANCE.tick(test.getLevel().getServer());
            test.assertTrue(!player.isUsingItem() && !BoxloomMod.suppressApiItemRelease(player), "Lifecycle cancels ownership");
            test.assertValueEqual(arrows(player), 0, "Lifecycle cancellation never fires a bow");
        }
        test.succeed();
    }

    @GameTest
    public void otherApiActionsCancelHoldWithoutReleaseEffects(GameTestHelper test) {
        var player = bowPlayer(test);
        PlayerItemUseActions.INSTANCE.start(player);
        PlayerBlockActions.INSTANCE.attack(player);
        test.assertTrue(!player.isUsingItem() && !BoxloomMod.suppressApiItemRelease(player), "Attack cancels API use");
        PlayerItemUseActions.INSTANCE.start(player);
        test.assertTrue(PlayerMiningActions.INSTANCE.start(player).getActive(), "Mining can take over");
        test.assertTrue(!player.isUsingItem(), "Mining cancels API use");
        PlayerItemUseActions.INSTANCE.start(player);
        PlayerMiningActions.INSTANCE.tick(test.getLevel().getServer());
        PlayerUseActions.INSTANCE.use(player);
        test.assertTrue(!BoxloomMod.suppressApiItemRelease(player), "Single use ends API ownership");
        player.stopUsingItem();
        test.assertValueEqual(arrows(player), 0, "Takeover never releases an arrow");
        test.succeed();
    }

    private static ServerPlayer hungryPlayer(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        test.assertTrue(player.connection.hasClientLoaded(), "Client handshake completed");
        player.getFoodData().setFoodLevel(10);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.APPLE, 2));
        PLAYERS.add(player);
        return player;
    }

    private static ServerPlayer bowPlayer(GameTestHelper test) {
        var player = hungryPlayer(test);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        player.getInventory().setItem(1, new ItemStack(Items.ARROW, 3));
        return player;
    }

    private static int arrows(ServerPlayer player) {
        return player.level().getEntitiesOfClass(AbstractArrow.class, player.getBoundingBox().inflate(8)).size();
    }

    private static void clientRelease(ServerPlayer player) {
        player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
    }

    private static void assertError(GameTestHelper test, ServerPlayer player, String expected) {
        try {
            PlayerItemUseActions.INSTANCE.start(player);
            test.assertTrue(false, "Expected start to fail");
        } catch (ApiException error) {
            test.assertValueEqual(error.getCode(), expected, "Start error code");
        }
    }
}
