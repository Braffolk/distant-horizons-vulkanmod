package net.vulkanmod.vulkan.shader.converter;

import java.util.ArrayList;
import java.util.List;

public class UniformBlock {
    int binding;
    String name;
    String alias;
    List<Field> fields = new ArrayList<>();

    public UniformBlock(String name) {
        this.name = name;
    }

    public void addField(Field field) {
        this.fields.add(field);
    }

    public void setBinding(int binding) {
        this.binding = binding;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public GLSLParser.Node getNode() {
        StringBuilder sb = new StringBuilder();

        sb.append("layout(binding = %d) uniform %s {\n".formatted(binding, name));

        for (int i = 0, fieldsSize = fields.size(); i < fieldsSize; i++) {
            Field field = fields.get(i);
            sb.append("\t%s %s;".formatted(field.type, field.name));

            if (i < fieldsSize - 1) {
                sb.append("\n");
            }
        }

        sb.append("\n}");

        if (this.alias != null) {
            sb.append(" %s ".formatted(this.alias));
        }

        sb.append(";\n");

        return new GLSLParser.Node("uniform_block", sb.toString());
    }

    public static class Field {
        final String type, name;

        public Field(String type, String name) {
            this.type = type;
            this.name = name;
        }
    }
}
