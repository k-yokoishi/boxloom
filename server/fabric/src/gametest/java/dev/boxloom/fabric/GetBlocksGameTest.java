package dev.boxloom.fabric;

import dev.boxloom.server.core.ApiException;
import dev.boxloom.server.core.GetBlockRequest;
import dev.boxloom.server.core.GetBlocksRequest;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.core.Direction;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class GetBlocksGameTest {
    @GameTest
    public void readsInclusiveCuboidInCoordinateOrderIncludingAirAndProperties(GameTestHelper test) {
        var first = test.absolutePos(new BlockPos(1, 2, 1));
        var last = first.offset(1, 1, 1);
        var level = test.getLevel();
        for (var pos : BlockPos.betweenClosed(first, last)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
        level.setBlock(first, Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X), 3);
        level.setBlock(last, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        var ops = new FabricMinecraftOperations(new AtomicReference<>(level.getServer()));
        var dimension = level.dimension().identifier().toString();
        var request = new GetBlocksRequest(dimension, first.getX(), last.getY(), last.getZ(),
            last.getX(), first.getY(), first.getZ());
        var future = ops.getBlocks(request).thenCompose(blocks -> {
            test.assertValueEqual(blocks.size(), 8, "Both corners included");
            for (int i = 0; i < blocks.size(); i++) {
                var block = blocks.get(i);
                test.assertValueEqual(block.getX(), first.getX() + i % 2, "X changes fastest");
                test.assertValueEqual(block.getY(), first.getY() + (i / 2) % 2, "Y changes next");
                test.assertValueEqual(block.getZ(), first.getZ() + i / 4, "Z changes last");
                test.assertValueEqual(block.getDimension(), dimension, "Resolved dimension");
            }
            test.assertValueEqual(blocks.getFirst().getBlock(), "minecraft:oak_log", "First block");
            test.assertValueEqual(blocks.getFirst().getProperties(), Map.of("axis", "x"), "Complete properties");
            test.assertValueEqual(blocks.getLast().getBlock(), "minecraft:gold_block", "Last block");
            for (int i = 1; i < 7; i++) {
                test.assertValueEqual(blocks.get(i).getBlock(), "minecraft:air", "Air is retained");
                test.assertTrue(blocks.get(i).getProperties().isEmpty(), "Air has no properties");
            }
            return ops.getBlocks(new GetBlocksRequest(dimension, first.getX(), first.getY(), first.getZ(),
                first.getX(), first.getY(), first.getZ())).thenCompose(single ->
                    ops.getBlock(new GetBlockRequest(dimension, first.getX(), first.getY(), first.getZ()))
                        .thenAccept(block -> test.assertValueEqual(single, java.util.List.of(block), "Single block matches getBlock")));
        });
        test.succeedWhen(() -> {
            test.assertTrue(future.isDone(), "Waiting for server-thread reads");
            future.join();
        });
    }

    @GameTest
    public void rejectsUnknownDimensionsAndOversizedRegions(GameTestHelper test) {
        var ops = new FabricMinecraftOperations(new AtomicReference<>(test.getLevel().getServer()));
        try {
            new GetBlocksRequest("minecraft:overworld", Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0);
            throw new AssertionError("Oversized regions must fail before reading");
        } catch (ApiException error) {
            test.assertValueEqual(error.getCode(), "INVALID_REQUEST", "Volume validation");
        }
        var future = ops.getBlocks(new GetBlocksRequest("boxloom:missing", 0, 64, 0, 0, 64, 0))
            .handle((blocks, error) -> {
                test.assertTrue(error instanceof ApiException, "Unknown dimension fails");
                test.assertValueEqual(((ApiException) error).getCode(), "DIMENSION_NOT_FOUND", "Dimension error");
                return null;
            });
        test.succeedWhen(() -> {
            test.assertTrue(future.isDone(), "Waiting for validation");
            future.join();
        });
    }
}
