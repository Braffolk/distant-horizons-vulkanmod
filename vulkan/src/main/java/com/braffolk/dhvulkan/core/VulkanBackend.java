package com.braffolk.dhvulkan.core;

import com.braffolk.dhvulkan.core.data.RenderUniforms;
import com.braffolk.dhvulkan.core.data.VkVertexData;
import com.seibel.distanthorizons.core.util.math.DhVec3f;

/**
 * Central rendering interface for the Vulkan backend.
 * Both DH 2.4 (mixin-based) and DH 3.0 (API-based) integration layers
 * call through this interface. No DH rendering types here --
 * only core data types and DH math/config (which are stable across versions).
 */
public interface VulkanBackend {

    /** Initialize Vulkan resources (pipeline, framebuffer, post-process pipelines) */
    void init();

    /** Start a new frame: save MC state, set DH state, begin render pass, bind pipeline */
    void beginFrame();

    /**
     * Upload per-frame uniform data (camera matrix, fog, noise, etc.).
     * Must be called after beginFrame() and before any drawVertexData() calls.
     */
    void fillUniforms(RenderUniforms uniforms);

    /**
     * Set the per-buffer model offset uniform.
     * Called once per LOD section, before drawing its VBOs.
     */
    void setModelOffset(DhVec3f modelOffset);

    /**
     * Draw a vertex buffer. Called per-VBO in the render loop.
     * The backend manages its own GPU buffer cache keyed by VkVertexData.id.
     *
     * @param data       vertex data wrapper (may have null vertexBuffer if VBO destroyed)
     * @param indexCount number of indices to draw
     */
    void drawVertexData(VkVertexData data, int indexCount);

    /**
     * Enable or disable alpha blending for the current render pass.
     * Called before drawing transparent LODs (water, glass).
     */
    void setBlendState(boolean enabled);

    /**
     * End the current frame's rendering.
     * Restores MC render state. Post-processing and compositing are now
     * handled by {@link #deferredComposite} which is called from
     * applyToMcTexture() — ensuring the composite happens before weather.
     */
    void endFrame(RenderUniforms uniforms);

    /**
     * Run post-processing (SSAO, fog) and composite DH's framebuffer onto
     * MC's render target. Must be called while MC's render pass is active
     * and BEFORE weather / translucent particles render.
     *
     * Called from applyToMcTexture() on DH 3.0, or from the equivalent
     * delegate method on DH 2.4.
     */
    void deferredComposite(RenderUniforms uniforms);

    /**
     * Late re-composite: called from renderLevel @RETURN, AFTER terrain + weather.
     * Re-composites DH LODs with real MC depth for SINGLE/DOUBLE fade modes.
     */
    void lateComposite(RenderUniforms uniforms);

    /**
     * Queue a VBO for deferred GPU buffer free on the next render frame.
     * Thread-safe -- called from DH's worker threads.
     */
    void queueDataFree(VkVertexData data);

    /**
     * Read MC's depth buffer and cache it for the current frame's composite.
     * Must be called at renderLevel @RETURN — after MC has fully rendered
     * terrain/translucent/weather, when the swapchain depth is in a known
     * Vulkan image layout that supports sampling.
     */
    void readAndCacheMcDepth();

    /** Clean up all Vulkan resources */
    void cleanup();
}
