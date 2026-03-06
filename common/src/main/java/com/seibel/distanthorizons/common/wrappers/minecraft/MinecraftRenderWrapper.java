/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.common.wrappers.minecraft;

import java.awt.Color;
import java.lang.invoke.MethodHandles;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.seibel.distanthorizons.api.enums.config.EDhApiLodShading;
import com.seibel.distanthorizons.common.wrappers.McObjectConverter;
import com.seibel.distanthorizons.common.wrappers.misc.LightMapWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;

import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.ColorUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.ILightMapWrapper;

#if MC_VER < MC_1_17_1
#elif MC_VER < MC_1_21_6
import net.minecraft.client.renderer.FogRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
#else
import net.minecraft.client.renderer.fog.FogRenderer;
#endif

#if MC_VER < MC_1_19_4
import org.joml.Matrix4f;
import org.joml.Vector3f;
#else
#endif

import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.AbstractOptifineAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IDimensionTypeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.util.math.Vec3d;
import com.seibel.distanthorizons.core.util.math.Vec3f;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffects;

import net.minecraft.world.phys.Vec3;
import com.seibel.distanthorizons.core.logging.DhLogger;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector4f;

#if MC_VER < MC_1_17_1
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FluidState;
import org.lwjgl.opengl.GL15;
#else
import net.minecraft.world.level.material.FogType;
#endif

#if MC_VER >= MC_1_21_5
import com.mojang.blaze3d.opengl.GlTexture;
#else
#endif

#if MC_VER <= MC_1_21_10
#else
import net.minecraft.world.attribute.EnvironmentAttributes;
#endif

/**
 * A singleton that contains everything
 * related to rendering in Minecraft.
 */
public class MinecraftRenderWrapper implements IMinecraftRenderWrapper
{
	public static final MinecraftRenderWrapper INSTANCE = new MinecraftRenderWrapper();
	
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static final Minecraft MC = Minecraft.getInstance();
	
	/** 
	 * In the case of immersive portals multiple levels may be active at once, causing conflicting lightmaps. <br> 
	 * Requiring the use of multiple {@link LightMapWrapper}.
	 */
	public ConcurrentHashMap<IDimensionTypeWrapper, LightMapWrapper> lightmapByDimensionType = new ConcurrentHashMap<>();
	
	/** 
	 * Holds the render buffer that should be used when displaying levels to the screen.
	 * This is used for Optifine shader support so we can render directly to Optifine's level frame buffer.
	 */
	public int finalLevelFrameBufferId = -1;
	
	public boolean colorTextureCastFailLogged = false;
	public boolean depthTextureCastFailLogged = false;
	
	#if MC_VER < MC_1_21_6
	#else
	private static FogRenderer mcFogRenderer = null;
	#endif
	
	
	
	//=========//
	// methods //
	//=========//
	
	@Override
	public Vec3f getLookAtVector()
	{
		#if MC_VER <= MC_1_21_10
		Camera camera = MC.gameRenderer.getMainCamera();
		return new Vec3f(camera.getLookVector().x(), camera.getLookVector().y(), camera.getLookVector().z());
		#else
		Camera camera = MC.gameRenderer.getMainCamera();
		return new Vec3f(camera.forwardVector().x(), camera.forwardVector().y(), camera.forwardVector().z());
		#endif
	}
	
	/** 
	 * Unless you really need to know if the player is blind, 
	 * use {@link MinecraftRenderWrapper#isFogStateSpecial()} or {@link IMinecraftRenderWrapper#isFogStateSpecial()} instead 
	 */
	@Override
	public boolean playerHasBlindingEffect()
	{
		if (MC.player == null)
		{
			return false;
		}
		else if (MC.player.getActiveEffectsMap() == null)
		{
			return false;
		}
		else
		{
			return MC.player.getActiveEffectsMap().get(MobEffects.BLINDNESS) != null
				#if MC_VER >= MC_1_19_2
					|| MC.player.getActiveEffectsMap().get(MobEffects.DARKNESS) != null // Deep dark effect
				#endif
					;
		}
	}
	
