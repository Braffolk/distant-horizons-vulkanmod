package net.vulkanmod.config.video;

public enum WindowMode {
    WINDOWED(0),
    WINDOWED_FULLSCREEN(1),
    EXCLUSIVE_FULLSCREEN(2);

    public final int mode;

    WindowMode(int mode) {
        this.mode = mode;
    }

    public static WindowMode fromValue(int value) {
        return switch (value) {
            case 0 -> WINDOWED;
            case 1 -> WINDOWED_FULLSCREEN;
            case 2 -> EXCLUSIVE_FULLSCREEN;

            default -> throw new IllegalStateException("Unexpected value: " + value);
        };
    }

    public static String getComponentName(WindowMode windowMode) {
        return switch (windowMode) {
            case WINDOWED -> "vulkanmod.options.windowMode.windowed";
            case WINDOWED_FULLSCREEN -> "vulkanmod.options.windowMode.windowedFullscreen";
            case EXCLUSIVE_FULLSCREEN -> "options.fullscreen";
        };
    }
}
