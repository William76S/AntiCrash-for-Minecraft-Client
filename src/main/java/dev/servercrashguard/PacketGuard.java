package dev.servercrashguard;

import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import net.minecraft.entity.EntityPosition;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.particle.*;
import net.minecraft.util.math.EulerAngle;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Per-connection ingress guard. It never reads a world or entity on the Netty thread. */
public final class PacketGuard {
    private final GuardConfig config;
    private final TokenBucket packets;
    private final TokenBucket explosions;
    private final TokenBucket visuals;
    private final TokenBucket states;
    private final TokenBucket metadata;

    public PacketGuard(GuardConfig config) { this(config, System::nanoTime); }

    public PacketGuard(GuardConfig config, LongSupplier clock) {
        this.config = config;
        packets = new TokenBucket(config.maxPacketsPerSecond, clock);
        explosions = new TokenBucket(config.maxExplosionsPerSecond, clock);
        visuals = new TokenBucket(config.maxVisualCostPerSecond, clock);
        states = new TokenBucket(config.maxGameStatesPerSecond, clock);
        metadata = new TokenBucket(config.maxMetadataEntriesPerSecond, clock);
    }

    /** null means allow; a fixed reason string means cancel and disconnect. */
    public String inspect(Packet<?> packet) { return inspect(packet, false); }

    /**
     * Return a packet safe to apply, or null. Bundle siblings survive a rejected child.
     * Each original packet is charged once, including rejected packets.
     */
    public Packet<?> filter(Packet<?> packet, java.util.function.BiConsumer<Packet<?>, String> onDrop) {
        if (!(packet instanceof BundleS2CPacket bundle)) {
            String reason = inspect(packet);
            if (reason != null) { onDrop.accept(packet, reason); return null; }
            return packet;
        }
        if (!packets.take(1)) { onDrop.accept(packet, "packet-rate"); return null; }
        var kept = new java.util.ArrayList<Packet<? super net.minecraft.network.listener.ClientPlayPacketListener>>();
        boolean changed = false;
        int count = 0;
        for (var child : bundle.getPackets()) {
            if (++count > 4096) {
                onDrop.accept(bundle, "bundle-size");
                changed = true;
                break;
            }
            String reason = inspect(child, true);
            if (reason == null) kept.add(child);
            else { onDrop.accept(child, reason); changed = true; }
        }
        if (kept.isEmpty()) return null;
        return changed ? new BundleS2CPacket(kept) : bundle;
    }

