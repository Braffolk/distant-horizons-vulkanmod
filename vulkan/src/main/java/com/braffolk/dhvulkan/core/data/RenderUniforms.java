package com.braffolk.dhvulkan.core.data;

import com.seibel.distanthorizons.core.util.math.Mat4f;

/**
 * DH-agnostic uniform data for a single render frame.
 * Both DH 2.4 and DH 3.0 integration layers populate this
 * from their respective parameter types.
 */
public class RenderUniforms {
    /** DH's projection matrix (extended near clip for high altitudes) */
    public final Mat4f dhProjectionMatrix = new Mat4f();

    /** DH's model-view matrix */
    public final Mat4f dhModelViewMatrix = new Mat4f();

    /** MC's projection matrix (for depth remapping in composite) */
    public final Mat4f mcProjectionMatrix = new Mat4f();

    /** World Y offset for terrain rendering */
    public double worldYOffset;

    /** Partial tick time for fog interpolation */
    public float partialTicks;

    /**
     * Set all fields from source matrices.
     * Callers should set worldYOffset and partialTicks directly.
     */
    public void set(Mat4f dhProj, Mat4f dhModelView, Mat4f mcProj) {
        this.dhProjectionMatrix.set(dhProj);
        this.dhModelViewMatrix.set(dhModelView);
        this.mcProjectionMatrix.set(mcProj);
    }
}
