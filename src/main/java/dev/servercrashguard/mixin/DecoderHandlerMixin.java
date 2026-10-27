package dev.servercrashguard.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.servercrashguard.ServerCrashGuard;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.handler.DecoderHandler;
import net.minecraft.network.state.NetworkState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(DecoderHandler.class)
public abstract class DecoderHandlerMixin {
    @Shadow @Final private NetworkState<?> state;

    @WrapMethod(method = "decode")
    private void servercrashguard$decode(ChannelHandlerContext context, ByteBuf frame,
            List<Object> output, Operation<Void> original) {
        if (state.side() != NetworkSide.CLIENTBOUND || state.id() != NetworkPhase.PLAY) {
            original.call(context, frame, output);
            return;
        }
        int originalSize = output.size();
        try {
            original.call(context, frame, output);
        } catch (Exception exception) {
            // The vanilla splitter has already isolated this packet's frame.
            // Discard only this frame; never the next packet, and never a JVM Error.
            frame.skipBytes(frame.readableBytes());
            while (output.size() > originalSize) output.remove(output.size() - 1);
            ServerCrashGuard.drop("packet-decode");
        }
    }
}