    private String inspect(Packet<?> packet, boolean insideBundle) {
        if (!packets.take(1)) return "packet-rate";
        if (packet instanceof BundleS2CPacket bundle) {
            if (insideBundle) return "nested-bundle";
            int count = 0;
            for (Packet<?> child : bundle.getPackets()) {
                if (++count > 4096) return "bundle-size";
                String reason = inspect(child, true);
                if (reason != null) return reason;
            }
            return null;
        }
        if (packet instanceof GameStateChangeS2CPacket p) {
            if (!states.take(1)) return "game-state-rate";
            return validGameState(p) ? null : "game-state-value";
        }
        if (packet instanceof ExplosionS2CPacket p) {
            if (!position(p.center()) || !bounded(p.radius(), config.maxExplosionRadius) || p.radius() < 0
                    || p.blockCount() < 0 || p.blockCount() > config.maxExplosionBlocks
                    || p.playerKnockback().filter(v -> !velocity(v)).isPresent()
                    || !validParticle(p.explosionParticle())
                    || (p.radius() == 0 && p.blockCount() > 0 && !p.blockParticles().isEmpty())
                    || p.blockParticles().getEntries().size() > config.maxExplosionPalette)
                return "explosion-value";
            long totalWeight = 0;
            for (var entry : p.blockParticles().getEntries()) {
                var effect = entry.value();
                if (entry.weight() < 0 || effect == null || !validParticle(effect.particle())
                        || !bounded(effect.scaling(), 16) || effect.scaling() < 0
                        || !bounded(effect.speed(), config.maxParticleSpeed)) return "explosion-palette";
                totalWeight += entry.weight();
            }
            if (!p.blockParticles().isEmpty() && (totalWeight <= 0 || totalWeight > Integer.MAX_VALUE))
                return "explosion-palette-weight";
            var range = p.explosionSound().value().fixedRange();
            if (range.isPresent() && (!bounded(range.get(), 65_536) || range.get() < 0))
                return "explosion-sound";
            // Charge block effects even for a small radius, including alternate explosion particles.
            if (!explosions.take(1) || !visuals.take(Math.max(1, p.blockCount()))) return "explosion-budget";
            return null;
        }
        if (packet instanceof EntityVelocityUpdateS2CPacket p)
            return velocity(p.getVelocity()) ? null : "entity-velocity";
        if (packet instanceof ParticleS2CPacket p) {
            if (!position(p.getX(), p.getY(), p.getZ()) || p.getCount() < 0
                    || p.getCount() > config.maxParticlesPerPacket
                    || !bounded(p.getOffsetX(), config.maxParticleOffset)
                    || !bounded(p.getOffsetY(), config.maxParticleOffset)
                    || !bounded(p.getOffsetZ(), config.maxParticleOffset)
                    || (p.getCount() == 0 && (!bounded((double) p.getOffsetX() * p.getSpeed(), config.maxParticleSpeed)
                        || !bounded((double) p.getOffsetY() * p.getSpeed(), config.maxParticleSpeed)
                        || !bounded((double) p.getOffsetZ() * p.getSpeed(), config.maxParticleSpeed)))
                    || !bounded(p.getSpeed(), config.maxParticleSpeed) || !validParticle(p.getParameters()))
                return "particle-value";
            // count=0 is the protocol's directional, single-particle mode; it still costs one.
            return visuals.take(Math.max(1, p.getCount())) ? null : "particle-budget";
        }
        if (packet instanceof PlayerPositionLookS2CPacket p)
            return entityPosition(p.change()) ? null : "player-position";
        if (packet instanceof EntityPositionS2CPacket p)
            return entityPosition(p.change()) ? null : "entity-position";
        if (packet instanceof EntityPositionSyncS2CPacket p)
            return entityPosition(p.values()) ? null : "entity-position-sync";
        if (packet instanceof VehicleMoveS2CPacket p)
            return position(p.position()) && rotation(p.yaw(), p.pitch()) ? null : "vehicle-position";
        if (packet instanceof EntitySpawnS2CPacket p)
            return position(p.getX(), p.getY(), p.getZ()) && velocity(p.getVelocity()) ? null : "entity-spawn";
        if (packet instanceof EntityTrackerUpdateS2CPacket p) {
            String reason = metadataValues(p.trackedValues());
            if (reason != null) return reason;
            return metadata.take(p.trackedValues().size()) ? null : "metadata-budget";
        }
        return null;
    }

    public boolean position(Vec3d v) { return v != null && position(v.x, v.y, v.z); }

    public boolean position(double x, double y, double z) {
        return bounded(x, config.maxHorizontalPosition) && bounded(z, config.maxHorizontalPosition)
                && bounded(y, config.maxVerticalPosition);
    }

    public boolean velocity(Vec3d v) {
        return v != null && bounded(v.x, config.maxVelocity) && bounded(v.y, config.maxVelocity)
                && bounded(v.z, config.maxVelocity);
    }

    public boolean rotation(float yaw, float pitch) {
        return bounded(yaw, config.maxRotation) && bounded(pitch, config.maxRotation);
    }

    public boolean entityPosition(EntityPosition p) {
        return p != null && position(p.position()) && velocity(p.deltaMovement()) && rotation(p.yaw(), p.pitch());
    }

