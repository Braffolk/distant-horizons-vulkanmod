package com.braffolk.dhvulkan;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin that resolves the DH/VulkanMod TextureUtil conflict.
 *
 * DH's MixinTextureUtil uses @Redirect on GlStateManager._texParameter()
 * inside TextureUtil.prepareImage(). VulkanMod's MTextureUtil uses @Overwrite
 * to replace prepareImage() entirely. The Mixin framework blocks ALL injections
 * into overwritten methods — no priority-based workaround exists.
 *
 * Solution: In onLoad(), we reflectively access DH's mixin config and remove
 * MixinTextureUtil from its client mixin list before the Mixin framework
 * applies it. The removed redirect only applies OpenGL LOD bias, which is
 * irrelevant in Vulkan.
 */
public class DhVulkanMixinPlugin implements IMixinConfigPlugin {

    private static final String DH_CONFIG_NAME = "DistantHorizons.fabric.mixins.json";
    private static final String DH_MIXIN_TO_REMOVE = "client.MixinTextureUtil";
    // DH 3.0's MixinSharedConstants sets IS_RUNNING_IN_IDE = true, which enables
    // VulkanMod's debug validation (VkRenderPass.VALIDATION). VulkanMod's own
    // GuiRendererMixin intentionally skips setIndexBuffer(), so the validation
    // throws "Missing index buffer" on the very first GUI render frame.
    private static final String DH_MIXIN_SHARED_CONSTANTS = "client.MixinSharedConstants";

    @Override
    public void onLoad(String mixinPackage) {
        System.out.println("[DH-VulkanMod] Mixin plugin loaded, resolving DH/VulkanMod mixin conflicts...");

        boolean removed = false;
        try {
            for (Object config : Mixins.getConfigs()) {
                // Config wraps MixinConfig — get the name to identify DH's config
                String configName = config.toString();
                if (!configName.contains("DistantHorizons")) {
                    continue;
                }

                System.out.println("[DH-VulkanMod] Found DH config: " + configName);

                // Config.getConfig() returns the internal MixinConfig
                Object mixinConfig = getFieldValue(config, "config");
                if (mixinConfig == null) {
                    System.err.println("[DH-VulkanMod] Could not access internal MixinConfig");
                    continue;
                }

                // MixinConfig stores client mixins in a List<String> field
                // Try common field names: "mixinsClient", "mixins"
                List<String> clientMixins = getListField(mixinConfig, "mixinsClient");
                if (clientMixins == null) {
                    clientMixins = getListField(mixinConfig, "client");
                }
                if (clientMixins == null) {
                    // Try to find any List<String> field containing our targets
                    clientMixins = findMixinList(mixinConfig, DH_MIXIN_TO_REMOVE);
                }
                if (clientMixins == null) {
                    clientMixins = findMixinList(mixinConfig, DH_MIXIN_SHARED_CONSTANTS);
                }

                if (clientMixins != null) {
                    if (clientMixins.remove(DH_MIXIN_TO_REMOVE)) {
                        removed = true;
                        System.out.println("[DH-VulkanMod] ✓ Removed " + DH_MIXIN_TO_REMOVE
                                + " from DH config — TextureUtil conflict resolved");
                    }
                    if (clientMixins.remove(DH_MIXIN_SHARED_CONSTANTS)) {
                        removed = true;
                        System.out.println("[DH-VulkanMod] ✓ Removed " + DH_MIXIN_SHARED_CONSTANTS
                                + " from DH config — prevents VulkanMod validation crash");
                    }
                }
                if (!removed) {
                    System.out.println("[DH-VulkanMod] No conflicting DH mixins found in config"
                            + " (may not exist in this DH version — no conflict)");
                }
            }
        } catch (Exception e) {
            System.err.println("[DH-VulkanMod] WARNING: Failed to strip DH's MixinTextureUtil: " + e);
            e.printStackTrace();
        }

        if (!removed) {
            System.err.println("[DH-VulkanMod] Could not remove conflicting DH mixins from DH config.");
            System.err.println("[DH-VulkanMod] If a mixin crash occurs, add JVM arg: -Dmixin.checks=false");
        }
    }

    @SuppressWarnings("unchecked")
    private static Object getFieldValue(Object obj, String fieldName) {
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> getListField(Object obj, String fieldName) {
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(obj);
            if (value instanceof List) {
                return (List<String>) value;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> findMixinList(Object obj, String targetEntry) {
        for (Field field : obj.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(obj);
                if (value instanceof List) {
                    List<?> list = (List<?>) value;
                    for (Object item : list) {
                        if (targetEntry.equals(item)) {
                            return (List<String>) value;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    // ---- No-op defaults for other IMixinConfigPlugin methods ----

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Lazily detect DH version
        if (isDh3Present == null) {
            try {
                Class.forName(
                    "com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition"
                );
                isDh3Present = true;
                System.out.println("[DH-VulkanMod] DH 3.0 detected. Using API integration + dh3 mixins.");
            } catch (ClassNotFoundException e) {
                isDh3Present = false;
                System.out.println("[DH-VulkanMod] DH 2.4 detected. Using mixin integration + dh24 mixins.");
            }
        }

        // dh24 mixins: only for DH 2.4
        if (mixinClassName.contains(".dh24.") && isDh3Present) {
            return false;
        }
        // dh3 mixins: only for DH 3.0
        if (mixinClassName.contains(".dh3.") && !isDh3Present) {
            return false;
        }
        return true;
    }

    private static Boolean isDh3Present = null;

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
