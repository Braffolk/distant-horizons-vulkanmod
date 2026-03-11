package com.braffolk.dhvulkan.dh24;

import com.braffolk.dhvulkan.core.VulkanBackend;
import com.braffolk.dhvulkan.core.data.RenderUniforms;
import com.braffolk.dhvulkan.core.data.VkVertexData;
import com.braffolk.dhvulkan.dh24.duck.IVulkanVertexBuffer;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.core.util.math.Vec3f;

import java.nio.ByteBuffer;

/**
 * DH 2.4.x adapter: implements {@link IVulkanRenderDelegate} (DH 2.4 types)
 * and delegates to {@link VulkanBackend} (core types).
 *
 * Translates:
 * - DhApiRenderParam -> RenderUniforms
 * - GLVertexBuffer (+ duck interface) -> VkVertexData
 */
public class Dh24RenderDelegate implements IVulkanRenderDelegate {

    private final VulkanBackend backend;

    /** Reusable RenderUniforms to avoid per-frame allocations */
    private final RenderUniforms cachedUniforms = new RenderUniforms();

    public Dh24RenderDelegate(VulkanBackend backend) {
        this.backend = backend;
    }

    @Override
    public void init() {
        this.backend.init();
    }

    @Override
    public void beginFrame() {
        this.backend.beginFrame();
    }

    @Override
    public void fillUniformData(DhApiRenderParam renderParameters) {
        this.cachedUniforms.dhProjectionMatrix.set(renderParameters.dhProjectionMatrix);
        this.cachedUniforms.dhModelViewMatrix.set(renderParameters.dhModelViewMatrix);
        this.cachedUniforms.mcProjectionMatrix.set(renderParameters.mcProjectionMatrix);
        this.cachedUniforms.worldYOffset = renderParameters.worldYOffset;
        this.cachedUniforms.partialTicks = renderParameters.partialTicks;
        this.backend.fillUniforms(this.cachedUniforms);
    }

    @Override
    public void setModelOffset(Vec3f modelOffset) {
        this.backend.setModelOffset(modelOffset);
    }

    @Override
    public long uploadVertexData(ByteBuffer vertexData, int vertexCount) {
        return 0; // Not used by DH core
    }

    @Override
    public void drawBuffer(GLVertexBuffer vbo, int indexCount) {
        IVulkanVertexBuffer vkBuf = (IVulkanVertexBuffer) vbo;
        int vboId = System.identityHashCode(vbo);

        VkVertexData data = new VkVertexData(vboId);
        ByteBuffer handle = (ByteBuffer) vkBuf.dhvulkan$getVulkanBufferHandle();
        if (handle != null) {
            data.setData(handle, System.identityHashCode(handle));
        }

        this.backend.drawVertexData(data, indexCount);
    }

    @Override
    public void setBlendState(boolean enabled) {
        this.backend.setBlendState(enabled);
    }

    @Override
    public void endFrame(DhApiRenderParam renderParam) {
        this.cachedUniforms.dhProjectionMatrix.set(renderParam.dhProjectionMatrix);
        this.cachedUniforms.dhModelViewMatrix.set(renderParam.dhModelViewMatrix);
        this.cachedUniforms.mcProjectionMatrix.set(renderParam.mcProjectionMatrix);
        this.cachedUniforms.worldYOffset = renderParam.worldYOffset;
        this.cachedUniforms.partialTicks = renderParam.partialTicks;
        this.backend.endFrame(this.cachedUniforms);
    }

    @Override
    public void deferredComposite(DhApiRenderParam renderParam) {
        this.cachedUniforms.dhProjectionMatrix.set(renderParam.dhProjectionMatrix);
        this.cachedUniforms.dhModelViewMatrix.set(renderParam.dhModelViewMatrix);
        this.cachedUniforms.mcProjectionMatrix.set(renderParam.mcProjectionMatrix);
        this.cachedUniforms.worldYOffset = renderParam.worldYOffset;
        this.cachedUniforms.partialTicks = renderParam.partialTicks;
        this.backend.deferredComposite(this.cachedUniforms);
    }

    @Override
    public void freeBuffer(GLVertexBuffer vbo) {
        int vboId = System.identityHashCode(vbo);
        VkVertexData data = new VkVertexData(vboId);
        this.backend.queueDataFree(data);
    }

    @Override
    public void queueBufferFree(GLVertexBuffer vbo) {
        int vboId = System.identityHashCode(vbo);
        VkVertexData data = new VkVertexData(vboId);
        this.backend.queueDataFree(data);
    }

    @Override
    public void cleanup() {
        this.backend.cleanup();
    }
}
