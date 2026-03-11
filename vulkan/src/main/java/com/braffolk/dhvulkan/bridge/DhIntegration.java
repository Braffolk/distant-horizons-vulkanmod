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

    /** Human-readable name for logging */
    String getName();
}
