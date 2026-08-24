package com.tesseractsoftwares.praxsuite;

import java.util.regex.Pattern;

/**
 * Tells a publishable credential from a secret one, and refuses the wrong one.
 *
 * <p>A JVM process is usually a server you control, so unlike the game-engine SDKs a secret key is
 * often exactly right here. What this prevents is the opposite mistake: shipping a secret key
 * somewhere a user can read it - an Android APK, a desktop app, a decompilable client mod, or a
 * Minecraft plugin distributed to server owners who are not you.
 */
public final class KeyGuard {

    private KeyGuard() {}

    public static final String PUBLISHABLE_PREFIX = "pk_live_";
    public static final String SECRET_PREFIX = "sk_live_";

    private static final Pattern JWT =
        Pattern.compile("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");

    /** What a credential is, by shape alone. Never contacts the network. */
    public enum Kind { UNKNOWN, PUBLISHABLE, SECRET, JWT }

    public static Kind classify(String credential) {
        if (credential == null || credential.isBlank()) return Kind.UNKNOWN;
        String v = credential.trim();
        if (v.startsWith(SECRET_PREFIX)) return Kind.SECRET;
        if (v.startsWith(PUBLISHABLE_PREFIX)) return Kind.PUBLISHABLE;
        if (JWT.matcher(v).matches()) return Kind.JWT;
        return Kind.UNKNOWN;
    }

    public static boolean isSecret(String credential) {
        return classify(credential) == Kind.SECRET;
    }

    /**
     * Throws when a secret key is about to be used somewhere it would be exposed.
     *
     * @throws PraxValidationError if the credential is an {@code sk_live_} key.
     */
    public static void requireClientSafe(String credential, String context) {
        if (isSecret(credential)) {
            throw new PraxValidationError("SECRET_KEY_REFUSED",
                context + " was given a secret key (sk_live_...). Anything a user can read - an "
                    + "Android build, a desktop app, a client mod, or a plugin you hand to server "
                    + "owners - must use a publishable key (pk_live_...) instead. Revoke this key "
                    + "if it has already been distributed.");
        }
    }

    /** Masks a credential for display, keeping only enough to identify which one it was. */
    public static String redact(String credential) {
        if (credential == null || credential.isEmpty()) return "<empty>";
        String v = credential.trim();
        for (String prefix : new String[] {SECRET_PREFIX, PUBLISHABLE_PREFIX}) {
            if (v.startsWith(prefix)) {
                String tail = v.substring(prefix.length());
                return prefix + (tail.length() > 4 ? tail.substring(0, 4) + "..." : "...");
            }
        }
        if (JWT.matcher(v).matches()) return "<jwt>";
        return v.length() > 2 ? v.substring(0, 2) + "..." : "...";
    }
}
