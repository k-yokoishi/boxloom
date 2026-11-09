package dev.boxloom.fabric.mixin;

import dev.boxloom.fabric.BoxloomMod;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Shadow
    public ServerPlayer player;

    // RETURN runs only after vanilla has dispatched the packet onto the server thread.
    // Unlike target-specific callbacks, this also receives attacks that hit nothing.
    @Inject(method = "handlePunch", at = @At("RETURN"))
    private void boxloom$onPunch(ServerboundPunchPacket packet, CallbackInfo ci) {
        BoxloomMod.publishPlayerSwing(player);
    }

    // This invocation is after vanilla's server-thread dispatch and loaded-client check.
    // An unpressed use key makes vanilla clients send RELEASE_USE_ITEM automatically.
    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerPlayer;releaseUsingItem()V"), cancellable = true)
    private void boxloom$keepApiItemUse(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (BoxloomMod.suppressApiItemRelease(player)) ci.cancel();
    }
}
