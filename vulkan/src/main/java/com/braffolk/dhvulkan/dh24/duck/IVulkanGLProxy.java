package com.braffolk.dhvulkan.dh24.duck;

/**
 * Duck interface for {@code GLProxy}. Implemented via Mixin to expose
 * VulkanMod detection on the unmodified DH class.
 */
public interface IVulkanGLProxy {
    /**
     * @return true if VulkanMod is loaded, meaning no GL context is available
     */
    static boolean isVulkanModActive() {
        return MixinGLProxyState.VULKANMOD_ACTIVE;
    }
}

/**
 * Internal holder so the static detection result is accessible without an
 * instance.
 */
class MixinGLProxyState {
    static final boolean VULKANMOD_ACTIVE = detectVulkanMod();

    private static boolean detectVulkanMod() {
        try {
            Class.forName("net.vulkanmod.vulkan.Renderer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
