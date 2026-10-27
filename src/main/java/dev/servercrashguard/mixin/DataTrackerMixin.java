package dev.servercrashguard.mixin;

import dev.servercrashguard.PacketGuard;
import dev.servercrashguard.ServerCrashGuard;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.entity.data.DataTracked;
import net.minecraft.entity.data.DataTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DataTracker.class)
public abstract class DataTrackerMixin {
    @Shadow @Final private DataTracked trackedEntity;
    @Shadow @Final private DataTracker.Entry<?>[] entries;

    @Inject(method = "writeUpdatedEntries", at = @At("HEAD"), cancellable = true)
    private void servercrashguard$preflight(List<DataTracker.SerializedEntry<?>> values, CallbackInfo ci) {
        // Integrated server shares the JVM; do not change its server-side entity state.
        if (!(trackedEntity instanceof Entity entity) || !entity.getEntityWorld().isClient()) return;
        String reason = ServerCrashGuard.validator().metadataSchema(values, entries);
        if (reason != null) {
            ci.cancel();
            ServerCrashGuard.drop(reason);
        }
    }
}
