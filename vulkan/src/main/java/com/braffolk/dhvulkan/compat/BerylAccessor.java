package com.braffolk.dhvulkan.compat;

import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;

/**
 * Accessor that mimics Iris so DH knows when a shader pack (Beryl) is active,
 * and specifically when Beryl's shadow pass is being rendered.
 */
public class BerylAccessor implements IIrisAccessor {
    
    // We only create this if Beryl is loaded and active
    private static boolean renderingShadowPass = false;

    @Override
    public String getModName() {
        return "BerylShader";
    }

    @Override
    public boolean isShaderPackInUse() {
        return true; 
    }

    @Override
    public boolean isRenderingShadowPass() {
        return renderingShadowPass;
    }

    public static void setRenderingShadowPass(boolean shadowPass) {
        renderingShadowPass = shadowPass;
    }
}
