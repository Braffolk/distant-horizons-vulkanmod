package com.braffolk.dhvulkan.duck;

import com.braffolk.dhvulkan.IVulkanRenderDelegate;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;

/**
 * Duck interface for {@code LodRenderer}. Implemented via Mixin to give
 * the extension mod access to the Vulkan delegate on the DH singleton.
 */
public interface IVulkanLodRenderer {
    void dhvulkan$setVulkanDelegate(IVulkanRenderDelegate delegate);

    IVulkanRenderDelegate dhvulkan$getVulkanDelegate();

    void dhvulkan$setLastVulkanRenderParams(DhApiRenderParam params);

    DhApiRenderParam dhvulkan$getLastVulkanRenderParams();

    void dhvulkan$compositeVulkanFrame();
}
