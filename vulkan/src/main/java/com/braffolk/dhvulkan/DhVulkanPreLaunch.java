package com.braffolk.dhvulkan;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Pre-launch entrypoint for DH-VulkanMod bridge.
 * Mixin conflict resolution is handled by {@link DhVulkanMixinPlugin}.
 */
public class DhVulkanPreLaunch implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        System.out.println("[DH-VulkanMod] Pre-launch init");
    }
}
