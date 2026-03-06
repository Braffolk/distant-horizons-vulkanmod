package net.vulkanmod.render.chunk.buffer;

import net.vulkanmod.render.chunk.ChunkAreaManager;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.lwjgl.system.MemoryUtil;

public abstract class DrawParametersBuffer {
    static final long INDEX_COUNT_OFFSET = 0;
    static final long FIRST_INDEX_OFFSET = 4;
    static final long VERTEX_OFFSET_OFFSET = 8;
    static final long BASE_INSTANCE_OFFSET = 12;

    public static final long STRIDE = 16;

    static final int SECTIONS = ChunkAreaManager.AREA_SIZE;
    static final int FACINGS = 7;

    public static long allocateBuffer() {
        int size = (int) (ChunkAreaManager.AREA_SIZE * TerrainRenderType.VALUES.length * QuadFacing.COUNT * DrawParametersBuffer.STRIDE);
        long drawParamsPtr = MemoryUtil.nmemAlignedAlloc(32, size);

        for (long ptr = drawParamsPtr; ptr < drawParamsPtr + size; ptr += DrawParametersBuffer.STRIDE) {
            DrawParametersBuffer.resetParameters(ptr);
        }

        return drawParamsPtr;
    }

    public static void freeBuffer(long ptr) {
        MemoryUtil.nmemAlignedFree(ptr);
    }

    public static long getParamsPtr(long basePtr, int section, int renderType, int facing) {
        return basePtr + (((renderType * SECTIONS + section) * FACINGS) + facing) * STRIDE;
    }

    public static void resetParameters(long ptr) {
        setIndexCount(ptr, 0);
        setFirstIndex(ptr, 0);
        setVertexOffset(ptr, -1);
        setBaseInstance(ptr, 0);
    }

    public static void setIndexCount(long ptr, int value) {
        MemoryUtil.memPutInt(ptr + INDEX_COUNT_OFFSET, value);
    }

    public static void setFirstIndex(long ptr, int value) {
        MemoryUtil.memPutInt(ptr + FIRST_INDEX_OFFSET, value);
    }

    public static void setVertexOffset(long ptr, int value) {
        MemoryUtil.memPutInt(ptr + VERTEX_OFFSET_OFFSET, value);
    }

    public static void setBaseInstance(long ptr, int value) {
        MemoryUtil.memPutInt(ptr + BASE_INSTANCE_OFFSET, value);
    }

    public static int getIndexCount(long ptr) {
        return MemoryUtil.memGetInt(ptr + INDEX_COUNT_OFFSET);
    }

    public static int getFirstIndex(long ptr) {
        return MemoryUtil.memGetInt(ptr + FIRST_INDEX_OFFSET);
    }

    public static int getVertexOffset(long ptr) {
        return MemoryUtil.memGetInt(ptr + VERTEX_OFFSET_OFFSET);
    }

    public static int getBaseInstance(long ptr) {
        return MemoryUtil.memGetInt(ptr + BASE_INSTANCE_OFFSET);
    }

}
