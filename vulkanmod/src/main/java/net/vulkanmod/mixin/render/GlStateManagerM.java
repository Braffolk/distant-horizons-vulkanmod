package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.jtracy.Plot;
import net.vulkanmod.gl.*;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.Shadow;

import java.nio.ByteBuffer;

@Mixin(GlStateManager.class)
public class GlStateManagerM {

    @Shadow @Final private static Plot PLOT_BUFFERS;

    @Shadow private static int numBuffers;

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _bindTexture(int i) {
        VkGlTexture.bindTexture(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _disableBlend() {
        RenderSystem.assertOnRenderThread();
        VRenderSystem.disableBlend();
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _enableBlend() {
        RenderSystem.assertOnRenderThread();
        VRenderSystem.enableBlend();
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _blendFuncSeparate(int i, int j, int k, int l) {
        RenderSystem.assertOnRenderThread();
        VRenderSystem.blendFuncSeparate(i, j, k, l);

    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _disableScissorTest() {
        Renderer.resetScissor();
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _enableScissorTest() {}

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _enableCull() {
        VRenderSystem.enableCull();
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _disableCull() {
        VRenderSystem.disableCull();
    }

    /**
     * @author
     */
    @Redirect(method = "_viewport", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glViewport(IIII)V"), remap = false)
    private static void _viewport(int x, int y, int width, int height) {
        Renderer.setViewport(x, y, width, height);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _scissorBox(int x, int y, int width, int height) {
        Renderer.setScissor(x, y, width, height);
    }

    //TODO
    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int _getError() {
        return 0;
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, @Nullable ByteBuffer pixels) {
        RenderSystem.assertOnRenderThread();
        VkGlTexture.texImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _texSubImage2D(int target, int level, int offsetX, int offsetY, int width, int height, int format, int type, long pixels) {
        RenderSystem.assertOnRenderThread();
        VkGlTexture.texSubImage2D(target, level, offsetX, offsetY, width, height, format, type, pixels);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _activeTexture(int i) {
        VkGlTexture.activeTexture(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _texParameter(int i, int j, int k) {
        VkGlTexture.texParameteri(i, j, k);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int _getTexLevelParameter(int i, int j, int k) {
        return VkGlTexture.getTexLevelParameter(i, j, k);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _pixelStore(int pname, int param) {
        //Used during upload to set copy offsets
        RenderSystem.assertOnRenderThread();
        VkGlTexture.pixelStoreI(pname, param);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int _genTexture() {
        RenderSystem.assertOnRenderThread();
        return VkGlTexture.genTextureId();
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _deleteTexture(int i) {
        RenderSystem.assertOnRenderThread();
        VkGlTexture.glDeleteTextures(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        RenderSystem.assertOnRenderThread();
        VRenderSystem.colorMask(red, green, blue, alpha);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _depthFunc(int i) {
        RenderSystem.assertOnRenderThread();
        VRenderSystem.depthFunc(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _polygonMode(int face, int mode) {
        RenderSystem.assertOnRenderThread();
        VRenderSystem.setPolygonModeGL(mode);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _enablePolygonOffset() {
        RenderSystem.assertOnRenderThread();
        VRenderSystem.enablePolygonOffset();
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _disablePolygonOffset() {
        RenderSystem.assertOnRenderThread();
        VRenderSystem.disablePolygonOffset();
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _polygonOffset(float f, float g) {
        RenderSystem.assertOnRenderThread();
        VRenderSystem.polygonOffset(f, g);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _enableColorLogicOp() {
        RenderSystem.assertOnRenderThread();
        VRenderSystem.enableColorLogicOp();
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _disableColorLogicOp() {
        RenderSystem.assertOnRenderThread();
        VRenderSystem.disableColorLogicOp();
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _logicOp(int i) {
        RenderSystem.assertOnRenderThread();
        VRenderSystem.logicOp(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _clear(int mask) {
        RenderSystem.assertOnRenderThread();
        VRenderSystem.clear(mask);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _disableDepthTest() {
        RenderSystem.assertOnRenderThread();
        VRenderSystem.disableDepthTest();
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _enableDepthTest() {
        RenderSystem.assertOnRenderThread();
        VRenderSystem.enableDepthTest();
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _depthMask(boolean bl) {
        RenderSystem.assertOnRenderThread();
        VRenderSystem.depthMask(bl);

    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int glGenFramebuffers() {
        RenderSystem.assertOnRenderThread();
        return VkGlFramebuffer.genFramebufferId();
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _glBindFramebuffer(int i, int j) {
        RenderSystem.assertOnRenderThread();
        VkGlFramebuffer.bindFramebuffer(i, j);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _glFramebufferTexture2D(int i, int j, int k, int l, int m) {
        RenderSystem.assertOnRenderThread();
        VkGlFramebuffer.framebufferTexture2D(i, j, k, l, m);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int _glGenBuffers() {
        RenderSystem.assertOnRenderThread();

        numBuffers++;
        PLOT_BUFFERS.setValue(numBuffers);

        return VkGlBuffer.glGenBuffers();
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _glBindBuffer(int i, int j) {
        RenderSystem.assertOnRenderThread();
        VkGlBuffer.glBindBuffer(i, j);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _glBufferData(int i, ByteBuffer byteBuffer, int j) {
        RenderSystem.assertOnRenderThread();
        VkGlBuffer.glBufferData(i, byteBuffer, j);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _glBufferData(int i, long l, int j) {
        RenderSystem.assertOnRenderThread();
        VkGlBuffer.glBufferData(i, l, j);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _glUnmapBuffer(int i) {
        RenderSystem.assertOnRenderThread();
        VkGlBuffer.glUnmapBuffer(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _glDeleteBuffers(int i) {
        RenderSystem.assertOnRenderThread();
        VkGlBuffer.glDeleteBuffers(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void glDeleteShader(int i) {
        RenderSystem.assertOnRenderThread();
        VkGlShader.glDeleteShader(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int glCreateShader(int i) {
        RenderSystem.assertOnRenderThread();
        return VkGlShader.glCreateShader(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void glShaderSource(int i, String string) {
        RenderSystem.assertOnRenderThread();
        VkGlShader.glShaderSource(i, string);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void glCompileShader(int i) {
        RenderSystem.assertOnRenderThread();
        VkGlShader.glCompileShader(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int glGetShaderi(int i, int j) {
        RenderSystem.assertOnRenderThread();
        return VkGlShader.glGetShaderi(i, j);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _glUseProgram(int i) {
//        RenderSystem.assertOnRenderThread();
//        GL20.glUseProgram(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int glCreateProgram() {
//        RenderSystem.assertOnRenderThread();
//        return GL20.glCreateProgram();
        return 0;
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void glDeleteProgram(int i) {
//        RenderSystem.assertOnRenderThread();
//        GL20.glDeleteProgram(i);
    }
}
