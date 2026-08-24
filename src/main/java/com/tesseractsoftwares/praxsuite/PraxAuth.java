package com.tesseractsoftwares.praxsuite;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * User accounts: register, sign in, refresh, sign out, password reset.
 *
 * <p>Reached through the client: {@code prax.auth()}.
 *
 * <p>Auth routes skip table-scope checks, so register/login/refresh work on a credential with no
 * table scopes at all. That is the credential a client-facing application should carry.
 *
 * <p>Thread-safe. A refresh is serialised, because the gateway retires the old refresh token as it
 * issues the new one - two concurrent refreshes would leave the loser holding a token the server has
 * already invalidated.
 */
public final class PraxAuth {

    /**
     * Refresh this many seconds before the access token actually expires, so it happens between
     * requests rather than in the middle of one.
     */
    private static final long REFRESH_SKEW_SECONDS = 60;

    /** A signed-in user's session. */
    public static final class Session {
        private final String accessToken;
        private final String refreshToken;
        private final Instant expiresAt;
        private final String userId;
        private final String email;
        private final String displayName;
        private final Map<String, Object> profile;

        Session(String accessToken, String refreshToken, Instant expiresAt,
                String userId, String email, String displayName, Map<String, Object> profile) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAt = expiresAt;
            this.userId = userId;
            this.email = email;
            this.displayName = displayName;
            this.profile = profile == null ? Map.of() : Map.copyOf(profile);
        }

        public String accessToken() { return accessToken; }
        public String refreshToken() { return refreshToken; }
        public Instant expiresAt() { return expiresAt; }
        public String userId() { return userId; }
        public String email() { return email; }
        public String displayName() { return displayName; }

        /** Any additional profile fields the workspace returned, verbatim. */
        public Map<String, Object> profile() { return profile; }

        public boolean isValid() { return accessToken != null && !accessToken.isEmpty(); }

        public boolean isExpired() {
            return expiresAt != null && !Instant.now().isBefore(expiresAt);
        }

        boolean needsRefresh() {
            return expiresAt != null
                && !Instant.now().isBefore(expiresAt.minusSeconds(REFRESH_SKEW_SECONDS));
        }

        static Session fromPayload(Map<String, Object> payload, Session previous) {
            String access = str(payload.get("accessToken"));
            String refresh = str(payload.get("refreshToken"));

            Instant expires = null;
            Object expiresIn = payload.get("expiresIn");
            if (expiresIn instanceof Number n && n.longValue() > 0) {
                // The gateway reports a lifetime in seconds; an absolute instant is what callers
                // need in order to reason about it.
                expires = Instant.now().plusSeconds(n.longValue());
            }

            String userId = "";
            String email = "";
            String displayName = "";
            Map<String, Object> profile = Map.of();
            if (payload.get("user") instanceof Map<?, ?> raw) {
                Map<String, Object> user = new LinkedHashMap<>();
                raw.forEach((k, v) -> user.put(String.valueOf(k), v));
                userId = str(user.getOrDefault("id", user.get("userId")));
                email = str(user.get("email"));
                displayName = str(user.getOrDefault("displayName", user.get("name")));
                profile = user;
            }

            // A refresh carries tokens but not always the user block. Carry the old identity
            // forward rather than presenting a signed-in user as anonymous.
            if (previous != null) {
                if (userId.isEmpty()) userId = previous.userId;
                if (email.isEmpty()) email = previous.email;
                if (displayName.isEmpty()) displayName = previous.displayName;
                if (profile.isEmpty()) profile = previous.profile;
                if (refresh.isEmpty()) refresh = previous.refreshToken;
            }
            return new Session(access, refresh, expires, userId, email, displayName, profile);
        }

