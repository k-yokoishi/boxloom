package dev.boxloom.fabric;

import dev.boxloom.server.core.ApiException;
import dev.boxloom.server.core.GetBlockRequest;
import dev.boxloom.server.core.SetBlockRequest;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class BlockPropertiesGameTest {
    private static final BlockPos TARGET = new BlockPos(1, 1, 1);

    @GameTest
    public void readsWritesAndResetsCropAge(GameTestHelper test) {
        test.setBlock(TARGET.below(), Blocks.FARMLAND);
        var ops = operations(test);
        var request = request(test, "minecraft:wheat", Map.of("age", "3"));
        var future = ops.setBlock(request).thenCompose(written -> {
            test.assertTrue(written.getChanged(), "Planting wheat changes the world");
            test.assertValueEqual(written.getProperties(), Map.of("age", "3"), "Written age");
            return ops.getBlock(new GetBlockRequest(request.getDimension(), request.getX(), request.getY(), request.getZ()));
        }).thenCompose(read -> {
            test.assertValueEqual(read.getProperties(), Map.of("age", "3"), "Read age");
            return ops.setBlock(request);
        }).thenCompose(repeated -> {
            test.assertTrue(!repeated.getChanged(), "Identical state is a no-op");
            return ops.setBlock(request(test, "minecraft:wheat", Map.of("age", "5")));
        }).thenCompose(changed -> {
            test.assertTrue(changed.getChanged(), "Changing only age changes the world");
            test.assertValueEqual(test.getBlockState(TARGET).getValue(CropBlock.AGE), 5, "World age");
            return ops.setBlock(request(test, "minecraft:wheat", Map.of()));
        });
        test.succeedWhen(() -> {
            test.assertTrue(future.isDone(), "Waiting for server-thread operations");
            test.assertValueEqual(future.join().getProperties(), Map.of("age", "0"), "Empty properties reset defaults");
        });
    }

    @GameTest
    public void returnsAllPropertiesIncludingCanonicalBooleansAndEnums(GameTestHelper test) {
        var ops = operations(test);
        var future = ops.setBlock(request(test, "minecraft:furnace", Map.of("facing", "east", "lit", "true")))
            .thenCompose(result -> {
                test.assertValueEqual(result.getProperties(), Map.of("facing", "east", "lit", "true"), "Boolean and enum names");
                return ops.setBlock(request(test, "minecraft:oak_stairs", Map.of("facing", "west", "half", "top")));
            }).thenCompose(result -> {
                test.assertValueEqual(result.getProperties().get("facing"), "west", "Set enum");
                test.assertValueEqual(result.getProperties().get("waterlogged"), "false", "Unspecified default included");
                test.assertValueEqual(result.getProperties().get("shape"), "straight", "All properties included");
                return ops.setBlock(request(test, "minecraft:stone", Map.of()));
            });
        test.succeedWhen(() -> {
            test.assertTrue(future.isDone(), "Waiting for server-thread operations");
            test.assertTrue(future.join().getProperties().isEmpty(), "Property-less blocks return an empty map");
        });
    }

    @GameTest
    public void rejectsInvalidPropertiesWithoutChangingTheWorld(GameTestHelper test) {
        test.setBlock(TARGET, Blocks.GOLD_BLOCK);
        var ops = operations(test);
        var requests = List.of(
            request(test, "minecraft:wheat", Map.of("age", "8")),
            request(test, "minecraft:beetroots", Map.of("age", "4")),
            request(test, "minecraft:wheat", Map.of("age", "-1")),
            request(test, "minecraft:wheat", Map.of("age", "three")),
            request(test, "minecraft:wheat", Map.of("age", "3", "not_a_property", "true")),
            request(test, "minecraft:stone", Map.of("age", "3")),
            request(test, "minecraft:furnace", Map.of("facing", "up")),
            request(test, "minecraft:furnace", Map.of("lit", "TRUE"))
        );
        var futures = requests.stream().map(req -> ops.setBlock(req).handle((value, error) -> {
            test.assertTrue(error instanceof ApiException, "Invalid properties fail with ApiException");
            test.assertValueEqual(((ApiException) error).getStatus(), 400, "Status");
            test.assertValueEqual(((ApiException) error).getCode(), "INVALID_BLOCK_PROPERTY", "Code");
            test.assertBlockPresent(Blocks.GOLD_BLOCK, TARGET);
            return null;
        })).toArray(CompletableFuture[]::new);
        var complete = CompletableFuture.allOf(futures);
        test.succeedWhen(() -> {
            test.assertTrue(complete.isDone(), "Waiting for validation");
            complete.join();
        });
    }

    @GameTest
    public void failedPlacementReportsActualState(GameTestHelper test) {
        var pos = test.absolutePos(TARGET);
        var future = operations(test).setBlock(new SetBlockRequest(
            test.getLevel().dimension().identifier().toString(), pos.getX(), test.getLevel().getMaxY() + 1, pos.getZ(),
            "minecraft:oak_log", Map.of("axis", "x")));
        test.succeedWhen(() -> {
            test.assertTrue(future.isDone(), "Waiting for placement");
            var result = future.join();
            test.assertTrue(!result.getChanged(), "Outside build height is unchanged");
            test.assertTrue(result.getProperties().isEmpty(), "Returns actual air properties, not requested log properties");
            test.assertTrue(!result.getBlock().equals("minecraft:oak_log"), "Returns actual block ID");
        });
    }

    private static FabricMinecraftOperations operations(GameTestHelper test) {
        return new FabricMinecraftOperations(new AtomicReference<>(test.getLevel().getServer()));
    }

    private static SetBlockRequest request(GameTestHelper test, String block, Map<String, String> properties) {
        var pos = test.absolutePos(TARGET);
        return new SetBlockRequest(test.getLevel().dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ(), block, properties);
    }
}