	@Override
	public Vec3d getCameraExactPosition()
	{
		Camera camera = MC.gameRenderer.getMainCamera();
		#if MC_VER <= MC_1_21_10
		Vec3 projectedView = camera.getPosition();
		#else
		Vec3 projectedView = camera.position();
		#endif
		
		return new Vec3d(projectedView.x, projectedView.y, projectedView.z);
	}
	
	@Override
	public float getPartialTickTime()
	{
		#if MC_VER < MC_1_21_1
		return MC.getFrameTime();
		#elif MC_VER < MC_1_21_3
		return MC.getTimer().getRealtimeDeltaTicks();
		#else
		return MC.deltaTracker.getRealtimeDeltaTicks();
		#endif
	}
	
	@Override
	public Color getFogColor(float partialTicks)
	{
		#if MC_VER < MC_1_17_1
		float[] colorValues = new float[4];
		GL15.glGetFloatv(GL15.GL_FOG_COLOR, colorValues);
		return new Color(
				Math.max(0f, Math.min(colorValues[0], 1f)), // r
				Math.max(0f, Math.min(colorValues[1], 1f)), // g
				Math.max(0f, Math.min(colorValues[2], 1f)), // b
				Math.max(0f, Math.min(colorValues[3], 1f))  // a
		);
		#elif MC_VER < MC_1_21_3
		FogRenderer.setupColor(MC.gameRenderer.getMainCamera(), partialTicks, MC.level, 1, MC.gameRenderer.getDarkenWorldAmount(partialTicks));
		float[] colorValues = RenderSystem.getShaderFogColor();
		return new Color(
				Math.max(0f, Math.min(colorValues[0], 1f)), // r
				Math.max(0f, Math.min(colorValues[1], 1f)), // g
				Math.max(0f, Math.min(colorValues[2], 1f)), // b
				Math.max(0f, Math.min(colorValues[3], 1f))  // a
		);
		#elif MC_VER < MC_1_21_6
		Vector4f colorValues = FogRenderer.computeFogColor(MC.gameRenderer.getMainCamera(), partialTicks, MC.level, 1, MC.gameRenderer.getDarkenWorldAmount(partialTicks));
		return new Color(
				Math.max(0f, Math.min(colorValues.x, 1f)), // r
				Math.max(0f, Math.min(colorValues.y, 1f)), // g
				Math.max(0f, Math.min(colorValues.z, 1f)), // b
				Math.max(0f, Math.min(colorValues.w, 1f))  // a
		);
		#elif MC_VER <= MC_1_21_10
		if (mcFogRenderer == null)
		{
			mcFogRenderer = new FogRenderer();
		}
		
		if (MC.level == null)
		{
			// shouldn't happen, but just in case
			return Color.white;
		}
		
		boolean isFoggy = 
				MC.level.effects().isFoggyAt(
						MC.gameRenderer.getMainCamera().getBlockPosition().getX(),
						MC.gameRenderer.getMainCamera().getBlockPosition().getZ()) 
					|| MC.gui.getBossOverlay().shouldCreateWorldFog();
		Vector4f colorValues = mcFogRenderer.setupFog(MC.gameRenderer.getMainCamera(), MC.options.getEffectiveRenderDistance(), isFoggy, MC.deltaTracker, MC.gameRenderer.getDarkenWorldAmount(MC.deltaTracker.getGameTimeDeltaPartialTick(true)), MC.level);
		return new Color(
				Math.max(0f, Math.min(colorValues.x, 1f)), // r
				Math.max(0f, Math.min(colorValues.y, 1f)), // g
				Math.max(0f, Math.min(colorValues.z, 1f)), // b
				Math.max(0f, Math.min(colorValues.w, 1f))  // a
		);
		#else
			
		if (mcFogRenderer == null)
		{
			mcFogRenderer = new FogRenderer();
		}
		
		if (MC.level == null)
		{
			// shouldn't happen, but just in case
			return Color.white;
		}
		
		Vector4f colorValues = mcFogRenderer.setupFog(
			MC.gameRenderer.getMainCamera(),
			MC.options.getEffectiveRenderDistance(),
			MC.deltaTracker,
			MC.gameRenderer.getDarkenWorldAmount(MC.deltaTracker.getGameTimeDeltaPartialTick(true)),
			MC.level);
		return new Color(
				Math.max(0f, Math.min(colorValues.x, 1f)), // r
				Math.max(0f, Math.min(colorValues.y, 1f)), // g
				Math.max(0f, Math.min(colorValues.z, 1f)), // b
				Math.max(0f, Math.min(colorValues.w, 1f))  // a
		);
		#endif
	}
	
