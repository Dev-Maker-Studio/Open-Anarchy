package com.rs.lib.util;

import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;

/**
 * Compatibility shim for the legacy Logger utility.
 * Wraps {@link java.util.logging.Logger} with the same static API
 * the legacy world-server code expects. No cache or database dependencies.
 */
public final class Logger {

    private static final String ROOT_KEY = "DarkanRS";
    private static final java.util.logging.Logger DARKAN_ROOT_LOGGER =
            java.util.logging.Logger.getLogger(ROOT_KEY);

    private static volatile boolean debug = false;

    private Logger() { }

    public static void setupFormat() {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "[%1$tF %1$tT %1$tL] [%4$-7s] %5$s %n");
        DARKAN_ROOT_LOGGER.addHandler(new ConsoleHandler());
        DARKAN_ROOT_LOGGER.setUseParentHandlers(false);
    }

    public static void setLevel(Level level) {
        DARKAN_ROOT_LOGGER.setLevel(level);
        for (Handler handler : DARKAN_ROOT_LOGGER.getHandlers())
            handler.setLevel(level);
    }

    public static void setDebug(boolean enabled) {
        debug = enabled;
    }

    // --- handle overloads (SEVERE with throwable) ---

    public static void handle(Class<?> source, String method, Throwable e) {
        String enclosing = e.getClass().getEnclosingMethod() != null
                ? "[" + e.getClass().getEnclosingMethod().getName() + "]: "
                : "";
        DARKAN_ROOT_LOGGER.log(Level.SEVERE,
                "[" + source.getSimpleName() + "." + method + "] " + enclosing, e);
        if (debug && e != null)
            e.printStackTrace();
    }

    public static void handle(Class<?> source, String method, String message, Throwable e) {
        // Legacy version recorded to DB here; we skip that entirely.
        handleNoRecord(source, method, message, e);
    }

    public static void handleNoRecord(Class<?> source, String method, String message, Throwable e) {
        String enclosing = e != null && e.getClass().getEnclosingMethod() != null
                ? "[" + e.getClass().getEnclosingMethod().getName() + "]: "
                : "";
        DARKAN_ROOT_LOGGER.log(Level.SEVERE,
                "[" + source.getSimpleName() + "." + method + "] " + enclosing + message, e);
        if (debug && e != null)
            e.printStackTrace();
    }

    // --- level-specific logging ---

    public static void error(Class<?> source, String method, Object msg) {
        DARKAN_ROOT_LOGGER.log(Level.SEVERE,
                "[" + source.getSimpleName() + "." + method + "] " + msg);
    }

    public static void warn(Class<?> source, String method, Object msg) {
        DARKAN_ROOT_LOGGER.log(Level.WARNING,
                "[" + source.getSimpleName() + "." + method + "] " + msg);
    }

    /**
     * Logs at {@link Level#CONFIG} to match legacy behavior (the original
     * Logger.info mapped to CONFIG, not INFO).
     */
    public static void info(Class<?> source, String method, Object msg) {
        DARKAN_ROOT_LOGGER.log(Level.CONFIG,
                "[" + source.getSimpleName() + "." + method + "] " + msg);
    }

    public static void debug(Class<?> source, String method, Object msg) {
        DARKAN_ROOT_LOGGER.log(Level.FINE,
                "[" + source.getSimpleName() + "." + method + "] " + msg);
    }

    public static void trace(Class<?> source, String method, Object msg) {
        DARKAN_ROOT_LOGGER.log(Level.FINER,
                "[" + source.getSimpleName() + "." + method + "] " + msg);
    }

    // --- simple tag-based logging ---

    public static void log(String tag, Object message) {
        DARKAN_ROOT_LOGGER.log(Level.INFO, "[" + tag + "] " + message);
    }
}
