package dev.servercrashguard.mixin;

import dev.servercrashguard.MetadataDecoder;
import java.util.List;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityTrackerUpdateS2CPacket.class)
public abstract class EntityTrackerPacketMixin {
    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private static void servercrashguard$boundedDecode(RegistryByteBuf buf,
            CallbackInfoReturnable<List<DataTracker.SerializedEntry<?>>> cir) {
        // Preserve repeated updates in wire order, but bound raw entry count before growing the list.
        cir.setReturnValue(MetadataDecoder.read(buf));
    }
}
