package dev.boxloom.fabric.mixin;

import dev.boxloom.fabric.BoxloomMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "stopUsingItem", at = @At("RETURN"))
    private void boxloom$onUseStopped(CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player) BoxloomMod.onNativeItemUseStopped(player);
    }
}
