package dev.servercrashguard;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.network.RegistryByteBuf;

public final class MetadataDecoder {
    public static final int MAX_ENTRIES = 1024;
    private MetadataDecoder() {}

    public static List<DataTracker.SerializedEntry<?>> read(RegistryByteBuf buf) {
        List<DataTracker.SerializedEntry<?>> result = new ArrayList<>();
        try {
            while (true) {
                int id = buf.readUnsignedByte();
                if (id == 255) return result;
                if (result.size() >= MAX_ENTRIES) return discard(buf, "metadata-count");
                result.add(DataTracker.SerializedEntry.fromBuf(buf, id));
            }
        } catch (RuntimeException exception) {
            return discard(buf, "metadata-decode");
        }
    }

    private static List<DataTracker.SerializedEntry<?>> discard(RegistryByteBuf buf, String reason) {
        buf.skipBytes(buf.readableBytes());
        ServerCrashGuard.drop(reason);
        return List.of();
    }
}
