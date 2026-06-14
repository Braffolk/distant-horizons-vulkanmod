package com.braffolk.dhvulkan;

import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin for the Beryl-specific mixin config.
 *
 * Gates Beryl mixins: only applies them when Beryl mod is loaded.
 * All Beryl mixins use @Pseudo and @Inject(require=0), so they're
 * already safe when Beryl is absent. This plugin provides an extra
 * safety layer and logging.
 */
public class DhVulkanBerylMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanMod-Beryl");

    /** Cached Beryl presence check */
    private static Boolean berylPresent = null;

    private static boolean isBerylLoaded() {
        if (berylPresent == null) {
            berylPresent = FabricLoader.getInstance().isModLoaded("beryl");
        }
        return berylPresent;
    }

    @Override
    public void onLoad(String mixinPackage) {
        if (isBerylLoaded()) {
            LOGGER.info("[DH-Vulkan-Beryl] Beryl detected — enabling Beryl integration mixins.");
        } else {
            LOGGER.info("[DH-Vulkan-Beryl] Beryl not detected — Beryl mixins will be skipped.");
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Only apply Beryl mixins when Beryl is loaded
        if (!isBerylLoaded()) {
            LOGGER.debug("[DH-Vulkan-Beryl] Skipping mixin {} (Beryl not loaded)", mixinClassName);
            return false;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
