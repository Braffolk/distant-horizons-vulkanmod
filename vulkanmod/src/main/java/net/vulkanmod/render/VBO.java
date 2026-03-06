package net.vulkanmod.render;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.*;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.VertexBuffer;
import net.vulkanmod.vulkan.memory.buffer.index.AutoIndexBuffer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;

import java.nio.ByteBuffer;

public class VBO {
    private final MemoryType memoryType;
    private VertexBuffer vertexBuffer;
    private IndexBuffer indexBuffer;

    private VertexFormat.Mode mode;
    private boolean autoIndexed = false;
    private int indexCount;
    private int vertexCount;

    public VBO(boolean useGpuMem) {
       this.memoryType = useGpuMem ? MemoryTypes.GPU_MEM : MemoryTypes.HOST_MEM;
    }

    public void upload(MeshData meshData) {
        MeshData.DrawState parameters = meshData.drawState();

        this.indexCount = parameters.indexCount();
        this.vertexCount = parameters.vertexCount();
        this.mode = parameters.mode();

        this.uploadVertexBuffer(parameters, meshData.vertexBuffer());
        this.uploadIndexBuffer(meshData.indexBuffer());

        meshData.close();
    }

    private void uploadVertexBuffer(MeshData.DrawState parameters, ByteBuffer data) {
        if (data != null) {
            if (this.vertexBuffer != null)
                this.vertexBuffer.scheduleFree();

            int size = parameters.format().getVertexSize() * parameters.vertexCount();
            this.vertexBuffer = new VertexBuffer(size, this.memoryType);
            this.vertexBuffer.copyBuffer(data, size);
        }
    }

    public void uploadIndexBuffer(ByteBuffer data) {
        if (data == null) {

            AutoIndexBuffer autoIndexBuffer;
            switch (this.mode) {
                case TRIANGLE_FAN -> {
                    autoIndexBuffer = Renderer.getDrawer().getTriangleFanIndexBuffer();
                    this.indexCount = AutoIndexBuffer.DrawType.getTriangleStripIndexCount(this.vertexCount);
                }
                case TRIANGLE_STRIP, LINE_STRIP -> {
                    autoIndexBuffer = Renderer.getDrawer().getTriangleStripIndexBuffer();
                    this.indexCount = AutoIndexBuffer.DrawType.getTriangleStripIndexCount(this.vertexCount);
                }
                case QUADS -> {
                    autoIndexBuffer = Renderer.getDrawer().getQuadsIndexBuffer();
                }
                case LINES -> {
                    autoIndexBuffer = Renderer.getDrawer().getLinesIndexBuffer();
                }
                case DEBUG_LINE_STRIP -> {
                    autoIndexBuffer = Renderer.getDrawer().getDebugLineStripIndexBuffer();
                }
                case TRIANGLES, DEBUG_LINES -> {
                    autoIndexBuffer = null;
                }
                default -> throw new IllegalStateException("Unexpected draw mode: %s".formatted(this.mode));
            }

            if (this.indexBuffer != null && !this.autoIndexed) {
                this.indexBuffer.scheduleFree();
            }

            if (autoIndexBuffer != null) {
                autoIndexBuffer.checkCapacity(this.vertexCount);
                this.indexBuffer = autoIndexBuffer.getIndexBuffer();
            }

            this.autoIndexed = true;
        }
        else {
            if (this.indexBuffer != null && !this.autoIndexed) {
                this.indexBuffer.scheduleFree();
            }

            this.indexBuffer = new IndexBuffer(data.remaining(), MemoryTypes.GPU_MEM);
            this.indexBuffer.copyBuffer(data, data.remaining());
        }
    }

    public void bind(GraphicsPipeline pipeline) {
        Renderer renderer = Renderer.getInstance();
        renderer.bindGraphicsPipeline(pipeline);
        VTextureSelector.bindShaderTextures(pipeline);
        renderer.uploadAndBindUBOs(pipeline);
    }

    public void draw() {
        if (this.indexCount != 0) {
            Renderer renderer = Renderer.getInstance();
            Pipeline pipeline = renderer.getBoundPipeline();
            renderer.uploadAndBindUBOs(pipeline);

            if (this.indexBuffer != null) {
                Renderer.getDrawer().drawIndexed(this.vertexBuffer, this.indexBuffer, this.indexCount);
            }
            else {
                Renderer.getDrawer().draw(this.vertexBuffer, this.vertexCount);
            }
        }
    }

    public void close() {
        if (this.vertexCount <= 0)
            return;

        this.vertexBuffer.scheduleFree();
        this.vertexBuffer = null;

        if (!this.autoIndexed) {
            this.indexBuffer.scheduleFree();
            this.indexBuffer = null;
        }

        this.vertexCount = 0;
        this.indexCount = 0;
    }

}
