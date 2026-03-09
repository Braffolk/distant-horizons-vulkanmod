package com.braffolk.dhvulkan;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.spongepowered.asm.mixin.Mixins;

/**
 * Pre-launch entrypoint that resolves mixin conflicts between DH and VulkanMod.
 *
 * DH's MixinTextureUtil @Redirects setLodBias inside
 * TextureUtil.prepareImage(),
 * but VulkanMod's MTextureUtil @Overwrites prepareImage() entirely. The Mixin
 * framework refuses to inject into an overwritten method at the same priority,
 * causing a hard crash. Since LOD bias is an OpenGL concept irrelevant in
 * Vulkan,
 * we disable that DH mixin entirely.
 */
public class DhVulkanPreLaunch implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        try {
            for (var config : Mixins.getConfigs()) {
                String name = config.getName();
                if (name != null && name.contains("DistantHorizons") && name.contains("fabric")) {
                    // Found DH's fabric mixin config — remove MixinTextureUtil via reflection
                    var innerConfig = config.getConfig();
                    var getClientMixinsMethod = innerConfig.getClass().getDeclaredMethod("getClientMixinList");
                    getClientMixinsMethod.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    java.util.List<String> clientMixins = (java.util.List<String>) getClientMixinsMethod
                            .invoke(innerConfig);
                    if (clientMixins != null && clientMixins.remove("client.MixinTextureUtil")) {
                        System.out.println(
                                "[DH-VulkanMod] Disabled DH's MixinTextureUtil to prevent conflict with VulkanMod");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[DH-VulkanMod] WARNING: Could not disable DH's MixinTextureUtil: " + e.getMessage());
            System.err.println("[DH-VulkanMod] If a mixin crash occurs, add JVM arg: -Dmixin.env.disableRefMap=true");
        }
    }
}
