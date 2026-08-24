package com.tesseractsoftwares.praxsuite;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SDK logging, with credentials scrubbed from every message.
 *
 * <p>Uses {@code java.util.logging} rather than SLF4J so the SDK stays dependency-free. That is not
 * the fashionable choice, but it is the one that works everywhere without a binding: a Paper plugin,
 * a Spring service and a plain {@code main} all have JUL available, and anyone using SLF4J or Log4j
 * can bridge JUL in one line rather than being forced to accept our binding.
 *
 * <p>Scrubbing happens here, not at the call sites, so a message assembled anywhere in the SDK
 * cannot leak a key into a log file or a crash report someone pastes into an issue.
 */
public final class PraxLog {

    private PraxLog() {}

    /** The SDK's logger. Configure it like any other JUL logger. */
    public static final Logger LOGGER = Logger.getLogger("com.tesseractsoftwares.praxsuite");

    // Keeps the prefix so a log still says WHICH kind of key was involved, and drops the material.
    private static final Pattern KEY =
        Pattern.compile("\\b(pk_live_|sk_live_)[A-Za-z0-9]{4,}");
    private static final Pattern JWT =
        Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+");
    private static final Pattern SECRET_FIELD = Pattern.compile(
        "\"(refreshToken|accessToken|password|newPassword|currentPassword|confirmPassword"
            + "|sessionToken|publicKey)\"\\s*:\\s*\"[^\"]*\"");

    /**
     * Removes credentials from a string.
     *
     * <p>Public because callers building their own diagnostics should run untrusted text through it
     * too - a gateway response body pasted into a bug report is the usual culprit.
     */
    public static String scrub(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = KEY.matcher(text).replaceAll(mr -> mr.group(1) + "<redacted>");
        out = JWT.matcher(out).replaceAll("<jwt redacted>");
        out = SECRET_FIELD.matcher(out)
            .replaceAll(mr -> "\"" + mr.group(1) + "\":\"<redacted>\"");
        return out;
    }

    static void error(String message) { log(Level.SEVERE, message); }
    static void warn(String message) { log(Level.WARNING, message); }
    static void info(String message) { log(Level.INFO, message); }
    static void debug(String message) { log(Level.FINE, message); }

    private static void log(Level level, String message) {
        if (LOGGER.isLoggable(level)) LOGGER.log(level, scrub(message));
    }

    /** Kept so {@link Matcher} is used rather than imported and ignored by a future edit. */
    static boolean containsCredential(String text) {
        if (text == null) return false;
        Matcher m = KEY.matcher(text);
        return m.find() || JWT.matcher(text).find();
    }
}
