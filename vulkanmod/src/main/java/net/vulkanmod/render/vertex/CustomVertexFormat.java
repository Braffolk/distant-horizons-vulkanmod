package net.vulkanmod.render.vertex;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

public abstract class CustomVertexFormat {
    public static final VertexFormatElement ELEMENT_POSITION = new VertexFormatElement(0, 0,VertexFormatElement.Type.SHORT, VertexFormatElement.Usage.POSITION, 4);
    public static final VertexFormatElement ELEMENT_COLOR = new VertexFormatElement(1, 0, VertexFormatElement.Type.UINT, VertexFormatElement.Usage.COLOR, 1);
    public static final VertexFormatElement ELEMENT_UV0 = new VertexFormatElement(2, 0, VertexFormatElement.Type.USHORT, VertexFormatElement.Usage.UV, 2);

    private static float POSITION_OFFSET = 4.0f;

    public static final VertexFormat COMPRESSED_TERRAIN =
            VertexFormat.builder()
                        .add("Position", ELEMENT_POSITION)
                        .add("UV0", ELEMENT_UV0)
                        .add("Color", ELEMENT_COLOR)
                        .build();

    public static final VertexFormat NONE = VertexFormat.builder().build();

    public static void setPositionOffset(float positionOffset) {
        POSITION_OFFSET = positionOffset;
    }

    public static float getPositionOffset() {
        return POSITION_OFFSET;
    }
}
