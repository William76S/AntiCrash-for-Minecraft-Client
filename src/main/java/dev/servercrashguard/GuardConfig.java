package dev.servercrashguard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loaded once at startup; no file I/O or allocation-heavy parsing in packet hooks. */
public final class GuardConfig {
    public double maxHorizontalPosition = 30_000_000;
    public double maxVerticalPosition = 20_000_000;
    public double maxVelocity = 100;
    public double maxRotation = 1_000_000;
    public double maxExplosionRadius = 32;
    public int maxExplosionBlocks = 4096;
    public int maxExplosionPalette = 256;
    public int maxParticlesPerPacket = 1024;
    public double maxParticleOffset = 1024;
    public double maxParticleSpeed = 100;
    public int maxParticleLifetime = 1200;
    public int maxPacketsPerSecond = 20_000;
    public int maxExplosionsPerSecond = 64;
    public int maxVisualCostPerSecond = 8192;
    public int maxGameStatesPerSecond = 128;
    public int maxMetadataEntriesPerSecond = 65_536;

    public static GuardConfig load(Path path) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        GuardConfig config = new GuardConfig();
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    config = gson.fromJson(reader, GuardConfig.class);
                }
                if (config == null) throw new IllegalArgumentException("Empty configuration");
                config.validate();
            } else {
                Files.createDirectories(path.getParent());
                try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                    gson.toJson(config, writer);
                }
            }
        } catch (IOException | RuntimeException exception) {
            ServerCrashGuard.LOGGER.warn("Invalid/unavailable Server Crash Guard config; using defaults", exception);
            config = new GuardConfig();
        }
        return config;
    }

    public void validate() {
        // Upper bounds also keep arithmetic and counters safe if the file is edited by hand.
        range(maxHorizontalPosition, 1, 30_000_000);
        range(maxVerticalPosition, 1, 20_000_000);
        range(maxVelocity, 1, 10_000);
        range(maxRotation, 360, 10_000_000);
        range(maxExplosionRadius, 1, 128);
        range(maxExplosionBlocks, 1, 65_536);
        range(maxExplosionPalette, 1, 1024);
        range(maxParticlesPerPacket, 1, 16_384);
        range(maxParticleOffset, 1, 65_536);
        range(maxParticleSpeed, 1, 10_000);
        range(maxParticleLifetime, 1, 72_000);
        range(maxPacketsPerSecond, 100, 200_000);
        range(maxExplosionsPerSecond, 1, 1024);
        range(maxVisualCostPerSecond, 1, 131_072);
        range(maxGameStatesPerSecond, 1, 4096);
        range(maxMetadataEntriesPerSecond, 255, 1_048_576);
    }

    private static void range(double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException("Configuration value out of range: " + value);
    }
}
