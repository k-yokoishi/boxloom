package dev.boxloom.fabric;

import dev.boxloom.server.core.ApiException;
import dev.boxloom.server.core.GiveItemRequest;
import dev.boxloom.server.core.GivenItemSlot;
import dev.boxloom.server.core.SelectSlotRequest;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import static dev.boxloom.fabric.PlayerBlockActionsGameTest.player;
import static dev.boxloom.fabric.PlayerBlockActionsGameTest.channel;

public class PlayerInventoryActionsGameTest {
    @GameTest
    public void mergesBlocksAndSplitsIntoLegalStacks(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(Items.STONE, 60));
        player.getInventory().setItem(1, new ItemStack(Items.DIRT, 12));
        var result = PlayerInventoryActions.INSTANCE.give(player, new GiveItemRequest("stone", 65));
        test.assertValueEqual(result.getUsername(), player.getName().getString(), "Player identity");
        test.assertValueEqual(result.getUuid(), player.getUUID().toString(), "Player UUID");
        test.assertValueEqual(result.getItem(), "minecraft:stone", "Canonical item ID");
        test.assertValueEqual(result.getCount(), 65, "Requested count, not the total inventory count");
        test.assertValueEqual(result.getSlot(), 0, "First hotbar destination");
        test.assertValueEqual(result.getSlots(), List.of(new GivenItemSlot(0, 4), new GivenItemSlot(2, 61)),
            "Reports additions to all stacks, not their resulting sizes");
        test.assertValueEqual(player.getInventory().getItem(0).getCount(), 64, "Fills the existing stack");
        test.assertTrue(player.getInventory().getItem(1).is(Items.DIRT), "Preserves other items");
        test.assertValueEqual(player.getInventory().getItem(1).getCount(), 12, "Preserves other counts");
        test.assertValueEqual(player.getInventory().getItem(2).getCount(), 61, "Remainder in a free slot");
        test.succeed();
    }

    @GameTest
    public void toolsAndSmallStacksRespectTheirOwnLimits(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        player.getInventory().clearContent();
        PlayerInventoryActions.INSTANCE.give(player, new GiveItemRequest("minecraft:diamond_sword", 2));
        test.assertTrue(player.getInventory().getItem(0).is(Items.DIAMOND_SWORD), "First tool");
        test.assertTrue(player.getInventory().getItem(1).is(Items.DIAMOND_SWORD), "Second tool");
        test.assertValueEqual(player.getInventory().getItem(0).getCount(), 1, "Tools do not stack");
        PlayerInventoryActions.INSTANCE.give(player, new GiveItemRequest("minecraft:snowball", 17));
        test.assertValueEqual(player.getInventory().getItem(2).getCount(), 16, "Snowballs stack to 16");
        test.assertValueEqual(player.getInventory().getItem(3).getCount(), 1, "Snowball remainder");
        test.succeed();
    }

    @GameTest
    public void survivalOverflowDropsOnlyTheRemainderForThePlayer(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        fill(player);
        player.getInventory().setItem(0, new ItemStack(Items.STONE, 60));
        var result = PlayerInventoryActions.INSTANCE.give(player, new GiveItemRequest("minecraft:stone", 10));
        test.assertValueEqual(result.getSlots(), List.of(new GivenItemSlot(0, 4)), "Dropped overflow has no slot");
        test.assertValueEqual(player.getInventory().getItem(0).getCount(), 64, "Uses the remaining space");
        var drops = player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(2),
            item -> item.getItem().is(Items.STONE) && !item.hasPickUpDelay());
        test.assertValueEqual(drops.size(), 1, "One real overflow stack");
        test.assertValueEqual(drops.getFirst().getItem().getCount(), 6, "Only the remainder drops");
        for (int slot = 1; slot < Inventory.INVENTORY_SIZE; slot++) {
            test.assertTrue(player.getInventory().getItem(slot).is(Items.DIRT), "Other slots stay intact");
        }
        test.succeed();
    }

    @GameTest
    public void creativeOverflowFollowsVanillaWithoutReplacingSlots(GameTestHelper test) {
        var player = player(test, GameType.CREATIVE);
        fill(player);
        var result = PlayerInventoryActions.INSTANCE.give(player, new GiveItemRequest("minecraft:stone", 3));
        test.assertValueEqual(result.getCount(), 3, "Vanilla give succeeds in creative even when full");
        test.assertTrue(result.getSlot() == null && result.getSlots().isEmpty(), "Discarded overflow has no slot");
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            test.assertTrue(player.getInventory().getItem(slot).is(Items.DIRT), "Does not replace a slot");
        }
        var drops = player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(2),
            item -> item.getItem().is(Items.STONE) && !item.hasPickUpDelay());
        test.assertTrue(drops.isEmpty(), "Creative overflow is discarded as in vanilla");
        test.succeed();
    }

    @GameTest
    public void invalidItemsAndCountsFailBeforeChangingInventory(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        fill(player);
        for (String item : new String[] {"minecraft:missing_item", "minecraft:air", "minecraft:water", "stone; say hi"}) {
            expectError(test, player, item, 1, "INVALID_ITEM");
        }
        expectError(test, player, "minecraft:stone", 0, "INVALID_COUNT");
        expectError(test, player, "minecraft:stone", -1, "INVALID_COUNT");
        expectError(test, player, "minecraft:stone", 6401, "INVALID_COUNT");
        expectError(test, player, "minecraft:diamond_sword", 101, "INVALID_COUNT");
        expectError(test, player, "minecraft:snowball", 1601, "INVALID_COUNT");
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            var stack = player.getInventory().getItem(slot);
            test.assertTrue(stack.is(Items.DIRT) && stack.getCount() == 64, "Invalid request leaves inventory intact");
        }
        test.succeed();
    }

    private static void expectError(GameTestHelper test, ServerPlayer player, String item, int count, String code) {
        try {
            PlayerInventoryActions.INSTANCE.give(player, new GiveItemRequest(item, count));
            test.fail("Expected " + code);
        } catch (ApiException error) {
            test.assertValueEqual(error.getStatus(), 400, "Client error");
            test.assertValueEqual(error.getCode(), code, "Error code");
        }
    }

    @GameTest
    public void reportsMainInventoryAndOffhandWithoutASelectableHotbarSlot(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        fill(player);
        player.getInventory().setItem(9, ItemStack.EMPTY);
        player.getInventory().setItem(Inventory.SLOT_OFFHAND, new ItemStack(Items.STONE, 60));
        var result = PlayerInventoryActions.INSTANCE.give(player, new GiveItemRequest("stone", 10));
        test.assertTrue(result.getSlot() == null, "Main inventory and offhand cannot be selected");
        test.assertValueEqual(result.getSlots(), List.of(new GivenItemSlot(9, 6), new GivenItemSlot(40, 4)),
            "Includes native offhand merging, ordered by inventory index");
        test.assertValueEqual(player.getInventory().getSelectedSlot(), 0, "Give does not change selection");
        test.succeed();
    }

    @GameTest
    public void survivalFullInventoryReturnsNoDestinations(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        fill(player);
        var result = PlayerInventoryActions.INSTANCE.give(player, new GiveItemRequest("stone", 3));
        test.assertTrue(result.getSlot() == null && result.getSlots().isEmpty(), "All items overflowed");
        test.succeed();
    }

    @GameTest
    public void selectsAndSynchronizesHotbarWithoutMovingItems(GameTestHelper test) {
        var player = player(test, GameType.SURVIVAL);
        player.getInventory().setItem(8, new ItemStack(Items.DIAMOND_SWORD));
        var channel = channel(player);
        channel.outboundMessages().clear();
        var result = PlayerInventoryActions.INSTANCE.selectSlot(player, new SelectSlotRequest(8));
        test.assertValueEqual(result.getUsername(), player.getName().getString(), "Player identity");
        test.assertValueEqual(result.getUuid(), player.getUUID().toString(), "Player UUID");
        test.assertValueEqual(result.getSlot(), 8, "Selected slot");
        test.assertValueEqual(player.getInventory().getSelectedSlot(), 8, "Server selection changed");
        test.assertTrue(player.getMainHandItem().is(Items.DIAMOND_SWORD), "Selected item is in the main hand");
        channel.runPendingTasks();
        test.assertTrue(channel.outboundMessages().stream().anyMatch(packet ->
            packet instanceof ClientboundSetHeldSlotPacket held && held.slot() == 8), "Client selection synchronized");
        for (int slot : new int[] {-1, 9, 40}) {
            try {
                PlayerInventoryActions.INSTANCE.selectSlot(player, new SelectSlotRequest(slot));
                test.fail("Expected invalid slot rejection");
            } catch (ApiException error) {
                test.assertValueEqual(error.getCode(), "INVALID_REQUEST", "Invalid slot error");
                test.assertValueEqual(player.getInventory().getSelectedSlot(), 8, "Invalid slot leaves selection intact");
            }
        }
        test.succeed();
    }

    private static void fill(ServerPlayer player) {
        player.getInventory().clearContent();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
        }
    }
}
