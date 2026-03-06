package net.vulkanmod.mixin.chunk;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.vulkanmod.render.chunk.ChunkStatusMap;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.Consumer;

@Mixin(ClientChunkCache.class)
public class ClientChunkCacheM {

    @Inject(method = "replaceWithPacketData", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;onChunkLoaded(Lnet/minecraft/world/level/ChunkPos;)V"))
    private void setChunkStatus(int x, int z, FriendlyByteBuf friendlyByteBuf, Map<Heightmap.Types, long[]> map,
                                Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
                                CallbackInfoReturnable<LevelChunk> cir) {
        ChunkStatusMap.INSTANCE.setChunkStatus(x, z, ChunkStatusMap.DATA_READY);
    }

    @Inject(method = "drop", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientChunkCache$Storage;drop(ILnet/minecraft/world/level/chunk/LevelChunk;)V"))
    private void resetChunkStatus(ChunkPos chunkPos, CallbackInfo ci) {
        ChunkStatusMap.INSTANCE.resetChunkStatus(chunkPos.x, chunkPos.z, ChunkStatusMap.DATA_READY);
    }
}
