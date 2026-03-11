package com.braffolk.dhvulkan.api;

import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IDhGenericObjectVertexBufferContainer;

import java.util.List;

/**
 * Stub implementation of DH's generic object VBO container.
 * Used for custom API-registered renderers (e.g., plugin-defined geometry).
 * Not yet implemented for Vulkan -- returns EState.NEW to signal inactive.
 */
public class VkGenericObjectVboContainer implements IDhGenericObjectVertexBufferContainer {

    private EState state = EState.NEW;

    @Override
    public void uploadDataToGpu() {
        // Not yet implemented
    }

    @Override
    public void updateVertexData(List<DhApiRenderableBox> uploadBoxList) {
        // Not yet implemented
    }

    @Override
    public EState getState() {
        return state;
    }

    @Override
    public void setState(EState state) {
        this.state = state;
    }

    @Override
    public void close() {
        // No-op
    }
}
