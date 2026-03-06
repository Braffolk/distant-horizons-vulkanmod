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

#if MC_VER < MC_1_19_4
import com.seibel.distanthorizons.core.util.math.Mat4f;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import org.lwjgl.opengl.GL32;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
#elif MC_VER < MC_1_21_6
import com.seibel.distanthorizons.core.util.math.Mat4f;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
#elif MC_VER < MC_1_21_9
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.seibel.distanthorizons.core.util.math.Mat4f;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
#else
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
#endif


import com.seibel.distanthorizons.common.wrappers.McObjectConverter;
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

import com.seibel.distanthorizons.core.logging.DhLogger;



@Mixin(LevelRenderer.class)
public class MixinLevelRenderer
{
    @Shadow
    private ClientLevel level;
	
	@Unique
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	
	#if MC_VER < MC_1_17_1
    @Inject(at = @At("HEAD"),
			method = "renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDD)V",
			cancellable = true)
	private void renderChunkLayer(RenderType renderType, PoseStack matrixStackIn, double xIn, double yIn, double zIn, CallbackInfo callback)
	#elif MC_VER < MC_1_19_4
    @Inject(at = @At("HEAD"),
            method = "renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLcom/mojang/math/Matrix4f;)V",
            cancellable = true)
    private void renderChunkLayer(RenderType renderType, PoseStack modelViewMatrixStack, double cameraXBlockPos, double cameraYBlockPos, double cameraZBlockPos, Matrix4f projectionMatrix, CallbackInfo callback)
	#elif MC_VER < MC_1_20_2
    @Inject(at = @At("HEAD"),
            method = "renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
            cancellable = true)
    private void renderChunkLayer(RenderType renderType, PoseStack modelViewMatrixStack, double cameraXBlockPos, double cameraYBlockPos, double cameraZBlockPos, Matrix4f projectionMatrix, CallbackInfo callback)
    #elif MC_VER < MC_1_20_6
	@Inject(at = @At("HEAD"),
			method = "Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
			cancellable = true)
	private void renderChunkLayer(RenderType renderType, PoseStack modelViewMatrixStack, double camX, double camY, double camZ, Matrix4f projectionMatrix, CallbackInfo callback)
	#elif MC_VER < MC_1_21_6
	@Inject(at = @At("HEAD"),
			method = "Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
			cancellable = true)
	private void renderChunkLayer(RenderType renderType, double x, double y, double z, Matrix4f projectionMatrix, Matrix4f frustumMatrix, CallbackInfo callback)
	#elif MC_VER < MC_1_21_9
	@Inject(at = @At("HEAD"), method = "prepareChunkRenders", cancellable = true)
	private void prepareChunkRenders(Matrix4fc projectionMatrix, double d, double e, double f, CallbackInfoReturnable<ChunkSectionsToRender> callback)
	#else
	@Inject(at = @At("HEAD"), method = "renderLevel")
	private void renderLevel(
			GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker,
			boolean renderBlockOutline, Camera camera,
			Matrix4f positionMatrix, Matrix4f projectionMatrix, Matrix4f idkMatrix, GpuBufferSlice gpuBufferSlice,
			Vector4f skyColor, boolean thinFog, CallbackInfo callback)
    #endif
    {
		#if MC_VER == MC_1_16_5
	    // get the matrices from the OpenGL fixed pipeline
	    float[] mcProjMatrixRaw = new float[16];
	    GL32.glGetFloatv(GL32.GL_PROJECTION_MATRIX, mcProjMatrixRaw);
	    ClientApi.RENDER_STATE.mcProjectionMatrix = new Mat4f(mcProjMatrixRaw);
	    ClientApi.RENDER_STATE.mcProjectionMatrix.transpose();
	    
	    ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.Convert(matrixStackIn.last().pose());
		
		#elif MC_VER <= MC_1_20_4
		// get the matrices directly from MC
		ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.Convert(modelViewMatrixStack.last().pose());
		ClientApi.RENDER_STATE.mcProjectionMatrix = McObjectConverter.Convert(projectionMatrix);
		#elif MC_VER < MC_1_21_9
		// MC combined the model view and projection matricies
	    ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.Convert(projectionMatrix);
	    ClientApi.RENDER_STATE.mcProjectionMatrix = new Mat4f();
	    ClientApi.RENDER_STATE.mcProjectionMatrix.setIdentity();
		#else
		ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.Convert(positionMatrix);
		ClientApi.RENDER_STATE.mcProjectionMatrix = McObjectConverter.Convert(projectionMatrix);
		#endif
		
		ClientApi.RENDER_STATE.partialTickTime = MinecraftRenderWrapper.INSTANCE.getPartialTickTime();
		ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, this.level);
	    
	    
	    
	    #if MC_VER < MC_1_21_6
	    if (renderType.equals(RenderType.translucent())) 
		{
		    ClientApi.INSTANCE.renderDeferredLodsForShaders();
	    }
		#elif MC_VER < MC_1_21_9
	    // rendering handled via Fabric Api render event
		#else
		// handled here and in MixinChunkSectionsToRender
	    #endif
    }
	
	
	
	#if MC_VER < MC_1_21_9
	// rendering handled via Fabric Api render event
	#else
	@Inject(at = @At("HEAD"), method = "prepareChunkRenders")
	private void prepareChunkRenders(Matrix4fc modelViewMatrix, double d, double e, double f, CallbackInfoReturnable<ChunkSectionsToRender> callback)
	{
		ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.Convert(modelViewMatrix);
		ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, this.level);
		
		// only crash during development
		if (ModInfo.IS_DEV_BUILD)
		{
			ClientApi.RENDER_STATE.canRenderOrThrow();
		}
		
		ClientApi.INSTANCE.renderLods();
		
	}
	
	#endif
	
	
	
}
