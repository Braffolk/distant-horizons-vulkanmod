package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.BlitRenderState;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(PictureInPictureRenderer.class)
public class PictureInPictureRendererM<T extends PictureInPictureRenderState> {

    @Shadow
    private @Nullable GpuTextureView textureView;

    @Overwrite
    public void blitTexture(T pictureInPictureRenderState, GuiRenderState guiRenderState) {
        guiRenderState.submitBlitToCurrentLayer(
                new BlitRenderState(
                        RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
                        TextureSetup.singleTexture(this.textureView),
                        pictureInPictureRenderState.pose(),
                        pictureInPictureRenderState.x0(),
                        pictureInPictureRenderState.y0(),
                        pictureInPictureRenderState.x1(),
                        pictureInPictureRenderState.y1(),
                        0.0F,
                        1.0F,
                        0.0F,
                        1.0F,
                        -1,
                        pictureInPictureRenderState.scissorArea(),
                        null
                )
        );
    }
}
