package com.braffolk.dhvulkan.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.*;

/**
 * Simple JSON config for the DH-VulkanMod extension.
 * Saved to {@code config/dh-vulkanmod.json}.
 */
public class DhVulkanConfig {

    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("dh-vulkanmod.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static DhVulkanConfig INSTANCE;

    // ---- Config Fields ----

    /**
     * Debug render mode for the composite shader.
     * 0=normal, 1=DH depth, 2=SSAO, 3=fog alpha, 4=fog color, 5=normals, 6=MC depth
     * Hot-reloadable: edit dh-vulkanmod.json while the game is running.
     */
    public int vulkanRenderMode = 0;

    // ---- Load / Save ----

    public static DhVulkanConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    /** Re-read config from disk. Call once per frame for hot-reload. */
    public static void reload() {
        INSTANCE = load();
    }

    private static DhVulkanConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                DhVulkanConfig config = GSON.fromJson(json, DhVulkanConfig.class);
                if (config != null)
                    return config;
            } catch (Exception e) {
                System.err.println("[DH-VulkanMod] Failed to load config, using defaults: " + e.getMessage());
            }
        }
        // Create default config
        DhVulkanConfig config = new DhVulkanConfig();
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(this));
        } catch (IOException e) {
            System.err.println("[DH-VulkanMod] Failed to save config: " + e.getMessage());
        }
    }
}