    private boolean validGameState(GameStateChangeS2CPacket p) {
        var reason = p.getReason();
        float value = p.getValue();
        if (reason == null || !Float.isFinite(value)) return false;
        if (reason == GameStateChangeS2CPacket.GAME_MODE_CHANGED)
            return value >= 0 && value <= 3 && value == Math.rint(value);
        if (reason == GameStateChangeS2CPacket.DEMO_MESSAGE_SHOWN)
            return value == 0 || value == 101 || value == 102 || value == 103 || value == 104;
        if (reason == GameStateChangeS2CPacket.RAIN_GRADIENT_CHANGED
                || reason == GameStateChangeS2CPacket.THUNDER_GRADIENT_CHANGED)
            return value >= 0 && value <= 1;
        if (reason == GameStateChangeS2CPacket.NO_RESPAWN_BLOCK
                || reason == GameStateChangeS2CPacket.RAIN_STARTED || reason == GameStateChangeS2CPacket.RAIN_STOPPED
                || reason == GameStateChangeS2CPacket.GAME_WON || reason == GameStateChangeS2CPacket.PROJECTILE_HIT_PLAYER
                || reason == GameStateChangeS2CPacket.PUFFERFISH_STING || reason == GameStateChangeS2CPacket.ELDER_GUARDIAN_EFFECT
                || reason == GameStateChangeS2CPacket.IMMEDIATE_RESPAWN
                || reason == GameStateChangeS2CPacket.LIMITED_CRAFTING_TOGGLED
                || reason == GameStateChangeS2CPacket.INITIAL_CHUNKS_COMING)
            return value == 0 || value == 1;
        return false;
    }

    public boolean validParticle(ParticleEffect effect) {
        if (effect == null) return false;
        if (effect instanceof AbstractDustParticleEffect p)
            return Float.isFinite(p.getScale()) && p.getScale() >= 0.01f && p.getScale() <= 4;
        if (effect instanceof SculkChargeParticleEffect p) return bounded(p.roll(), config.maxRotation);
        if (effect instanceof ShriekParticleEffect p) return lifetime(p.getDelay(), true);
        if (effect instanceof TrailParticleEffect p) return position(p.target()) && lifetime(p.duration(), false);
        if (effect instanceof VibrationParticleEffect p) return lifetime(p.getArrivalInTicks(), false);
        return true; // Unknown/modded particle payloads are not interpreted as vanilla types.
    }

    private boolean lifetime(int ticks, boolean allowZero) {
        return ticks >= (allowZero ? 0 : 1) && ticks <= config.maxParticleLifetime;
    }

    public String metadataValues(List<DataTracker.SerializedEntry<?>> values) {
        if (values == null || values.size() > MetadataDecoder.MAX_ENTRIES) return "metadata-count";

        for (var value : values) {
            if (value == null || value.id() < 0 || value.id() >= 255) return "metadata-index";

            if (value.handler() == null || !safeValue(value.value(), 0)) return "metadata-value";
        }
        return null;
    }

    private boolean safeValue(Object value, int depth) {
        if (value == null || depth > 8) return false;
        if (value instanceof Float f) return bounded(f, 1_000_000);
        if (value instanceof Double d) return bounded(d, 1_000_000);
        if (value instanceof Vector3f v)
            return bounded(v.x(), 1_000_000) && bounded(v.y(), 1_000_000) && bounded(v.z(), 1_000_000);
        if (value instanceof Quaternionf q)
            return bounded(q.x(), 1) && bounded(q.y(), 1) && bounded(q.z(), 1) && bounded(q.w(), 1)
                    && q.lengthSquared() > 1.0e-12f;
        if (value instanceof EulerAngle a)
            return rotation(a.yaw(), a.pitch()) && bounded(a.roll(), config.maxRotation);
        if (value instanceof Vec3d v) return position(v);
        if (value instanceof ParticleEffect p) return validParticle(p);
        if (value instanceof Optional<?> o) return o.isEmpty() || safeValue(o.get(), depth + 1);
        if (value instanceof List<?> list) {
            if (list.size() > 256) return false;
            for (Object element : list) if (!safeValue(element, depth + 1)) return false;
        }
        return true;
    }

    /** Preflight the entire update before the first entry can mutate the entity. */
    public String metadataSchema(List<DataTracker.SerializedEntry<?>> values, DataTracker.Entry<?>[] entries) {
        String reason = metadataValues(values);
        if (reason != null) return reason;
        for (var value : values) {
            int id = value.id();
            if (id >= entries.length || entries[id] == null) return "metadata-unknown-field";
            if (entries[id].getData().dataType() != value.handler()) return "metadata-type";
        }
        return null;
    }

    public static boolean bounded(double value, double max) {
        return Double.isFinite(value) && Math.abs(value) <= max;
    }
}
