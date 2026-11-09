package dev.boxloom.fabric;

import dev.boxloom.server.core.ApiException;
import dev.boxloom.server.core.BoxloomEventBroker;
import dev.boxloom.server.core.BlockUseEvent;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeverBlock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import static dev.boxloom.fabric.PlayerBlockActionsGameTest.player;

public class PlayerUseActionsGameTest {
    private static final BlockPos TARGET = new BlockPos(1, 3, 4);
    private static final Set<BlockPos> DENIED_BLOCKS = new HashSet<>();
    private static final Set<UUID> DENIED_ITEMS = new HashSet<>();
    private static final Set<UUID> DENIED_ENTITIES = new HashSet<>();
    private static final BoxloomEventBroker EVENTS = new BoxloomEventBroker();

    static {
        UseBlockCallback.EVENT.register((p, level, hand, hit) ->
            DENIED_BLOCKS.contains(hit.getBlockPos()) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((p, level, hand) ->
            DENIED_ITEMS.contains(p.getUUID()) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((p, level, hand, entity, hit) ->
            DENIED_ENTITIES.contains(entity.getUUID()) ? InteractionResult.FAIL : InteractionResult.PASS);
        new PlayerActionEvents(EVENTS).register();
    }

    @GameTest
    public void boneMealGrowsWheatAndConsumesOneItem(GameTestHelper test) {
        var player = cropPlayer(test, GameType.SURVIVAL);
        var cursor = EVENTS.openCursor(null);
        var result = PlayerUseActions.INSTANCE.use(player);
        test.assertTrue(result.getUsed() && result.getSwung(), "Bone meal use succeeds and swings");
        test.assertTrue(result.getTarget() != null && result.getEntity() == null, "Returns the wheat target");
        test.assertValueEqual(result.getTarget().getProperties().get("age"), "0", "Target captures pre-use age");
        test.assertTrue(test.getBlockState(TARGET).getValue(CropBlock.AGE) > 0, "Wheat grows");
        test.assertValueEqual(player.getMainHandItem().getCount(), 2, "Consumes one bone meal");
        var events = EVENTS.awaitAfter(cursor, Duration.ZERO);
        test.assertTrue(events != null && events.size() == 1 && events.getFirst() instanceof BlockUseEvent,
            "Emits block.use, without an attack swing event");
        var event = (BlockUseEvent) events.getFirst();
        test.assertValueEqual(event.getContext().getItem(), "minecraft:bone_meal", "Held item snapshot");
        test.assertValueEqual(event.getContext().getHand(), "main_hand", "Used hand");
        test.succeed();
    }

    @GameTest
    public void creativeBoneMealIsNotConsumed(GameTestHelper test) {
        var player = cropPlayer(test, GameType.CREATIVE);
        test.assertTrue(PlayerUseActions.INSTANCE.use(player).getUsed(), "Creative bone meal works");
        test.assertTrue(test.getBlockState(TARGET).getValue(CropBlock.AGE) > 0, "Wheat grows");
        test.assertValueEqual(player.getMainHandItem().getCount(), 3, "Creative keeps bone meal");
        test.succeed();
    }

    @GameTest
    public void matureWheatDoesNotConsumeBoneMeal(GameTestHelper test) {
        var player = cropPlayer(test, GameType.SURVIVAL);
        test.setBlock(TARGET, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        test.assertTrue(!PlayerUseActions.INSTANCE.use(player).getUsed(), "Mature crop does not accept bone meal");
        test.assertValueEqual(player.getMainHandItem().getCount(), 3, "No bone meal consumed");
        test.succeed();
    }

    @GameTest
    public void cancelledBlockUseDoesNotFallBackToAirUse(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        test.setBlock(TARGET, Blocks.STONE);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SNOWBALL, 3));
        var pos = test.absolutePos(TARGET).immutable();
        DENIED_BLOCKS.add(pos);
        try {
            var result = PlayerUseActions.INSTANCE.use(player);
            test.assertTrue(!result.getUsed() && !result.getSwung(), "Cancellation stops the whole use");
            test.assertValueEqual(player.getMainHandItem().getCount(), 3, "Does not throw a snowball");
        } finally {
            DENIED_BLOCKS.remove(pos);
        }
        test.succeed();
    }

    @GameTest
    public void throwsItemInAirAndRespectsCooldown(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ENDER_PEARL, 3));
        var cursor = EVENTS.openCursor(null);
        var result = PlayerUseActions.INSTANCE.use(player);
        test.assertTrue(result.getUsed() && result.getTarget() == null && result.getEntity() == null, "In-air use");
        test.assertValueEqual(player.getMainHandItem().getCount(), 2, "One pearl consumed");
        test.assertTrue(EVENTS.awaitAfter(cursor, Duration.ZERO).stream().anyMatch(e -> e.getType().equals("item.use")),
            "In-air use emits item.use");
        test.assertTrue(!PlayerUseActions.INSTANCE.use(player).getUsed(), "Repeat respects cooldown");
        test.assertValueEqual(player.getMainHandItem().getCount(), 2, "Cooldown prevents consumption");
        test.succeed();
    }

    @GameTest
    public void cancelledAirUseDoesNotConsumeAnItem(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SNOWBALL, 3));
        DENIED_ITEMS.add(player.getUUID());
        try {
            test.assertTrue(!PlayerUseActions.INSTANCE.use(player).getUsed(), "UseItemCallback can cancel");
            test.assertValueEqual(player.getMainHandItem().getCount(), 3, "Keeps snowballs");
        } finally {
            DENIED_ITEMS.remove(player.getUUID());
        }
        test.succeed();
    }

    @GameTest
    public void emptyHandUsesABlockAndDoesNotUseOffhand(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        test.setBlock(TARGET.below(), Blocks.STONE);
        test.setBlock(TARGET, Blocks.LEVER);
        test.assertTrue(PlayerUseActions.INSTANCE.use(player).getUsed(), "Empty hand operates a lever");
        test.assertTrue(test.getBlockState(TARGET).getValue(LeverBlock.POWERED), "Lever is powered");
        test.setBlock(TARGET, Blocks.AIR);
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SNOWBALL, 3));
        test.assertTrue(!PlayerUseActions.INSTANCE.use(player).getUsed(), "No automatic offhand fallback");
        test.assertValueEqual(player.getOffhandItem().getCount(), 3, "Offhand is unchanged");
        test.succeed();
    }

    @GameTest
    public void feedsACowAndHonorsEntityCancellation(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        // Aim at the cow's torso rather than above its collision box.
        player.setXRot(20.0f);
        var cow = test.spawn(EntityTypes.COW, new BlockPos(1, 2, 3));
        cow.setNoAi(true);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT, 3));
        DENIED_ENTITIES.add(cow.getUUID());
        try {
            test.assertTrue(!PlayerUseActions.INSTANCE.use(player).getUsed(), "Entity use can be cancelled");
            test.assertValueEqual(player.getMainHandItem().getCount(), 3, "Cancellation keeps wheat");
        } finally {
            DENIED_ENTITIES.remove(cow.getUUID());
        }
        var result = PlayerUseActions.INSTANCE.use(player);
        test.assertTrue(result.getUsed() && result.getEntity() != null && result.getTarget() == null, "Feeds the cow");
        test.assertTrue(cow.isInLove(), "Cow enters love mode");
        test.assertValueEqual(player.getMainHandItem().getCount(), 2, "Consumes one wheat");
        test.succeed();
    }

    @GameTest
    public void doesNotUseBoneMealThroughAnEntity(GameTestHelper test) {
        var player = cropPlayer(test, GameType.SURVIVAL);
        test.spawn(EntityTypes.ARMOR_STAND, new BlockPos(1, 2, 3));
        var result = PlayerUseActions.INSTANCE.use(player);
        test.assertTrue(!result.getUsed() && result.getTarget() == null, "Entity occludes wheat");
        test.assertValueEqual(test.getBlockState(TARGET).getValue(CropBlock.AGE), 0, "Wheat stays young");
        test.assertValueEqual(player.getMainHandItem().getCount(), 3, "Keeps bone meal");
        test.succeed();
    }

    @GameTest
    public void spectatorAndDeadPlayersCannotUse(GameTestHelper test) {
        var player = cropPlayer(test, GameType.SPECTATOR);
        assertCannotUse(test, player);
        player.setGameMode(GameType.SURVIVAL);
        player.setHealth(0);
        assertCannotUse(test, player);
        test.assertValueEqual(player.getMainHandItem().getCount(), 3, "Rejected use keeps bone meal");
        test.succeed();
    }

    @GameTest(maxTicks = 30)
    public void useStopsHeldMining(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        PlayerMiningActions.INSTANCE.start(player);
        PlayerUseActions.INSTANCE.use(player);
        test.setBlock(TARGET, Blocks.DIRT);
        test.runAfterDelay(20, () -> {
            test.assertBlockPresent(Blocks.DIRT, TARGET);
            test.succeed();
        });
    }

    private static ServerPlayer cropPlayer(GameTestHelper test, GameType mode) {
        var player = player(test, mode);
        player.setXRot(12.0f);
        test.setBlock(TARGET.below(), Blocks.FARMLAND);
        test.setBlock(TARGET, Blocks.WHEAT);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BONE_MEAL, 3));
        return player;
    }

    private static void assertCannotUse(GameTestHelper test, ServerPlayer player) {
        try {
            PlayerUseActions.INSTANCE.use(player);
            test.assertTrue(false, "Expected PLAYER_CANNOT_USE");
        } catch (ApiException error) {
            test.assertValueEqual(error.getCode(), "PLAYER_CANNOT_USE", "failure code");
        }
    }
}
