package dev.servercrashguard.mixin;

import dev.servercrashguard.PacketGuard;
import dev.servercrashguard.ServerCrashGuard;
import java.util.Set;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPosition;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "setPosition", at = @At("HEAD"), cancellable = true)
    private static void servercrashguard$resolvedPosition(EntityPosition change, Set<PositionFlag> relatives,
            Entity entity, boolean interpolate, CallbackInfoReturnable<Boolean> cir) {
        // Values may be individually valid but unsafe after relative position/velocity/rotation arithmetic.
        var guard = ServerCrashGuard.validator();
        if (!guard.entityPosition(EntityPosition.apply(EntityPosition.fromEntity(entity), change, relatives))) {
            cir.setReturnValue(false);
            ServerCrashGuard.drop("resolved-relative-position");
        }
    }

    @Inject(method = "onExplosion", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/network/PacketApplyBatcher;)V",
            shift = At.Shift.AFTER), cancellable = true)
    private void servercrashguard$accumulatedKnockback(ExplosionS2CPacket packet, CallbackInfo ci) {
        var player = MinecraftClient.getInstance().player;
        if (player != null && packet.playerKnockback().isPresent()
                && !ServerCrashGuard.validator().velocity(player.getVelocity().add(packet.playerKnockback().get()))) {
            ci.cancel();
            ServerCrashGuard.drop("accumulated-explosion-velocity");
        }
    }
}