	@Override
	public Color getSkyColor()
	{
		if (MC.level.dimensionType().hasSkyLight())
		{
			#if MC_VER < MC_1_17_1
			float frameTime = this.getPartialTickTime();
			Vec3 colorValues = MC.level.getSkyColor(MC.gameRenderer.getMainCamera().getBlockPosition(), frameTime);
			return new Color((float) colorValues.x, (float) colorValues.y, (float) colorValues.z);
			#elif MC_VER < MC_1_21_3
			float frameTime = this.getPartialTickTime();
			Vec3 colorValues = MC.level.getSkyColor(MC.gameRenderer.getMainCamera().getPosition(), frameTime);
			return new Color((float) colorValues.x, (float) colorValues.y, (float) colorValues.z);
			#elif MC_VER <= MC_1_21_10
			float frameTime = this.getPartialTickTime();
			int argbColorInt = MC.level.getSkyColor(MC.gameRenderer.getMainCamera().getPosition(), frameTime);
			return ColorUtil.toColorObjARGB(argbColorInt);
			#else
			int argbColor = MC.level.environmentAttributes().getValue(EnvironmentAttributes.SKY_COLOR, BlockPos.ZERO);
			return new Color(ColorUtil.getRed(argbColor), ColorUtil.getGreen(argbColor), ColorUtil.getBlue(argbColor), 255 /* ignore alpha since DH clouds don't render correctly with transparency */);
			#endif
		}
		else
		{
			return new Color(0, 0, 0);
		}
	}
	
	@Override
	public double getFov(float partialTicks) { return MC.gameRenderer.getFov(MC.gameRenderer.getMainCamera(), partialTicks, true); }
	
	/** Measured in chunks */
	@Override
	public int getRenderDistance()
	{
		#if MC_VER <= MC_1_17_1
		return MC.options.renderDistance;
		#else
		return MC.options.getEffectiveRenderDistance();
		#endif
	}
	
	@Override
	public int getFrameLimit()
	{
		#if MC_VER <= MC_1_18_2
		return MC.options.framerateLimit;
		#else
		return MC.options.framerateLimit().get();
		#endif
	}
	
	protected RenderTarget getRenderTarget() { return MC.getMainRenderTarget(); }
	
	@Override
	public boolean mcRendersToFrameBuffer()
	{
		#if MC_VER < MC_1_21_5
		return true;
		#else
		return false;
		#endif
	}
	
	@Override
	public boolean runningLegacyOpenGL()
	{
		#if MC_VER <= MC_1_16_5
		return true;
		#else
		return false;
		#endif
	}
	
	@Override
	public int getTargetFramebuffer()
	{
		// used so we can access the framebuffer shaders end up rendering to
		if (AbstractOptifineAccessor.optifinePresent())
		{
			return this.finalLevelFrameBufferId;
		}
		
		#if MC_VER < MC_1_21_5
		return this.getRenderTarget().frameBufferId;
		#else
		// MC renders to a texture and then directly to the default FBO now
		// we need to draw to their texture instead of the FBO
		return 0; // 0 is the ID for the default frame buffer
		#endif
	}
	
	@Override
	public void clearTargetFrameBuffer() { this.finalLevelFrameBufferId = -1; }
	
