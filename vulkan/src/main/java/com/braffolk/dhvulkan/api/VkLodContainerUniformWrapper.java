package com.braffolk.dhvulkan.api;

import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.ILodContainerUniformBufferWrapper;

/**
 * Stub implementation of DH's uniform buffer wrapper.
 * In the OpenGL path this manages per-section UBO data. In Vulkan,
 * our engine handles uniform uploads internally via push constants
 * and the fillUniforms() / setModelOffset() pipeline, so this is a no-op.
 *
 * DH 3.2.0-b interface: {@code tryUpload(LodBufferContainer)} + {@code close()}.
 */
public class VkLodContainerUniformWrapper implements ILodContainerUniformBufferWrapper {

    @Override
    public void tryUpload(LodBufferContainer bufferContainer) {
        // No-op: Vulkan engine manages uniforms internally
    }

    @Override
    public void close() {
        // No-op
    }
}
