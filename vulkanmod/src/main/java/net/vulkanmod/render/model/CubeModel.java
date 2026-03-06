package net.vulkanmod.render.model;

import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Set;

public class CubeModel {

    private Polygon[] polygons;
    public float minX;
    public float minY;
    public float minZ;
    public float maxX;
    public float maxY;
    public float maxZ;

    Vertex[] vertices;

    public void setVertices(int u, int v, float minX, float minY, float minZ, float dimX, float dimY, float dimZ, float growX, float growY, float growZ, boolean mirror, float uTexScale, float vTexScale, Set<Direction> set) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = minX + dimX;
        this.maxY = minY + dimY;
        this.maxZ = minZ + dimZ;
        this.polygons = new Polygon[set.size()];
        float s = maxX;
        float t = maxY;
        float u1 = maxZ;
        minX -= growX;
        minY -= growY;
        minZ -= growZ;
        s += growX;
        t += growY;
        u1 += growZ;
        if (mirror) {
            float v1 = s;
            s = minX;
            minX = v1;
        }

        this.vertices = new Vertex[]{
                new Vertex(minX, minY, minZ, 0.0F, 0.0F),
                new Vertex(s, minY, minZ,    0.0F, 8.0F),
                new Vertex(s, t, minZ,       8.0F, 8.0F),
                new Vertex(minX, t, minZ,    8.0F, 0.0F),
                new Vertex(minX, minY, u1,    0.0F, 0.0F),
                new Vertex(s, minY, u1,       0.0F, 8.0F),
                new Vertex(s, t, u1,          8.0F, 8.0F),
                new Vertex(minX, t, u1,       8.0F, 0.0F)
        };

        float w = (float)u;
        float x = (float)u + dimZ;
        float y = (float)u + dimZ + dimX;
        float z = (float)u + dimZ + dimX + dimX;
        float aa = (float)u + dimZ + dimX + dimZ;
        float ab = (float)u + dimZ + dimX + dimZ + dimX;
        float ac = (float)v;
        float ad = (float)v + dimZ;
        float ae = (float)v + dimZ + dimY;

        Vertex vertex1 = this.vertices[0];
        Vertex vertex2 = this.vertices[1];
        Vertex vertex3 = this.vertices[2];
        Vertex vertex4 = this.vertices[3];
        Vertex vertex5 = this.vertices[4];
        Vertex vertex6 = this.vertices[5];
        Vertex vertex7 = this.vertices[6];
        Vertex vertex8 = this.vertices[7];

        int idx = 0;
        if (set.contains(Direction.DOWN)) {
            this.polygons[idx++] = new Polygon(new Vertex[]{vertex6, vertex5, vertex1, vertex2}, x, ac, y, ad, uTexScale, vTexScale, mirror, Direction.DOWN);
        }

        if (set.contains(Direction.UP)) {
            this.polygons[idx++] = new Polygon(new Vertex[]{vertex3, vertex4, vertex8, vertex7}, y, ad, z, ac, uTexScale, vTexScale, mirror, Direction.UP);
        }

        if (set.contains(Direction.WEST)) {
            this.polygons[idx++] = new Polygon(new Vertex[]{vertex1, vertex5, vertex8, vertex4}, w, ad, x, ae, uTexScale, vTexScale, mirror, Direction.WEST);
        }

        if (set.contains(Direction.NORTH)) {
            this.polygons[idx++] = new Polygon(new Vertex[]{vertex2, vertex1, vertex4, vertex3}, x, ad, y, ae, uTexScale, vTexScale, mirror, Direction.NORTH);
        }

        if (set.contains(Direction.EAST)) {
            this.polygons[idx++] = new Polygon(new Vertex[]{vertex6, vertex2, vertex3, vertex7}, y, ad, aa, ae, uTexScale, vTexScale, mirror, Direction.EAST);
        }

        if (set.contains(Direction.SOUTH)) {
            this.polygons[idx] = new Polygon(new Vertex[]{vertex5, vertex6, vertex7, vertex8}, aa, ad, ab, ae, uTexScale, vTexScale, mirror, Direction.SOUTH);
        }
    }

    public void transformVertices(Matrix4f matrix) {
        // Transform original vertices and store them
        for (int i = 0; i < 8; ++i) {
            Vertex vertex = this.vertices[i];
            vertex.pos.mulPosition(matrix, vertex.transformed);
        }
    }

    public Polygon[] getPolygons() {
        return this.polygons;
    }

    public record Polygon(Vertex[] vertices, Vector3fc normal) {

        public Polygon(Vertex[] vertices, float u0, float v0, float u1, float v1, float uSize, float vSize, boolean mirror, Direction direction) {
            this(vertices, (mirror ? mirrorFacing(direction) : direction).getUnitVec3f());

            // This will force NaN if uSize or vSize are 0
            float l = 0.0F / uSize;
            float m = 0.0F / vSize;

            vertices[0] = vertices[0].remap(u1 / uSize - l, v0 / vSize + m);
            vertices[1] = vertices[1].remap(u0 / uSize + l, v0 / vSize + m);
            vertices[2] = vertices[2].remap(u0 / uSize + l, v1 / vSize - m);
            vertices[3] = vertices[3].remap(u1 / uSize - l, v1 / vSize - m);

            if (mirror) {
                int n = vertices.length;

                for (int o = 0; o < n / 2; o++) {
                    Vertex vertex = vertices[o];
                    vertices[o] = vertices[n - 1 - o];
                    vertices[n - 1 - o] = vertex;
                }
            }
        }

        private static Direction mirrorFacing(Direction direction) {
            return direction.getAxis() == Direction.Axis.X ? direction.getOpposite() : direction;
        }
    }

    public static class Vertex {
        private static final float SCALE_FACTOR = 16.0F;

        final Vector3f pos;
        final Vector3f transformed;
        float u, v;

        public Vertex(float x, float y, float z, float u, float v) {
            this.pos = new Vector3f(x / SCALE_FACTOR, y / SCALE_FACTOR, z / SCALE_FACTOR);
            this.transformed = new Vector3f();
            this.u = u;
            this.v = v;
        }

        public Vertex(Vector3f pos, Vector3f transformed, float u, float v) {
            this.pos = pos;
            this.transformed = transformed;
            this.u = u;
            this.v = v;
        }

        Vertex remap(float u, float v) {
            return new Vertex(this.pos, this.transformed, u, v);
        }

        public Vector3f pos() {
            return transformed;
        }

        public float u() {
            return u;
        }

        public float v() {
            return v;
        }
    }

}
