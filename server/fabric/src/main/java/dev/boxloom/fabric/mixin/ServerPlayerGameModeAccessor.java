package dev.boxloom.fabric.mixin;

import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerPlayerGameMode.class)
public interface ServerPlayerGameModeAccessor {
    @Accessor("isDestroyingBlock")
    boolean boxloom$isDestroyingBlock();

    @Accessor("destroyPos")
    BlockPos boxloom$destroyPos();

    @Accessor("gameTicks")
    int boxloom$gameTicks();

    @Accessor("destroyProgressStart")
    int boxloom$destroyProgressStart();

    @Accessor("hasDelayedDestroy")
    boolean boxloom$hasDelayedDestroy();

    @Accessor("hasDelayedDestroy")
    void boxloom$setHasDelayedDestroy(boolean value);

    @Accessor("delayedDestroyPos")
    BlockPos boxloom$delayedDestroyPos();
}
