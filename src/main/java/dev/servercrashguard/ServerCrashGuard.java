package dev.servercrashguard;

import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ServerCrashGuard implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("ServerCrashGuard");
    private static volatile GuardConfig config = new GuardConfig();
    private static volatile PacketGuard validator = new PacketGuard(config);
    private static final AtomicLong LAST_LOG = new AtomicLong();
    private static final GuardNotifications NOTICES = new GuardNotifications();

    @Override
    public void onInitializeClient() {
        config = GuardConfig.load(FabricLoader.getInstance().getConfigDir().resolve("server-crash-guard.json"));
        validator = new PacketGuard(config);
        LOGGER.info("Server Crash Guard 1.1.0 active: local filtering, no automatic disconnect");
    }

    public static GuardConfig config() { return config; }
    /** Only use stateless validation methods on this shared object. */
    public static PacketGuard validator() { return validator; }

    public static void drop(Packet<?> packet, String reason) {
        NOTICES.add(reason);
        long now = System.nanoTime();
        long previous = LAST_LOG.get();
        if ((previous != 0 && now - previous < 2_000_000_000L) || !LAST_LOG.compareAndSet(previous, now)) return;
        if (packet instanceof EntitySpawnS2CPacket spawn) {
            var velocity = spawn.getVelocity();
            LOGGER.warn("Dropped unsafe entity spawn: id={} pos=({}, {}, {}) velocity=({}, {}, {})",
                    spawn.getEntityId(), spawn.getX(), spawn.getY(), spawn.getZ(),
                    velocity.x, velocity.y, velocity.z);
        } else {
            LOGGER.warn("Dropped unsafe server data locally: {}", reason);
        }
    }

    public static void drop(String reason) { drop(null, reason); }

    /** Invoked by the client tick hook: local ChatHud only, never a server chat message. */
    public static void flushChat() {
        var client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;
        var notice = NOTICES.poll(System.nanoTime());
        if (notice == null) return;
        client.inGameHud.getChatHud().addMessage(
                Text.literal("[Server Crash Guard] ").formatted(Formatting.YELLOW)
                    .append(Text.literal("已在本地拦截 " + notice.count() + " 个异常数据包；最近原因："
                            + describe(notice.reason()) + "。").formatted(Formatting.GRAY)));
    }

    private static String describe(String reason) {
        if (reason.startsWith("entity-spawn")) return "实体生成坐标或速度异常";
        if (reason.startsWith("metadata")) return "实体元数据异常";
        if (reason.startsWith("explosion") || reason.startsWith("accumulated-explosion")) return "爆炸数据或效果数量异常";
        if (reason.startsWith("particle")) return "粒子参数或数量异常";
        if (reason.startsWith("game-state")) return "游戏状态参数或频率异常";
        if (reason.contains("position") || reason.equals("vehicle-position")) return "位置或视角异常";
        if (reason.equals("entity-velocity")) return "实体速度异常";
        if (reason.equals("packet-rate")) return "数据包频率超限";
        if (reason.contains("bundle")) return "组合数据包异常";
        if (reason.equals("packet-decode")) return "游戏数据包解码异常";
        return "非法服务器数据";
    }
}
