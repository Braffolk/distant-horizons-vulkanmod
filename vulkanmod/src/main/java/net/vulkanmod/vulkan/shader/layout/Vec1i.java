package net.vulkanmod.vulkan.shader.layout;

import net.vulkanmod.vulkan.shader.Uniforms;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.apache.commons.lang3.Validate;
import org.lwjgl.system.MemoryUtil;

import java.util.function.Supplier;

public class Vec1i extends Uniform {
    private Supplier<Integer> intSupplier;

    public Vec1i(Info info) {
        super(info);
    }

    protected void setupSupplier() {
        if (this.info.intSupplier != null) {
            this.intSupplier = this.info.intSupplier;
        } else {
            this.setSupplier(this.info.bufferSupplier);
        }
    }

    @Override
    public void setSupplier(Supplier<MappedBuffer> supplier) {
        this.intSupplier = () -> supplier.get().getInt(0);
    }

    void update(long ptr) {
        int i = this.intSupplier.get();
        MemoryUtil.memPutInt(ptr + this.offset, i);
    }
}
