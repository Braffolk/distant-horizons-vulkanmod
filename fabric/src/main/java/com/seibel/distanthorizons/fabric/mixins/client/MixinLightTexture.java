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

package com.seibel.distanthorizons.fabric.mixins.client;

import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import net.minecraft.client.renderer.LightTexture;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

#if MC_VER < MC_1_21_3
import com.mojang.blaze3d.platform.NativeImage;
#elif MC_VER < MC_1_21_5
import com.mojang.blaze3d.pipeline.TextureTarget;
#else
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
#endif

@Mixin(LightTexture.class)
public class MixinLightTexture
{
	 
	#if MC_VER < MC_1_21_3
	@Shadow 
	@Final
	private NativeImage lightPixels;
	#elif MC_VER < MC_1_21_5
	@Shadow
	@Final
	private TextureTarget target;
	#else
	@Shadow
	@Final
	private GpuTexture texture;
	#endif
	
	@Unique
	private MinecraftRenderWrapper renderWrapper = null;
	
	
	
	@Inject(method = "updateLightTexture(F)V", at = @At("RETURN"))
	public void updateLightTexture(float partialTicks, CallbackInfo ci)
	{
		IMinecraftClientWrapper mc = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
		if (mc == null)
		{
			return;
		}
		
		IClientLevelWrapper clientLevel = mc.getWrappedClientLevel();
		if (clientLevel == null)
		{
			return;
		}
		
		// lazy initialization to make sure we don't call this too early
		if (this.renderWrapper == null)
		{
			this.renderWrapper = (MinecraftRenderWrapper)SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
		}
		
		
		#if MC_VER < MC_1_21_3
		this.renderWrapper.updateLightmap(this.lightPixels, clientLevel);
		#elif MC_VER < MC_1_21_5
		this.renderWrapper.setLightmapId(this.target.getColorTextureId(), clientLevel);
		#else
		GlTexture glTexture = (GlTexture) this.texture;
		this.renderWrapper.setLightmapId(glTexture.glId(), clientLevel);
		#endif
	}
	
}