        private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    }

    /** Register succeeded, but whether a session came with it depends on the workspace. */
    public static final class RegistrationResult {
        private final boolean requiresEmailConfirmation;
        private final Session session;
        private final String message;

        RegistrationResult(boolean requiresEmailConfirmation, Session session, String message) {
            this.requiresEmailConfirmation = requiresEmailConfirmation;
            this.session = session;
            this.message = message;
        }

        /**
         * Set when the workspace requires email confirmation. There is NO session in that case, and
         * this is not a failure - telling the user their password was wrong would leave them
         * retrying a correct one forever.
         */
        public boolean requiresEmailConfirmation() { return requiresEmailConfirmation; }

        /** Null when confirmation is required. */
        public Session session() { return session; }

        public String message() { return message; }
    }

    private final Praxsuite client;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final List<Consumer<Session>> listeners = new CopyOnWriteArrayList<>();
    private volatile Session session;

    PraxAuth(Praxsuite client) {
        this.client = client;
    }

    /** The signed-in user, or null. */
    public Session session() { return session; }

    public boolean isSignedIn() {
        Session s = session;
        return s != null && s.isValid();
    }

    /**
     * Registers a callback fired whenever the signed-in user changes, sign-out included.
     *
     * <p>Useful for clearing a per-user cache, or for sending someone back to a login screen when a
     * refresh fails rather than discovering it on their next query.
     */
    public void onSessionChange(Consumer<Session> listener) {
        if (listener != null) listeners.add(listener);
    }

    // ── sign in and out ─────────────────────────────────────────────────────

    public RegistrationResult register(String email, String password) {
        return register(email, password, Map.of());
    }

    /** Creates an account. Check {@code requiresEmailConfirmation} before assuming a session. */
    public RegistrationResult register(String email, String password,
                                       Map<String, Object> extraFields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        if (extraFields != null) body.putAll(extraFields);

        Map<String, Object> payload = post("register", body);
        boolean requires = Boolean.TRUE.equals(payload.get("requiresEmailConfirmation"));
        String message = payload.get("message") == null ? "" : String.valueOf(payload.get("message"));

        Session created = null;
        if (!requires && payload.get("accessToken") != null) {
            created = adopt(Session.fromPayload(payload, null));
        }
        return new RegistrationResult(requires, created, message);
    }

    public Session login(String email, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        return adopt(Session.fromPayload(post("login", body), null));
    }

    /**
     * Signs out and clears the session.
     *
     * <p>The local session is cleared even if the server call fails - someone who pressed sign out
     * must end up signed out.
     */
    public void logout() {
        Session current = session;
        try {
            if (current != null && !current.refreshToken().isEmpty()) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("refreshToken", current.refreshToken());
                post("logout", body);
            }
        } catch (PraxError e) {
            PraxLog.debug("Server-side logout failed; clearing the local session anyway: " + e);
        } finally {
            setSession(null);
        }
    }

    // ── session maintenance ─────────────────────────────────────────────────

    /**
     * Refreshes if the access token is close to expiry. Called automatically before every request,
     * so you should not normally need it.
     */
    public void ensureFreshSession() {
        Session current = session;
        if (current == null || !current.isValid() || !current.needsRefresh()) return;

        refreshLock.lock();
        try {
            Session now = session;
            // Another thread may have refreshed while this one waited for the lock.
            if (now == null || !now.isValid() || !now.needsRefresh()) return;
            refreshLocked(now);
        } finally {
            refreshLock.unlock();
        }
    }

    /** Forces a refresh now. */
    public Session refresh() {
        refreshLock.lock();
        try {
            Session current = session;
            if (current == null || !current.isValid()) {
                throw new PraxAuthError("NOT_SIGNED_IN", "There is no session to refresh.",
                    401, List.of(), "");
            }
            return refreshLocked(current);
        } finally {
            refreshLock.unlock();
        }
    }

    private Session refreshLocked(Session current) {
        if (current.refreshToken().isEmpty()) {
            setSession(null);
            throw new PraxAuthError("SESSION_EXPIRED",
                "The session expired and there is no refresh token, so the user has been signed "
                    + "out.", 401, List.of(), "");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("refreshToken", current.refreshToken());
        try {
            return adopt(Session.fromPayload(post("refresh", body), current));
        } catch (PraxError e) {
            // A rejected refresh token is final. A network blip is not - keep the session, since
            // the existing token may still work.
            if (e.isAuthFailure()) setSession(null);
            throw e;
        }
    }

    // ── password reset and confirmation ─────────────────────────────────────
    //
    // These always report success, whether or not the address exists. That is deliberate on the
    // server's part: it stops the endpoint being used to discover which addresses have accounts.
    // Do not "helpfully" report that no such account exists - that reintroduces the leak.

    public void forgotPassword(String email) {
        post("forgot-password", Map.of("email", email));
    }

    public void verifyResetCode(String email, String code) {
        post("verify-reset-code", Map.of("email", email, "code", code));
    }

    public void resetPassword(String email, String code, String newPassword) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("code", code);
        body.put("newPassword", newPassword);
        post("reset-password", body);
    }

    public void resendConfirmation(String email) {
        post("resend-confirmation", Map.of("email", email));
    }

    /**
     * Reads the workspace's public auth configuration.
     *
     * <p>This route is UNAUTHENTICATED. A workspace id alone is enough to fetch it, which is why a
     * workspace id is not a secret - but also why it does not belong in a published example.
     */
    public Map<String, Object> config() {
        String url = Routes.auth(client.baseUrl(), client.workspaceId(), "config");
        return Responses.unwrapEnvelope(
            client.transport().requestJson("GET", url, client.anonymousHeaders(), null, true, null));
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private Session adopt(Session candidate) {
        if (!candidate.isValid()) {
            throw new PraxError("MALFORMED_RESPONSE", "The gateway returned no access token.");
        }
        setSession(candidate);
        return candidate;
    }

    private void setSession(Session next) {
        this.session = next;
        for (Consumer<Session> listener : listeners) {
            try {
                listener.accept(next);
            } catch (RuntimeException e) {
                // A caller's callback must not break sign-in.
                PraxLog.warn("A session-change listener threw: " + e);
            }
        }
    }

    private Map<String, Object> post(String action, Map<String, Object> body) {
        String url = Routes.auth(client.baseUrl(), client.workspaceId(), action);
        // Auth calls carry the credential, never the session: signing in while already signed in
        // must not depend on the old token still being valid.
        Map<String, Object> response = client.transport()
            .requestJson("POST", url, client.anonymousHeaders(), body, false, null);
        return Responses.unwrapEnvelope(response);
    }
}
