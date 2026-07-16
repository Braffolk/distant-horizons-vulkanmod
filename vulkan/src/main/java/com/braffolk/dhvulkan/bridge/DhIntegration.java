package com.braffolk.dhvulkan.bridge;

import com.braffolk.dhvulkan.core.VulkanBackend;

/**
 * Interface for DH version-specific integration.
 * Each DH version has its own implementation that wires
 * the VulkanBackend into DH's rendering pipeline.
 */
public interface DhIntegration {

    /** Called once at mod init to set up this integration path */
    void initialize(VulkanBackend backend);

    /** Get the underlying Vulkan backend */
    VulkanBackend getBackend();

    /** Human-readable name for logging */
    String getName();

    /**
     * Late wiring hook. Only the DH 2.4 path needs this (it wires its render
     * delegate from a mixin before the first render pass); DH 3.x wires via the
     * API at init, so the default is a no-op. Keeping it on the interface lets the
     * entrypoint stay decoupled from the (optional) dh24 module.
     */
    default void wireIfNeeded() {}
}
