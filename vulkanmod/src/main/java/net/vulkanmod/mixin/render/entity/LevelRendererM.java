package net.vulkanmod.mixin.render.entity;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.WorldRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

@Mixin(LevelRenderer.class)
public class LevelRendererM {

    // TODO
//    @Shadow @Final private EntityRenderDispatcher entityRenderDispatcher;
//    @Shadow @Final private Minecraft minecraft;
//
//    @Unique private Object2ReferenceOpenHashMap<MultiBufferSource, Map<Class<? extends Entity>, ObjectArrayList<Entity>>> bufferSourceMap = new Object2ReferenceOpenHashMap<>();
//    @Unique boolean managed;
//
//    @Inject(method = "renderLevel",
//            at = @At(value = "INVOKE",
//                    target = "Lnet/minecraft/client/renderer/LevelRenderer;setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V",
//                    shift = At.Shift.AFTER)
//    )
//    private void clearMap(GraphicsResourceAllocator graphicsResourceAllocator, DeltaTracker deltaTracker, boolean bl,
//                          Camera camera, Matrix4f matrix4f, Matrix4f matrix4f2, Matrix4f matrix4f3,
//                          GpuBufferSlice gpuBufferSlice, Vector4f vector4f, boolean bl2, CallbackInfo ci) {
//        for (var bufferSource : this.bufferSourceMap.keySet()) {
//            var entityMap = this.bufferSourceMap.get(bufferSource);
//            entityMap.clear();
//        }
//
//        this.managed = true;
//    }
//
//    /**
//     * @author
//     * @reason
//     */
//    @Overwrite
//    private void renderEntity(Entity entity, double d, double e, double f, float partialTicks, PoseStack poseStack, MultiBufferSource multiBufferSource) {
//        if (!Initializer.CONFIG.entityCulling || !this.managed) {
//            double h = Mth.lerp(partialTicks, entity.xOld, entity.getX());
//            double i = Mth.lerp(partialTicks, entity.yOld, entity.getY());
//            double j = Mth.lerp(partialTicks, entity.zOld, entity.getZ());
//            this.entityRenderDispatcher.render(entity, h - d, i - e, j - f, partialTicks, poseStack, multiBufferSource, this.entityRenderDispatcher.getPackedLightCoords(entity, partialTicks));
//            return;
//        }
//
//        var entityClass = entity.getClass();
//
//        var entityMap = this.bufferSourceMap.computeIfAbsent(multiBufferSource, bufferSource -> new Object2ReferenceOpenHashMap<>());
//        var list = entityMap.computeIfAbsent(entityClass, k -> new ObjectArrayList<>());
//        list.add(entity);
//    }
//
//    @Inject(method = "renderEntities", at = @At("RETURN"))
//    private void renderEntities(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource1, Camera camera, DeltaTracker deltaTracker, List<Entity> entityList, CallbackInfo ci) {
//        if (!Initializer.CONFIG.entityCulling)
//            return;
//
//        Vec3 cameraPos = WorldRenderer.getCameraPos();
//        TickRateManager tickRateManager = this.minecraft.level.tickRateManager();
//
////        PoseStack poseStack = new PoseStack();
//
//        for (var bufferSource : this.bufferSourceMap.keySet()) {
//            var entityMap = this.bufferSourceMap.get(bufferSource);
//
//            for (var list : entityMap.values()) {
//                for (Entity entity : list) {
//                    float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(!tickRateManager.isEntityFrozen(entity));
//
//                    double h = Mth.lerp(partialTicks, entity.xOld, entity.getX());
//                    double i = Mth.lerp(partialTicks, entity.yOld, entity.getY());
//                    double j = Mth.lerp(partialTicks, entity.zOld, entity.getZ());
//                    this.entityRenderDispatcher.render(entity, h - cameraPos.x, i - cameraPos.y, j - cameraPos.z, partialTicks, poseStack, bufferSource, this.entityRenderDispatcher.getPackedLightCoords(entity, partialTicks));
//                }
//            }
//        }
//
//        this.managed = false;
//    }
}
