package dev.servercrashguard.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.servercrashguard.PacketGuard;
import dev.servercrashguard.ServerCrashGuard;
import dev.servercrashguard.TokenBucket;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {
    @Unique private PacketGuard servercrashguard$guard;
    @Unique private TokenBucket servercrashguard$confirmations;

    @WrapMethod(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V")
    private void servercrashguard$inspect(ChannelHandlerContext context, Packet<?> packet, Operation<Void> original) {
        ClientConnection connection = (ClientConnection) (Object) this;
        if (connection.getSide() != NetworkSide.CLIENTBOUND) {
            original.call(context, packet);
            return;
        }
        if (!connection.isOpen()) return;
        if (servercrashguard$guard == null) {
            servercrashguard$guard = new PacketGuard(ServerCrashGuard.config());
            servercrashguard$confirmations = new TokenBucket(64, System::nanoTime);
        }
        Packet<?> filtered = servercrashguard$guard.filter(packet, (dropped, reason) -> {
            ServerCrashGuard.drop(dropped, reason);
            // Keep the last safe local position, but do not leave a malformed teleport unacknowledged.
            if (dropped instanceof PlayerPositionLookS2CPacket teleport
                    && reason.equals("player-position") && servercrashguard$confirmations.take(1)) {
                connection.send(new TeleportConfirmC2SPacket(teleport.teleportId()));
            }
        });
        if (filtered != null) original.call(context, filtered);
    }
}
