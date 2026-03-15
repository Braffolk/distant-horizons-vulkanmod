package com.braffolk.dhvulkan.core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Lightweight per-frame profiler for DH Vulkan rendering.
 *
 * Usage:
 *   profiler.beginFrame();
 *   profiler.begin(PHASE_DRAWS);
 *   ... draw calls ...
 *   profiler.end(PHASE_DRAWS);
 *   profiler.endFrame();
 *
 * Logs a summary every LOG_INTERVAL frames.
 * All timing uses System.nanoTime() — zero allocation, ~25ns per call.
 */
public final class DhFrameProfiler {

    private static final Logger LOGGER = LogManager.getLogger("DH-Perf");

    // Static flag — when false, JIT inlines all calls to no-ops.
    // Toggle at runtime via setEnabled(true), e.g. from config.
    private static volatile boolean enabled = false;
    // Phase IDs — fixed indices, no enum allocation
    public static final int PHASE_UNIFORMS  = 0;
    public static final int PHASE_DRAWS     = 1;
    public static final int PHASE_SSAO      = 2;
    public static final int PHASE_FOG       = 3;
    public static final int PHASE_COMPOSITE = 4;
    public static final int PHASE_CLOUDS    = 5;
    public static final int PHASE_PHASE2    = 6;
    private static final int PHASE_COUNT    = 7;

    private static final String[] PHASE_NAMES = {
            "uniforms", "draws", "ssao", "fog", "composite", "clouds", "phase2"
    };

    // How often to log (in frames)
    private static final int LOG_INTERVAL = 300;

    // Per-frame accumulators
    private final long[] phaseStart = new long[PHASE_COUNT];
    private final long[] phaseAccum = new long[PHASE_COUNT]; // current frame

    // Cross-frame stats
    private final long[] totalNanos  = new long[PHASE_COUNT];
    private final long[] peakNanos   = new long[PHASE_COUNT];
    private long totalFrameNanos;
    private long peakFrameNanos;
    private int drawCallCount;
    private int totalDrawCalls;

    // Frame tracking
    private long frameStart;
    private int frameCount;

    // GC tracking
    private final List<GarbageCollectorMXBean> gcBeans;
    private long prevGcCount;
    private long prevGcTime;
    private long intervalGcCount;
    private long intervalGcTimeMs;
    private boolean gcBaselined = false;

    public DhFrameProfiler() {
        this.gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    }

    public static void setEnabled(boolean flag) {
        enabled = flag;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Call at the start of each frame. */
    public void beginFrame() {
        if (!enabled) return;
        if (!gcBaselined) { snapshotGc(); gcBaselined = true; }
        frameStart = System.nanoTime();
        for (int i = 0; i < PHASE_COUNT; i++) {
            phaseAccum[i] = 0;
        }
        drawCallCount = 0;
    }

    /** Start timing a phase. */
    public void begin(int phase) {
        if (!enabled) return;
        phaseStart[phase] = System.nanoTime();
    }

    /** End timing a phase, accumulating into current frame. */
    public void end(int phase) {
        if (!enabled) return;
        phaseAccum[phase] += System.nanoTime() - phaseStart[phase];
    }

    /** Increment draw call counter. */
    public void countDraw() {
        if (!enabled) return;
        drawCallCount++;
    }

    /** Call at the end of each frame. */
    public void endFrame() {
        if (!enabled) return;

        long frameNanos = System.nanoTime() - frameStart;
        totalFrameNanos += frameNanos;
        if (frameNanos > peakFrameNanos) peakFrameNanos = frameNanos;

        for (int i = 0; i < PHASE_COUNT; i++) {
            totalNanos[i] += phaseAccum[i];
            if (phaseAccum[i] > peakNanos[i]) peakNanos[i] = phaseAccum[i];
        }
        totalDrawCalls += drawCallCount;
        frameCount++;

        if (frameCount >= LOG_INTERVAL) {
            log();
            reset();
        }
    }

    private void log() {
        // GC delta
        long gcCount = 0, gcTimeMs = 0;
        for (GarbageCollectorMXBean gc : gcBeans) {
            gcCount += gc.getCollectionCount();
            gcTimeMs += gc.getCollectionTime();
        }
        intervalGcCount = gcCount - prevGcCount;
        intervalGcTimeMs = gcTimeMs - prevGcTime;
        prevGcCount = gcCount;
        prevGcTime = gcTimeMs;

        float n = frameCount;
        StringBuilder sb = new StringBuilder(256);
        sb.append(String.format("Avg: %.1fms (peak %.1fms) | ",
                (totalFrameNanos / n) / 1_000_000f,
                peakFrameNanos / 1_000_000f));

        for (int i = 0; i < PHASE_COUNT; i++) {
            sb.append(String.format("%s: %.2f/%.2f | ",
                    PHASE_NAMES[i],
                    (totalNanos[i] / n) / 1_000_000f,
                    peakNanos[i] / 1_000_000f));
        }

        sb.append(String.format("draws: %d | GC: %d (%dms)",
                totalDrawCalls / frameCount,
                intervalGcCount,
                intervalGcTimeMs));

        LOGGER.info("[DH-Perf] {}", sb);
    }

    private void snapshotGc() {
        prevGcCount = 0;
        prevGcTime = 0;
        for (GarbageCollectorMXBean gc : gcBeans) {
            prevGcCount += gc.getCollectionCount();
            prevGcTime += gc.getCollectionTime();
        }
    }

    private void reset() {
        frameCount = 0;
        totalFrameNanos = 0;
        peakFrameNanos = 0;
        totalDrawCalls = 0;
        for (int i = 0; i < PHASE_COUNT; i++) {
            totalNanos[i] = 0;
            peakNanos[i] = 0;
        }
    }
}
