package net.vulkanmod.config.gui.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2f;

import java.util.List;

public abstract class GuiRenderer {

    public static Minecraft minecraft;
    public static GuiGraphics guiGraphics;
    public static PoseStack pose;
    public static BufferBuilder bufferBuilder;

    public static void enableScissor(int i, int j, int k, int l) {
        guiGraphics.enableScissor(i, j, k, l);
    }

    public static void disableScissor() {
        guiGraphics.disableScissor();
    }

    public static void fillBox(int x0, int y0, int width, int height, int color) {
        fill(x0, y0, x0 + width, y0 + height, 0, color);
    }

    public static void fill(int x0, int y0, int x1, int y1, int color) {
        fill(x0, y0, x1, y1, 0, color);
    }

    public static void fill(int x0, int y0, int x1, int y1, int z, int color) {
        guiGraphics.fill(x0, y0, x1, y1, color);
    }

    public static void fillGradient(int x0, int y0, int x1, int y1, int color1, int color2) {
        fillGradient(x0, y0, x1, y1, 0, color1, color2);
    }

    public static void fillGradient(int x0, int y0, int x1, int y1, int z, int color1, int color2) {
        guiGraphics.fillGradient(x0, y0, x1, y1, color1, color2);
    }

    public static void renderBoxBorder(int x0, int y0, int width, int height, int borderWidth, int color) {
        renderBorder(x0, y0, x0 + width, y0 + height, borderWidth, color);
    }

    public static void renderBorder(int x0, int y0, int x1, int y1, int width, int color) {
        GuiRenderer.fill(x0, y0, x1, y0 + width, color);
        GuiRenderer.fill(x0, y1 - width, x1, y1, color);

        GuiRenderer.fill(x0, y0 + width, x0 + width, y1 - width, color);
        GuiRenderer.fill(x1 - width, y0 + width, x1, y1 - width, color);
    }

    public static void drawString(Font font, Component component, int x, int y, int color) {
        drawString(font, component.getVisualOrderText(), x, y, color);
    }

    public static void drawString(Font font, FormattedCharSequence formattedCharSequence, int x, int y, int color) {
        guiGraphics.drawString(font, formattedCharSequence, x, y, color);
    }

    public static void drawString(Font font, Component component, int x, int y, int color, boolean shadow) {
        drawString(font, component.getVisualOrderText(), x, y, color, shadow);
    }

    public static void drawString(Font font, FormattedCharSequence formattedCharSequence, int x, int y, int color, boolean shadow) {
        guiGraphics.drawString(font, formattedCharSequence, x, y, color, shadow);
    }

    public static void drawCenteredString(Font font, Component component, int x, int y, int color) {
        FormattedCharSequence formattedCharSequence = component.getVisualOrderText();
        guiGraphics.drawString(font, formattedCharSequence, x - font.width(formattedCharSequence) / 2, y, color);
    }

    public static int getMaxTextWidth(Font font, List<FormattedCharSequence> list) {
        int maxWidth = 0;
        for (var text : list) {
            int width = font.width(text);
            if (width > maxWidth) {
                maxWidth = width;
            }
        }
        return maxWidth;
    }

    public static void submitPolygon(RenderPipeline renderPipeline, TextureSetup textureSetup, float[][] vertices, int color) {
        guiGraphics.guiRenderState.submitGuiElement(
                new PolygonRenderState(
                        renderPipeline, textureSetup, new Matrix3x2f(), vertices, color, guiGraphics.scissorStack.peek()
                )
        );
    }
}
