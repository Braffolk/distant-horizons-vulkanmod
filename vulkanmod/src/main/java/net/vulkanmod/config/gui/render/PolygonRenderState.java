package net.vulkanmod.config.gui.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;

public record PolygonRenderState (
        RenderPipeline pipeline,
        TextureSetup textureSetup,
        Matrix3x2f pose,
        float[][] vertices,
        int col,
        @Nullable ScreenRectangle scissorArea,
        @Nullable ScreenRectangle bounds
) implements GuiElementRenderState {

    public PolygonRenderState(
            RenderPipeline renderPipeline,
            TextureSetup textureSetup,
            Matrix3x2f pose,
            float[][] vertices,
            int color,
            @Nullable ScreenRectangle screenRectangle
    ) {
        this(renderPipeline, textureSetup, pose, vertices, color, screenRectangle,
             getBounds(vertices, pose, screenRectangle));
    }

    @Override
    public void buildVertices(VertexConsumer vertexConsumer) {
        for (float[] vertex : vertices) {
            float x = vertex[0];
            float y = vertex[1];
            vertexConsumer.addVertexWith2DPose(this.pose(), x, y)
                          .setColor(this.col);
        }
    }

    @Nullable
    private static ScreenRectangle getBounds(float[][] vertices, Matrix3x2f matrix3x2f, @Nullable ScreenRectangle screenRectangle) {
        float x0 = vertices[0][0];
        float x1 = vertices[0][0];
        float y0 = vertices[0][1];
        float y1 = vertices[0][1];

        for (float[] vertex : vertices) {
            float x = vertex[0];
            float y = vertex[1];

            if (x < x0) {
                x0 = x;
            }
            if (x > x1) {
                x1 = x;
            }

            if (y < y0) {
                y0 = y;
            }
            if (y > y1) {
                y1 = y;
            }
        }

        ScreenRectangle screenRectangle2 = new ScreenRectangle((int) x0, (int) y0, (int) (x1 - x0), (int) (y1 - y0)).transformMaxBounds(matrix3x2f);
        return screenRectangle != null ? screenRectangle.intersection(screenRectangle2) : screenRectangle2;
    }
}

