package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.BlitRenderState;
import net.minecraft.client.gui.render.state.GuiItemRenderState;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.vulkanmod.render.engine.VkRenderPass;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {

    @Shadow @Final private GuiRenderState renderState;
    @Shadow private @Nullable GpuTextureView itemsAtlasView;

    @Overwrite
    private void submitBlitFromItemAtlas(GuiItemRenderState guiItemRenderState, float u, float v, int size, int atlasSize) {
        v = 1.0f - v;
        float u1 = u + (float)size / atlasSize;
        float v1 = v + (float)(size) / atlasSize;
        this.renderState
                .submitBlitToCurrentLayer(
                        new BlitRenderState(
                                RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
                                TextureSetup.singleTexture(this.itemsAtlasView),
                                guiItemRenderState.pose(),
                                guiItemRenderState.x(),
                                guiItemRenderState.y(),
                                guiItemRenderState.x() + 16,
                                guiItemRenderState.y() + 16,
                                u,
                                u1,
                                v,
                                v1,
                                -1,
                                guiItemRenderState.scissorArea(),
                                null
                        )
                );
    }

    @Redirect(method = "executeDraw", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setIndexBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/vertex/VertexFormat$IndexType;)V"))
    private void removeIndexBuffer(RenderPass instance, GpuBuffer gpuBuffer, VertexFormat.IndexType indexType) {
        // This draw method forces quad index buffer, not allowing other draw modes
        // Not binding it here will allow for a proper index  selection in lower level methods
    }

    @Redirect(method = "executeDraw", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;drawIndexed(IIII)V"))
    private void useVertexCount(RenderPass renderPass, int baseVertex, int firstIndex, int indexCount, int instanceCount) {
        // For the same reason here we need to use vertexCount instead of indexCount

        VkRenderPass vkRenderPass = (VkRenderPass) renderPass;
        if (vkRenderPass.getPipeline().getVertexFormatMode() != VertexFormat.Mode.TRIANGLES) {
            int vertexCount = indexCount * 2 / 3;
            renderPass.drawIndexed(baseVertex, 0, vertexCount, 1);
        }
        else {
            renderPass.drawIndexed(baseVertex, 0, indexCount, 1);
        }
    }

}
