package com.example.nuriaassistant.util;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal zero-dependency logging facade over {@code java.util.logging}.
 *
 * Every component logs under a named logger ({@code alpha.<Component>}) so the
 * standard JUL console handler already tags messages with a timestamp, the
 * component and the level — while keeping a single knob to tune verbosity on
 * the Pi ({@code -Djdk.logging.level=...}). Diagnostics are not hot paths, so
 * the per-call {@link Logger#getLogger(String)} lookup is intentional: it keeps
 * the facade stateless and trivially testable.
 */
public final class Log {

    private Log() {
    }

    /** Normal lifecycle / status diagnostics (shown on the Pi console). */
    public static void info(String component, String message) {
        Logger.getLogger("alpha." + component).info(message);
    }

    /** Recoverable problems that do not stop the feature. */
    public static void warn(String component, String message) {
        Logger.getLogger("alpha." + component).warning(message);
    }

    /** Failures worth investigating. */
    public static void error(String component, String message) {
        Logger.getLogger("alpha." + component).severe(message);
    }

    /** Failures with a throwable attached. */
    public static void error(String component, String message, Throwable throwable) {
        Logger.getLogger("alpha." + component).log(Level.SEVERE, message, throwable);
    }
}
