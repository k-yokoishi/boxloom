package dev.boxloom.fabric;

import dev.boxloom.server.core.ApiException;
import dev.boxloom.server.core.FillMode;
import dev.boxloom.server.core.FillRequest;
import dev.boxloom.server.core.FillResult;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.AABB;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class FillGameTest {
    private static final BlockPos START = new BlockPos(1, 1, 1);
    private static final BlockPos END = new BlockPos(3, 3, 3);
    private static final BlockPos CENTER = new BlockPos(2, 2, 2);

    @GameTest
    public void replaceRemovesBlocksWithoutDropsAndReportsNoOp(GameTestHelper test) {
        test.setBlock(START, Blocks.DIRT);
        var future = fill(test, START, START, "minecraft:air", FillMode.REPLACE)
            .thenCompose(result -> {
                test.assertValueEqual(result.getChangedBlocks(), 1, "One block replaced");
                test.assertBlockPresent(Blocks.AIR, START);
                test.assertTrue(dirtDrops(test).isEmpty(), "Replacement does not create drops");
                return fill(test, START, START, "minecraft:air", FillMode.REPLACE);
            }).thenAccept(result -> test.assertValueEqual(result.getChangedBlocks(), 0, "Repeated fill is a no-op"));
        finish(test, future);
    }

    @GameTest
    public void destroyDropsOnlyMatchingBlocksAndCountsAirPlacement(GameTestHelper test) {
        test.setBlock(START, Blocks.DIRT);
        test.setBlock(START.east(), Blocks.GOLD_BLOCK);
        var future = fill(test, START, START.east(), "minecraft:air", FillMode.DESTROY,
                         "minecraft:dirt", Map.of(), Map.of())
            .thenAccept(result -> {
                test.assertValueEqual(result.getChangedBlocks(), 1, "Destroyed block counts even when air placement is a no-op");
                test.assertBlockPresent(Blocks.AIR, START);
                test.assertBlockPresent(Blocks.GOLD_BLOCK, START.east());
                test.assertValueEqual(dirtDrops(test).stream().mapToInt(item -> item.getItem().getCount()).sum(),
                                      1, "Native dirt loot drops once");
            });
        finish(test, future);
    }

    @GameTest
    public void destroyRecreatesIdenticalBlocksAndCountsEachPositionOnce(GameTestHelper test) {
        test.setBlock(START, Blocks.DIRT);
        var future = fill(test, START, START, "minecraft:dirt", FillMode.DESTROY)
            .thenCompose(result -> {
                test.assertValueEqual(result.getChangedBlocks(), 1, "Destruction and placement count as one position");
                test.assertBlockPresent(Blocks.DIRT, START);
                test.assertValueEqual(dirtDrops(test).size(), 1, "Identical destination still drops the old block");
                return fill(test, START.above(), START.above(), "minecraft:air", FillMode.DESTROY);
            }).thenAccept(result -> test.assertValueEqual(result.getChangedBlocks(), 0, "Destroying air is a no-op"));
        finish(test, future);
    }

    @GameTest
    public void keepFillsOnlyAirIncludingNeitherSolidBlocksNorWater(GameTestHelper test) {
        test.setBlock(START, Blocks.DIRT);
        test.setBlock(START.east(2), Blocks.WATER);
        var future = fill(test, START, START.east(2), "minecraft:gold_block", FillMode.KEEP)
            .thenAccept(result -> {
                test.assertValueEqual(result.getChangedBlocks(), 1, "Only the air block changes");
                test.assertBlockPresent(Blocks.DIRT, START);
                test.assertBlockPresent(Blocks.GOLD_BLOCK, START.east());
                test.assertBlockPresent(Blocks.WATER, START.east(2));
            });
        finish(test, future);
    }

    @GameTest
    public void outlinePreservesInteriorAndHollowClearsItWithReversedCorners(GameTestHelper test) {
        test.setBlock(CENTER, Blocks.DIRT);
        var future = fill(test, END, START, "minecraft:gold_block", FillMode.OUTLINE)
            .thenCompose(result -> {
                test.assertValueEqual(result.getChangedBlocks(), 26, "All six faces form the shell");
                test.assertBlockPresent(Blocks.DIRT, CENTER);
                test.assertBlockPresent(Blocks.GOLD_BLOCK, START);
                test.assertBlockPresent(Blocks.GOLD_BLOCK, END);
                return fill(test, END, START, "minecraft:gold_block", FillMode.HOLLOW);
            }).thenAccept(result -> {
                test.assertValueEqual(result.getChangedBlocks(), 1, "Only the interior changes this time");
                test.assertBlockPresent(Blocks.AIR, CENTER);
                test.assertTrue(dirtDrops(test).isEmpty(), "Hollow clears the interior without drops");
            });
        finish(test, future);
    }

    @GameTest
    public void thinHollowRegionsHaveNoInterior(GameTestHelper test) {
        var future = fill(test, START, new BlockPos(3, 1, 3), "minecraft:gold_block", FillMode.HOLLOW)
            .thenAccept(result -> {
                test.assertValueEqual(result.getChangedBlocks(), 9, "Every position belongs to a face");
                test.assertBlockPresent(Blocks.GOLD_BLOCK, new BlockPos(2, 1, 2));
            });
        finish(test, future);
    }

    @GameTest
    public void replacePropertiesMatchOnlySpecifiedStatesAndDestinationPropertiesApply(GameTestHelper test) {
        test.setBlock(START, Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(StairBlock.FACING, Direction.WEST).setValue(StairBlock.HALF, Half.TOP));
        test.setBlock(START.east(), Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(StairBlock.FACING, Direction.EAST).setValue(StairBlock.HALF, Half.TOP));
        var future = fill(test, START, START.east(), "minecraft:oak_log", FillMode.REPLACE,
                         "minecraft:oak_stairs", Map.of("axis", "x"), Map.of("facing", "west"))
            .thenAccept(result -> {
                test.assertValueEqual(result.getChangedBlocks(), 1, "Unspecified half does not constrain the filter");
                test.assertBlockPresent(Blocks.OAK_LOG, START);
                test.assertValueEqual(test.getBlockState(START).getValue(RotatedPillarBlock.AXIS), Direction.Axis.X,
                                      "Requested destination property is used");
                test.assertBlockPresent(Blocks.OAK_STAIRS, START.east());
            });
        finish(test, future);
    }

    @GameTest
    public void hollowFiltersInteriorAsWellAsShell(GameTestHelper test) {
        test.setBlock(START, Blocks.DIRT);
        test.setBlock(CENTER, Blocks.GOLD_BLOCK);
        test.setBlock(END, Blocks.GOLD_BLOCK);
        var future = fill(test, START, END, "minecraft:glass", FillMode.HOLLOW,
                         "minecraft:dirt", Map.of(), Map.of())
            .thenAccept(result -> {
                test.assertValueEqual(result.getChangedBlocks(), 1, "Only a matching boundary block changes");
                test.assertBlockPresent(Blocks.GLASS, START);
                test.assertBlockPresent(Blocks.GOLD_BLOCK, CENTER);
                test.assertBlockPresent(Blocks.GOLD_BLOCK, END);
            });
        finish(test, future);
    }

    @GameTest
    public void invalidStatesAndFiltersFailBeforeDestruction(GameTestHelper test) {
        test.setBlock(START, Blocks.DIRT);
        var futures = List.of(
            fill(test, START, START, "minecraft:oak_log", FillMode.DESTROY, null, Map.of("axis", "bad"), Map.of()),
            fill(test, START, START, "minecraft:air", FillMode.DESTROY, "minecraft:oak_log", Map.of(), Map.of("axis", "bad")),
            fill(test, START, START, "minecraft:air", FillMode.DESTROY, "minecraft:missing_block", Map.of(), Map.of())
        ).stream().map(future -> future.handle((result, error) -> {
            test.assertTrue(error instanceof ApiException, "Invalid state or filter is an API error");
            test.assertValueEqual(((ApiException) error).getStatus(), 400, "Client error");
            test.assertBlockPresent(Blocks.DIRT, START);
            test.assertTrue(dirtDrops(test).isEmpty(), "Validation happens before destruction");
            return null;
        })).toArray(CompletableFuture[]::new);
        finish(test, CompletableFuture.allOf(futures));
    }

    @GameTest
    public void strictSuppressesPlacementSideEffects(GameTestHelper test) {
        var strict = new BlockPos(1, 4, 1);
        var normal = new BlockPos(5, 4, 5);
        var future = fill(test, strict, strict, "minecraft:sand", FillMode.STRICT)
            .thenCompose(result -> fill(test, normal, normal, "minecraft:sand", FillMode.REPLACE));
        test.runAfterDelay(10, () -> {
            test.assertTrue(future.isDone(), "Waiting for placement");
            future.join();
            test.assertBlockPresent(Blocks.SAND, strict);
            test.assertBlockPresent(Blocks.AIR, normal);
            test.succeed();
        });
    }

    private static CompletableFuture<FillResult> fill(GameTestHelper test, BlockPos start, BlockPos end,
                                                      String block, FillMode mode) {
        return fill(test, start, end, block, mode, null, Map.of(), Map.of());
    }

    private static CompletableFuture<FillResult> fill(GameTestHelper test, BlockPos start, BlockPos end,
                                                      String block, FillMode mode, String replace,
                                                      Map<String, String> properties, Map<String, String> replaceProperties) {
        var a = test.absolutePos(start);
        var b = test.absolutePos(end);
        var operations = new FabricMinecraftOperations(new AtomicReference<>(test.getLevel().getServer()));
        return operations.fill(new FillRequest(test.getLevel().dimension().identifier().toString(),
            a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ(), block, mode, replace, properties, replaceProperties));
    }

    private static List<ItemEntity> dirtDrops(GameTestHelper test) {
        return test.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(test.absolutePos(START)).inflate(2),
                                                item -> item.getItem().is(Items.DIRT));
    }

    private static void finish(GameTestHelper test, CompletableFuture<?> future) {
        test.succeedWhen(() -> {
            test.assertTrue(future.isDone(), "Waiting for server-thread fill");
            future.join();
        });
    }
}
