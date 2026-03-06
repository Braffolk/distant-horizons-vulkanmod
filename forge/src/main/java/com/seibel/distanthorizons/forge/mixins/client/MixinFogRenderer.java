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

package com.seibel.distanthorizons.forge.mixins.client;

import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
#if MC_VER < MC_1_17_1
import net.minecraft.world.level.material.FluidState;
#else
import net.minecraft.world.level.material.FogType;
#endif

@Mixin(FogRenderer.class)
public class MixinFogRenderer
{
	
	// Using this instead of Float.MAX_VALUE because Sodium don't like it.
	private static final float A_REALLY_REALLY_BIG_VALUE = 420694206942069.F;
	private static final float A_EVEN_LARGER_VALUE = 42069420694206942069.F;
	
	@Inject(at = @At("RETURN"),
			method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
			remap = #if MC_VER == MC_1_17_1 || MC_VER == MC_1_18_2 false #else true #endif ) // Remap messiness due to this being weird in forge
	private static void disableSetupFog(Camera camera, FogMode fogMode, float f, boolean bl, float partTick, CallbackInfo callback)
	{
		#if MC_VER < MC_1_17_1
		FluidState fluidState = camera.getFluidInCamera();
		boolean cameraNotInFluid = fluidState.isEmpty();
		#else
		FogType fogTypes = camera.getFluidInCamera();
		boolean cameraNotInFluid = fogTypes == FogType.NONE;
		#endif
		
		
		Entity entity = camera.getEntity();
		boolean isSpecialFog = (entity instanceof LivingEntity) && ((LivingEntity) entity).hasEffect(MobEffects.BLINDNESS);
		if (!isSpecialFog 
			&& cameraNotInFluid 
			&& fogMode == FogMode.FOG_TERRAIN
			&& !SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class).isFogStateSpecial()
			&& !Config.Client.Advanced.Graphics.Fog.enableVanillaFog.get())
		{
			#if MC_VER < MC_1_17_1
			RenderSystem.fogStart(A_REALLY_REALLY_BIG_VALUE);
			RenderSystem.fogEnd(A_EVEN_LARGER_VALUE);
			#else
			RenderSystem.setShaderFogStart(A_REALLY_REALLY_BIG_VALUE);
			RenderSystem.setShaderFogEnd(A_EVEN_LARGER_VALUE);
			#endif
			
			ClientApi.RENDER_STATE.vanillaFogEnabled = false;
		}
		else
		{
			ClientApi.RENDER_STATE.vanillaFogEnabled = true;
		}
	}
	
}
