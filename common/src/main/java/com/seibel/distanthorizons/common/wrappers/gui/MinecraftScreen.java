package com.seibel.distanthorizons.common.wrappers.gui;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import com.seibel.distanthorizons.core.config.gui.AbstractScreen;
import net.minecraft.client.Minecraft;
#if MC_VER >= MC_1_20_1
import net.minecraft.client.gui.GuiGraphics;
#endif
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.*;

public class MinecraftScreen
{
	public static Screen getScreen(Screen parent, AbstractScreen screen, String translationName)
	{
		return new ConfigScreenRenderer(parent, screen, translationName);
	}
	
	private static class ConfigScreenRenderer extends DhScreen
	{
		private final Screen parent;
		private ConfigListWidget configListWidget;
		private AbstractScreen screen;
		
		
		#if MC_VER < MC_1_19_2
		public static net.minecraft.network.chat.TranslatableComponent translate(String str, Object... args)
		{ return new net.minecraft.network.chat.TranslatableComponent(str, args); }
		#else
		public static net.minecraft.network.chat.MutableComponent translate(String str, Object... args)
		{ return net.minecraft.network.chat.Component.translatable(str, args); }
        #endif
		
		protected ConfigScreenRenderer(Screen parent, AbstractScreen screen, String translationName)
		{
			super(translate(translationName));
			#if MC_VER < MC_1_21_9
			screen.minecraftWindow = Minecraft.getInstance().getWindow().getWindow();
			#else
			screen.minecraftWindow = Minecraft.getInstance().getWindow().handle();
			#endif
			this.parent = parent;
			this.screen = screen;
		}
		
		@Override
		protected void init()
		{
			super.init(); // Init Minecraft's screen
			Window mcWindow = this.minecraft.getWindow();
			this.screen.width = mcWindow.getWidth();
			this.screen.height = mcWindow.getHeight();
			this.screen.scaledWidth = this.width;
			this.screen.scaledHeight = this.height;
			this.screen.init(); // Init our own config screen
			
			this.configListWidget = new ConfigListWidget(this.minecraft, this.width, this.height, 0, 0, 25); // Select the area to tint
			
			#if MC_VER < MC_1_20_6 // no background is rendered in MC 1.20.6+
			if (this.minecraft != null && this.minecraft.level != null) // Check if in game
			{
				this.configListWidget.setRenderBackground(false); // Disable from rendering
			}
			#endif
			
			this.addWidget(this.configListWidget); // Add the tint to the things to be rendered
		}
		
		@Override
        #if MC_VER < MC_1_20_1
		public void render(PoseStack matrices, int mouseX, int mouseY, float delta)
        #else
		public void render(GuiGraphics matrices, int mouseX, int mouseY, float delta)
        #endif
		{
			#if MC_VER < MC_1_20_2
			this.renderBackground(matrices); // Render background
			#elif MC_VER < MC_1_21_6
			this.renderBackground(matrices, mouseX, mouseY, delta); // Render background
			#else
			// background blur is already being rendered, rendering again causes the game to crash
			#endif
			
			this.configListWidget.render(matrices, mouseX, mouseY, delta); // Renders the items in the render list (currently only used to tint background darker)
			
			this.screen.mouseX = mouseX;
			this.screen.mouseY = mouseY;
			this.screen.render(delta); // Render everything on the main screen
			
			super.render(matrices, mouseX, mouseY, delta); // Render the vanilla stuff (currently only used for the background and tint)
		}
		
		#if MC_VER <= MC_1_21_10
		@Override
		public void resize(Minecraft mc, int width, int height)
		#else
		@Override
		public void resize(int width, int height)
		#endif
		{
			// Resize Minecraft's screen
			#if MC_VER <= MC_1_21_10
			super.resize(mc, width, height);
			#else
			super.resize(width, height);
			#endif
			
			
			Window mcWindow = this.minecraft.getWindow();
			this.screen.width = mcWindow.getWidth();
			this.screen.height = mcWindow.getHeight();
			this.screen.scaledWidth = this.width;
			this.screen.scaledHeight = this.height;
			this.screen.onResize(); // Resize our screen
		}
		
		@Override
		public void tick()
		{
			super.tick(); // Tick Minecraft's screen
			this.screen.tick(); // Tick our screen
			if (this.screen.close) // If we decide to close the screen, then actually close the screen
			{
				this.onClose();
			}
		}
		
		@Override
		public void onClose()
		{
			this.screen.onClose(); // Close our screen
			Objects.requireNonNull(this.minecraft).setScreen(this.parent); // Goto the parent screen
		}
		
		@Override
		public void onFilesDrop(@NotNull List<Path> files)
		{ this.screen.onFilesDrop(files); }
		
		// For checking if it should close when you press the escape key
		@Override
		public boolean shouldCloseOnEsc()
		{ return this.screen.shouldCloseOnEsc; }
		
	}
	
	public static class ConfigListWidget extends ContainerObjectSelectionList
	{
		public ConfigListWidget(Minecraft minecraftClient, int canvasWidth, int canvasHeight, int topMargin, int botMargin, int itemSpacing)
		{
			#if MC_VER < MC_1_20_4
			super(minecraftClient, canvasWidth, canvasHeight, topMargin, canvasHeight - botMargin, itemSpacing);
			#else
			super(minecraftClient, canvasWidth, canvasHeight - (topMargin + botMargin), topMargin, itemSpacing);
			#endif
			this.centerListVertically = false;
		}
		
	}
	
}
