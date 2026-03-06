package net.vulkanmod.gl;

import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;

public class VkGlShader {
    private static int ID_COUNTER = 1;
    private static final Int2ReferenceOpenHashMap<VkGlShader> map = new Int2ReferenceOpenHashMap<>();
    private static int boundTextureId = 0;

    public static int glCreateShader(int type) {
        int id = ID_COUNTER++;
        VkGlShader shader = new VkGlShader(id, type);

        map.put(id, shader);
        return id;
    }

    public static void glDeleteShader(int i) {
        map.remove(i);
    }

    public static void glShaderSource(int i, String string) {
        VkGlShader shader = map.get(i);
        shader.source = string;
    }

    public static void glCompileShader(int i) {

    }

    public static int glGetShaderi(int i, int j) {
        return 0;
    }

    final int id;
    final int type;

    String source;

    VkGlShader(int id, int type) {
        this.id = id;
        this.type = type;
    }

}
