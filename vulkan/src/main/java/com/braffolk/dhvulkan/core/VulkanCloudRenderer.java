package com.braffolk.dhvulkan.core;

import com.braffolk.dhvulkan.compat.Compat;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.util.math.Mat4f;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

/**
 * Renders clouds after the DH composite pass, so they depth-test against
 * both MC terrain and DH LOD depth in the combined depth buffer.
 *
 * Requires VM 0.6+ API (PipelineManager.getCloudsPipeline(), VBO.bind/draw).
 * On older VM versions, this renderer is disabled and the engine should
 * use an alternative cloud rendering path.
 */
public class VulkanCloudRenderer {

    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanMod");

    // Cached singleton — avoids per-frame SingletonInjector.INSTANCE.get()
    private static final IMinecraftRenderWrapper MC_RENDER =
            SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);

    // Cloud grid constants (same as VM)
    private static final int CELL_WIDTH = 12;
    private static final int CELL_HEIGHT = 4;

    // Face direction bits
    private static final int DIR_NEG_Y_BIT = 1;
    private static final int DIR_POS_Y_BIT = 1 << 1;
    private static final int DIR_NEG_X_BIT = 1 << 2;
    private static final int DIR_POS_X_BIT = 1 << 3;
    private static final int DIR_NEG_Z_BIT = 1 << 4;
    private static final int DIR_POS_Z_BIT = 1 << 5;

    // Y-state relative to clouds
    private static final byte Y_BELOW_CLOUDS = 0;
    private static final byte Y_ABOVE_CLOUDS = 1;
    private static final byte Y_INSIDE_CLOUDS = 2;

    // Cloud grid data (loaded from clouds.png)
    private int[] cloudPixels;
    private byte[] cloudRenderFaces;
    private int cloudGridWidth;
    private boolean textureLoaded = false;

    // Mesh caching
    private Object cloudVBO; // net.vulkanmod.render.VBO
    private int prevCloudX;
    private int prevCloudZ;
    private byte prevCloudY;
    private CloudStatus prevCloudsType;
    private boolean needsRebuild = true;

    // Reflection — VM 0.6 only (converted to MethodHandles for performance)
    private boolean reflectionResolved = false;
    private boolean reflectionFailed = false;
    private GraphicsPipeline cloudsPipeline;
    private MethodHandle vboConstructorHandle;
    private MethodHandle vboUploadHandle;
    private MethodHandle vboBindHandle;
    private MethodHandle vboDrawHandle;
    private MethodHandle vboCloseHandle;

    // Reusable JOML matrices — avoids per-frame createJomlMatrix() allocations
    private final Matrix4f reusableJomlProj = new Matrix4f();
    private final Matrix4f reusableJomlModelView = new Matrix4f();

    // Config cache
    private boolean cloudRenderingEnabled = true;
    private int configRefreshCounter = 0;
    private static final int CONFIG_REFRESH_INTERVAL = 60;

    /** Returns true if this renderer is available (VM 0.6+). */
    public boolean isAvailable() {
        if (!this.reflectionResolved) {
            resolveReflection();
        }
        return !this.reflectionFailed;
    }

    public void renderIfEnabled(float partialTicks, Mat4f mcProjection, Mat4f mcModelView) {
        // Periodic config check
        if (--this.configRefreshCounter <= 0) {
            this.configRefreshCounter = CONFIG_REFRESH_INTERVAL;
            try {
                this.cloudRenderingEnabled = Config.Client.Advanced.Graphics.GenericRendering.enableCloudRendering.get();
            } catch (Exception e) {
                this.cloudRenderingEnabled = true;
            }
        }
        if (!this.cloudRenderingEnabled) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        int cloudHeight = Compat.getCloudHeight(level);
        if (cloudHeight < 0) return;

        if (!this.reflectionResolved) resolveReflection();
        if (this.reflectionFailed) return;

        if (!this.textureLoaded) {
            loadCloudTexture();
            if (!this.textureLoaded) return;
        }

        try {
            renderClouds(level, mc, cloudHeight, partialTicks, mcProjection, mcModelView);
        } catch (Throwable e) {
            LOGGER.error("[DH-VulkanMod] Cloud rendering failed", e);
        }
    }

    private void renderClouds(ClientLevel level, Minecraft mc, int cloudHeight,
                              float partialTicks, Mat4f mcProjection, Mat4f mcModelView) throws Throwable {
        var camPos = MC_RENDER.getCameraExactPosition();

        int ticks = (int) (level.getGameTime() % Integer.MAX_VALUE);
        double timeOffset = (ticks + partialTicks) * 0.03F;
        double centerX = camPos.x + timeOffset;
        double centerZ = camPos.z + 0.33F * CELL_WIDTH;
        double centerY = cloudHeight - camPos.y + 0.33F;

        int centerCellX = (int) Math.floor(centerX / CELL_WIDTH);
        int centerCellZ = (int) Math.floor(centerZ / CELL_WIDTH);

        byte yState;
        if (centerY < -4.0f) yState = Y_BELOW_CLOUDS;
        else if (centerY > 0.0f) yState = Y_ABOVE_CLOUDS;
        else yState = Y_INSIDE_CLOUDS;

        CloudStatus cloudsType = mc.options.getCloudsType();

        // Rebuild mesh when camera cell changes
        if (centerCellX != this.prevCloudX || centerCellZ != this.prevCloudZ
                || cloudsType != this.prevCloudsType || this.prevCloudY != yState
                || this.cloudVBO == null) {
            this.prevCloudX = centerCellX;
            this.prevCloudZ = centerCellZ;
            this.prevCloudsType = cloudsType;
            this.prevCloudY = yState;
            this.needsRebuild = true;
        }

        if (this.needsRebuild) {
            this.needsRebuild = false;
            if (this.cloudVBO != null) this.vboCloseHandle.invoke(this.cloudVBO);

            Object cloudsMesh = buildCloudMesh(centerCellX, centerCellZ, centerY, cloudsType);
            if (cloudsMesh == null) { this.cloudVBO = null; return; }

            this.cloudVBO = this.vboConstructorHandle.invoke(true);
            this.vboUploadHandle.invoke(this.cloudVBO, cloudsMesh);
        }

        if (this.cloudVBO == null) return;

        // --- Rendering ---
        float xTranslation = (float) (centerX - (centerCellX * CELL_WIDTH));
        float yTranslation = (float) centerY;
        float zTranslation = (float) (centerZ - (centerCellZ * CELL_WIDTH));

        // Restore MC's projection — critical for depth testing against combined depth buffer.
        copyToJoml(mcProjection, this.reusableJomlProj);
        VRenderSystem.applyProjectionMatrix(this.reusableJomlProj);

        // Set up model-view: camera view + cloud translation.
        Matrix4fStack poseStack = RenderSystem.getModelViewStack();
        poseStack.pushMatrix();
        try {
            copyToJoml(mcModelView, this.reusableJomlModelView);
            poseStack.set(this.reusableJomlModelView);
            poseStack.translate(-xTranslation, yTranslation, -zTranslation);
            VRenderSystem.applyModelViewMatrix(poseStack);
            VRenderSystem.calculateMVP();

            Compat.setModelOffset(-xTranslation, 0, -zTranslation);

            float[] cloudColor = Compat.getCloudColorRGB(level, partialTicks);
            VRenderSystem.setShaderColor(cloudColor[0], cloudColor[1], cloudColor[2], 0.8f);

            GraphicsPipeline pipeline = this.cloudsPipeline;

            // Render state
            VRenderSystem.enableBlend();
            VRenderSystem.blendFuncSeparate(
                    org.lwjgl.opengl.GL11.GL_SRC_ALPHA,
                    org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA,
                    org.lwjgl.opengl.GL11.GL_ONE,
                    org.lwjgl.opengl.GL11.GL_ZERO);
            VRenderSystem.enableDepthTest();
            VRenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
            VRenderSystem.depthMask = true;
            VRenderSystem.setPolygonModeGL(org.lwjgl.opengl.GL11.GL_FILL);
            VRenderSystem.setPrimitiveTopologyGL(org.lwjgl.opengl.GL11.GL_TRIANGLES);

            boolean fastClouds = cloudsType == CloudStatus.FAST;
            boolean insideClouds = yState == Y_INSIDE_CLOUDS;
            if (insideClouds || (fastClouds && centerY <= 0.0f)) {
                VRenderSystem.disableCull();
            } else {
                VRenderSystem.enableCull();
            }

            // Fancy clouds: depth-only pre-pass
            if (!fastClouds) {
                VRenderSystem.colorMask(false, false, false, false);
                this.vboBindHandle.invoke(this.cloudVBO, pipeline);
                this.vboDrawHandle.invoke(this.cloudVBO);
                VRenderSystem.colorMask(true, true, true, true);
            }

            // Main draw
            this.vboBindHandle.invoke(this.cloudVBO, pipeline);
            this.vboDrawHandle.invoke(this.cloudVBO);
        } finally {
            poseStack.popMatrix();
            VRenderSystem.enableCull();
            VRenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            Compat.setModelOffset(0.0f, 0.0f, 0.0f);
        }
    }

    // ========================= //
    // Cloud mesh generation     //
    // ========================= //

    private Object buildCloudMesh(int centerCellX, int centerCellZ,
                                  double cloudY, CloudStatus cloudsType) {
        final float upFaceBrightness = 1.0f;
        final float xDirBrightness = 0.9f;
        final float downFaceBrightness = 0.7f;
        final float zDirBrightness = 0.8f;

        BufferBuilder bufferBuilder = Compat.beginCloudMesh();

        int cloudRange = Compat.getCloudRenderRange();
        int renderDistance = Mth.ceil(cloudRange / 12.0F);
        boolean insideClouds = this.prevCloudY == Y_INSIDE_CLOUDS;

        if (cloudsType == CloudStatus.FANCY) {
            for (int cellX = -renderDistance; cellX < renderDistance; ++cellX) {
                for (int cellZ = -renderDistance; cellZ < renderDistance; ++cellZ) {
                    int cellIdx = getWrappedIdx(centerCellX + cellX, centerCellZ + cellZ);
                    byte renderFaces = this.cloudRenderFaces[cellIdx];
                    int baseColor = this.cloudPixels[cellIdx];

                    float x = cellX * CELL_WIDTH;
                    float z = cellZ * CELL_WIDTH;

                    if ((renderFaces & DIR_POS_Y_BIT) != 0 && cloudY <= 0.0f) {
                        int color = multiplyRGB(baseColor, upFaceBrightness);
                        putVertex(bufferBuilder, x + CELL_WIDTH, CELL_HEIGHT, z + CELL_WIDTH, color);
                        putVertex(bufferBuilder, x + CELL_WIDTH, CELL_HEIGHT, z, color);
                        putVertex(bufferBuilder, x, CELL_HEIGHT, z, color);
                        putVertex(bufferBuilder, x, CELL_HEIGHT, z + CELL_WIDTH, color);
                    }

                    if ((renderFaces & DIR_NEG_Y_BIT) != 0 && cloudY >= -CELL_HEIGHT) {
                        int color = multiplyRGB(baseColor, downFaceBrightness);
                        putVertex(bufferBuilder, x, 0.0f, z + CELL_WIDTH, color);
                        putVertex(bufferBuilder, x, 0.0f, z, color);
                        putVertex(bufferBuilder, x + CELL_WIDTH, 0.0f, z, color);
                        putVertex(bufferBuilder, x + CELL_WIDTH, 0.0f, z + CELL_WIDTH, color);
                    }

                    if ((renderFaces & DIR_POS_X_BIT) != 0 && (x < 1.0f || insideClouds)) {
                        int color = multiplyRGB(baseColor, xDirBrightness);
                        putVertex(bufferBuilder, x + CELL_WIDTH, CELL_HEIGHT, z + CELL_WIDTH, color);
                        putVertex(bufferBuilder, x + CELL_WIDTH, 0.0f, z + CELL_WIDTH, color);
                        putVertex(bufferBuilder, x + CELL_WIDTH, 0.0f, z, color);
                        putVertex(bufferBuilder, x + CELL_WIDTH, CELL_HEIGHT, z, color);
                    }

                    if ((renderFaces & DIR_NEG_X_BIT) != 0 && (x > -1.0f || insideClouds)) {
                        int color = multiplyRGB(baseColor, xDirBrightness);
                        putVertex(bufferBuilder, x, CELL_HEIGHT, z, color);
                        putVertex(bufferBuilder, x, 0.0f, z, color);
                        putVertex(bufferBuilder, x, 0.0f, z + CELL_WIDTH, color);
                        putVertex(bufferBuilder, x, CELL_HEIGHT, z + CELL_WIDTH, color);
                    }

                    if ((renderFaces & DIR_POS_Z_BIT) != 0 && (z < 1.0f || insideClouds)) {
                        int color = multiplyRGB(baseColor, zDirBrightness);
                        putVertex(bufferBuilder, x, CELL_HEIGHT, z + CELL_WIDTH, color);
                        putVertex(bufferBuilder, x, 0.0f, z + CELL_WIDTH, color);
                        putVertex(bufferBuilder, x + CELL_WIDTH, 0.0f, z + CELL_WIDTH, color);
                        putVertex(bufferBuilder, x + CELL_WIDTH, CELL_HEIGHT, z + CELL_WIDTH, color);
                    }

                    if ((renderFaces & DIR_NEG_Z_BIT) != 0 && (z > -1.0f || insideClouds)) {
                        int color = multiplyRGB(baseColor, zDirBrightness);
                        putVertex(bufferBuilder, x + CELL_WIDTH, CELL_HEIGHT, z, color);
                        putVertex(bufferBuilder, x + CELL_WIDTH, 0.0f, z, color);
                        putVertex(bufferBuilder, x, 0.0f, z, color);
                        putVertex(bufferBuilder, x, CELL_HEIGHT, z, color);
                    }
                }
            }
        } else {
            // Fast clouds: single bottom face per cell
            for (int cellX = -renderDistance; cellX < renderDistance; ++cellX) {
                for (int cellZ = -renderDistance; cellZ < renderDistance; ++cellZ) {
                    int cellIdx = getWrappedIdx(centerCellX + cellX, centerCellZ + cellZ);
                    byte renderFaces = this.cloudRenderFaces[cellIdx];
                    int baseColor = this.cloudPixels[cellIdx];

                    float x = cellX * CELL_WIDTH;
                    float z = cellZ * CELL_WIDTH;

                    if ((renderFaces & DIR_NEG_Y_BIT) != 0) {
                        int color = multiplyRGB(baseColor, upFaceBrightness);
                        putVertex(bufferBuilder, x, 0.0f, z + CELL_WIDTH, color);
                        putVertex(bufferBuilder, x, 0.0f, z, color);
                        putVertex(bufferBuilder, x + CELL_WIDTH, 0.0f, z, color);
                        putVertex(bufferBuilder, x + CELL_WIDTH, 0.0f, z + CELL_WIDTH, color);
                    }
                }
            }
        }

        return Compat.finishCloudMesh(bufferBuilder);
    }

    private static void putVertex(BufferBuilder builder, float x, float y, float z, int color) {
        Compat.putCloudVertex(builder, x, y, z, color);
    }

    // ========================= //
    // Cloud texture loading     //
    // ========================= //

    private void loadCloudTexture() {
        try (var inputStream = Compat.openMcResource("textures/environment/clouds.png")) {
            NativeImage image = NativeImage.read(inputStream);
            int width = image.getWidth();
            int height = image.getHeight();

            if (width != height) {
                LOGGER.warn("[DH-VulkanMod] Cloud texture is not square ({}x{}), skipping", width, height);
                return;
            }

            this.cloudPixels = Compat.getCloudPixels(image);
            this.cloudGridWidth = width;
            this.cloudRenderFaces = computeRenderFaces();
            this.textureLoaded = true;
            LOGGER.info("[DH-VulkanMod] Cloud texture loaded: {}x{}", width, height);
        } catch (Exception e) {
            LOGGER.error("[DH-VulkanMod] Failed to load cloud texture", e);
        }
    }

    private byte[] computeRenderFaces() {
        byte[] renderFaces = new byte[this.cloudPixels.length];
        for (int z = 0; z < this.cloudGridWidth; z++) {
            for (int x = 0; x < this.cloudGridWidth; x++) {
                int idx = z * this.cloudGridWidth + x;
                int pixel = this.cloudPixels[idx];
                if (!hasAlpha(pixel)) continue;

                byte faces = (byte) (DIR_NEG_Y_BIT | DIR_POS_Y_BIT);
                if (pixel != getTexelWrapped(x - 1, z)) faces |= DIR_NEG_X_BIT;
                if (pixel != getTexelWrapped(x + 1, z)) faces |= DIR_POS_X_BIT;
                if (pixel != getTexelWrapped(x, z - 1)) faces |= DIR_NEG_Z_BIT;
                if (pixel != getTexelWrapped(x, z + 1)) faces |= DIR_POS_Z_BIT;
                renderFaces[idx] = faces;
            }
        }
        return renderFaces;
    }

    private int getTexelWrapped(int x, int z) {
        x = Math.floorMod(x, this.cloudGridWidth);
        z = Math.floorMod(z, this.cloudGridWidth);
        return this.cloudPixels[z * this.cloudGridWidth + x];
    }

    private int getWrappedIdx(int x, int z) {
        x = Math.floorMod(x, this.cloudGridWidth);
        z = Math.floorMod(z, this.cloudGridWidth);
        return z * this.cloudGridWidth + x;
    }

    private static boolean hasAlpha(int pixel) {
        return ((pixel >> 24) & 0xFF) > 1;
    }

    private static int multiplyRGB(int color, float factor) {
        int a = (color >> 24) & 0xFF;
        int r = (int) (((color >> 16) & 0xFF) * factor);
        int g = (int) (((color >> 8) & 0xFF) * factor);
        int b = (int) ((color & 0xFF) * factor);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ========================= //
    // Reflection resolution     //
    // ========================= //

    private void resolveReflection() {
        this.reflectionResolved = true;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Class<?> pipelineManagerClass = Class.forName("net.vulkanmod.render.PipelineManager");

            // Requires VM 0.6+ getCloudsPipeline()
            Method getCloudsPipelineMethod = pipelineManagerClass.getMethod("getCloudsPipeline");
            this.cloudsPipeline = (GraphicsPipeline) getCloudsPipelineMethod.invoke(null);
            LOGGER.info("[DH-VulkanMod] Cloud pipeline: using VM 0.6 getCloudsPipeline()");

            if (this.cloudsPipeline == null) {
                throw new RuntimeException("Cloud pipeline is null");
            }

            // VM 0.6 VBO API — convert to MethodHandles for performance
            Class<?> vboClass = Class.forName("net.vulkanmod.render.VBO");
            this.vboConstructorHandle = lookup.unreflectConstructor(
                    vboClass.getConstructor(boolean.class));
            this.vboBindHandle = lookup.unreflect(
                    vboClass.getMethod("bind", GraphicsPipeline.class));
            this.vboDrawHandle = lookup.unreflect(
                    vboClass.getMethod("draw"));

            // upload(MeshData) — name-based lookup because Fabric remaps class names
            for (Method m : vboClass.getMethods()) {
                if (m.getName().equals("upload") && m.getParameterCount() == 1) {
                    this.vboUploadHandle = lookup.unreflect(m);
                    break;
                }
            }
            if (this.vboUploadHandle == null) {
                throw new NoSuchMethodException("VBO.upload(MeshData) not found");
            }
            this.vboCloseHandle = lookup.unreflect(
                    vboClass.getMethod("close"));

            LOGGER.info("[DH-VulkanMod] Cloud renderer MethodHandles resolved (VM 0.6).");
        } catch (Exception e) {
            LOGGER.info("[DH-VulkanMod] VM 0.6 cloud API not available, custom cloud renderer disabled. ({})", e.getMessage());
            this.reflectionFailed = true;
        }
    }

    /** Reload cloud texture (called on resource reload). */
    public void resetBuffer() {
        if (this.cloudVBO != null) {
            try { this.vboCloseHandle.invoke(this.cloudVBO); } catch (Throwable ignored) {}
            this.cloudVBO = null;
        }
        this.needsRebuild = true;
    }

    /** Copy DH Mat4f into a reusable JOML Matrix4f (column-major). */
    private static void copyToJoml(Mat4f src, Matrix4f dst) {
        dst.set(src.m00, src.m10, src.m20, src.m30,
                src.m01, src.m11, src.m21, src.m31,
                src.m02, src.m12, src.m22, src.m32,
                src.m03, src.m13, src.m23, src.m33);
    }

    /** Full cleanup — release all resources. */
    public void cleanup() {
        resetBuffer();
        this.textureLoaded = false;
        this.cloudPixels = null;
        this.cloudRenderFaces = null;
        this.reflectionResolved = false;
        this.reflectionFailed = false;
        this.configRefreshCounter = 0;
    }
}
