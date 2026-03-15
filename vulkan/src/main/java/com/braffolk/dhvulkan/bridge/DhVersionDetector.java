package com.braffolk.dhvulkan.bridge;

/**
 * Runtime detection of the installed DH version.
 * Uses class presence checks (no preprocessor needed).
 */
public class DhVersionDetector {

    public enum DhVersion {
        /** DH 2.4.x -- mixin-based integration */
        DH_2_4,
        /** DH 3.0+ -- API-based integration via AbstractDhRenderApiDefinition */
        DH_3_0,
        /** Unknown DH version */
        UNKNOWN
    }

    private static DhVersion detected = null;

    /**
     * Detect the installed DH version. Result is cached after first call.
     */
    public static DhVersion detect() {
        if (detected != null) {
            return detected;
        }

        try {
            Class.forName(
                "com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition"
            );
            detected = DhVersion.DH_3_0;
        } catch (ClassNotFoundException e) {
            // Class not found = DH 2.4 or earlier
            detected = DhVersion.DH_2_4;
        }

        return detected;
    }
}