	@Override
	public int getDepthTextureId()
	{
		#if MC_VER < MC_1_21_5
		return this.getRenderTarget().getDepthTextureId();
		#else
		try
		{		
			GlTexture glTexture = (GlTexture) this.getRenderTarget().getDepthTexture();
			if (glTexture == null)
			{
				// shouldn't happen, but just in case
				return -1;
			}

			return glTexture.glId();
			
		}
		catch (Exception e)
		{
			// only log this error once per session
			if (!this.depthTextureCastFailLogged)
			{
				this.depthTextureCastFailLogged = true;
				LOGGER.error("Unable to cast render Target depth texture to GlTexture. MC or a rendering mod may have changed the object type.", e);
			}
			return -1;
		}
		#endif
	}
	@Override
	public int getColorTextureId() 
	{
		#if MC_VER < MC_1_21_5
		return this.getRenderTarget().getColorTextureId();
		#else
		try
		{
			GlTexture glTexture = (GlTexture) this.getRenderTarget().getColorTexture();
			if (glTexture == null)
			{
				// shouldn't happen, but just in case
				return -1;
			}
			
			return glTexture.glId();
		}
		catch (Exception e)
		{
			// only log this error once per session
			if (!this.colorTextureCastFailLogged)
			{
				this.colorTextureCastFailLogged = true;
				LOGGER.error("Unable to cast render Target color texture to GlTexture. MC or a rendering mod may have changed the object type.", e);
			}
			return -1;
		}
		#endif
	}
	
	@Override
	public int getTargetFramebufferViewportWidth()
	{
		#if MC_VER < MC_1_21_9
		return this.getRenderTarget().viewWidth;
		#else
		return this.getRenderTarget().width;
		#endif
	}
	
	@Override
	public int getTargetFramebufferViewportHeight()
	{
		#if MC_VER < MC_1_21_9
		return this.getRenderTarget().viewHeight;
		#else
		return this.getRenderTarget().height;
		#endif
	}
	
	@Override
	public ILightMapWrapper getLightmapWrapper(@NotNull ILevelWrapper level) { return this.lightmapByDimensionType.get(level.getDimensionType()); }
	
	@Override
	public boolean isFogStateSpecial()
	{
		#if MC_VER < MC_1_17_1
		Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		FluidState fluidState = camera.getFluidInCamera();
		Entity entity = camera.getEntity();
		boolean isBlind = this.playerHasBlindingEffect();
			isBlind |= fluidState.is(FluidTags.WATER);
			isBlind |= fluidState.is(FluidTags.LAVA);
		return isBlind;
		#else
		boolean isBlind = this.playerHasBlindingEffect();
		return MC.gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE || isBlind;
		#endif
	}
	
	/** 
	 * It's better to use {@link MinecraftRenderWrapper#setLightmapId(int, IClientLevelWrapper)} if possible,
	 * however old MC versions don't support it.
	 */
	public void updateLightmap(NativeImage lightPixels, IClientLevelWrapper level)
	{
		// Using ClientLevelWrapper as the key would be better, but we don't have a consistent way to create the same
		// object for the same MC level and/or the same hash,
		// so this will have to do for now
		IDimensionTypeWrapper dimensionType = level.getDimensionType();
		
		LightMapWrapper wrapper = this.lightmapByDimensionType.computeIfAbsent(dimensionType, (dimType) -> new LightMapWrapper());
		wrapper.uploadLightmap(lightPixels);
	}
	public void setLightmapId(int tetxureId, IClientLevelWrapper level)
	{
		// Using ClientLevelWrapper as the key would be better, but we don't have a consistent way to create the same
		// object for the same MC level and/or the same hash,
		// so this will have to do for now
		IDimensionTypeWrapper dimensionType = level.getDimensionType();

		LightMapWrapper wrapper = this.lightmapByDimensionType.computeIfAbsent(dimensionType, (dimType) -> new LightMapWrapper());
		wrapper.setLightmapId(tetxureId);
	}
	
	@Override
	public float getShade(EDhDirection lodDirection)
	{
		EDhApiLodShading lodShading = Config.Client.Advanced.Graphics.Quality.lodShading.get();
		switch (lodShading)
		{
			default:
			case AUTO:
				if (MC.level != null)
				{
					Direction mcDir = McObjectConverter.Convert(lodDirection);
					return MC.level.getShade(mcDir, true);
				}
				else
				{
					return 0.0f;
				}
			
			case ENABLED:
				switch (lodDirection)
				{
					case DOWN:
						return 0.5F;
					default:
					case UP:
						return 1.0F;
					case NORTH:
					case SOUTH:
						return 0.8F;
					case WEST:
					case EAST:
						return 0.6F;
				}
			
			case DISABLED:
				return 1.0F;
		}
	}
	
	
	
}
