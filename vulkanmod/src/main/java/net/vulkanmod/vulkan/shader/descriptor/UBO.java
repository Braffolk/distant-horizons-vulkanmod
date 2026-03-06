package net.vulkanmod.vulkan.shader.descriptor;

import net.vulkanmod.vulkan.memory.buffer.BufferSlice;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.shader.layout.Uniform;

import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;

public class UBO extends AlignedStruct implements Descriptor {
    public final String name;
    public final int binding;
    public final int stages;
    public final BufferSlice bufferSlice;
    private boolean useGlobalBuffer;
    private boolean update;

    public UBO(String name, int binding, int stages, int size, List<Uniform.Info> infoList) {
        super(infoList, size);
        this.name = name;
        this.binding = binding;
        this.stages = stages;
        this.update = true;

        this.bufferSlice = new BufferSlice();
    }

    @Override
    public String toString() {
        return "UBO{" +
               "name='" + name + '\'' +
               ", binding=" + binding +
               ", useGlobalBuffer=" + useGlobalBuffer +
               '}';
    }

    public int getBinding() {
        return binding;
    }

    @Override
    public int getType() {
        return VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
    }

    public int getStages() {
        return stages;
    }

    public BufferSlice getBufferSlice() {
        return bufferSlice;
    }

    public boolean useGlobalBuffer() {
        return useGlobalBuffer;
    }

    public void setUseGlobalBuffer(boolean useGlobalBuffer) {
        this.useGlobalBuffer = useGlobalBuffer;
    }

    public boolean shouldUpdate() {
        return update;
    }

    public void setUpdate(boolean update) {
        this.update = update;
    }
}
