package com.tesseractsoftwares.praxsuite;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
            // Map.copyOf (used elsewhere in this SDK for immutability) throws on a null value, and
            // a workspace's user profile legitimately has one - an unset avatarUrl or username
            // comes back as JSON null, not an absent key. LinkedHashMap tolerates that; Map.copyOf
            // does not.
            this.profile = profile == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(profile));
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
            created = adoptInternal(Session.fromPayload(payload, null));
        }
        return new RegistrationResult(requires, created, message);
    }

    public Session login(String email, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        return adoptInternal(Session.fromPayload(post("login", body), null));
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
            return adoptInternal(Session.fromPayload(post("refresh", body), current));
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

    // ── external identity providers ─────────────────────────────────────────

    /** One external identity provider the workspace has configured. */
    public record OidcProvider(String slug, String displayName) { }

    /**
     * Where to send the user, and the CSRF token to bring back.
     *
     * @param authorizationUrl open this in a browser
     * @param state            one-time value the gateway issued and will consume on the
     *                         callback; the provider echoes it back on the redirect, so it can
     *                         usually be read from there - it is returned here so nobody has to
     *                         parse it out of a URL
     */
    public record OidcStart(String authorizationUrl, String state) { }

    /**
     * The external identity providers this workspace has configured.
     *
     * <p>Read from {@code oidcProviders} in the public config. {@code
     * authPageConfig.enabledSocialProviders} is a different list, written by the portal's
     * auth-page designer - a provider named there but absent here is not configured, and its
     * button is a dead end.
     */
    @SuppressWarnings("unchecked")
    public List<OidcProvider> providers() {
        Object raw = config().get("oidcProviders");
        if (!(raw instanceof List<?> entries)) {
            return List.of();
        }

        List<OidcProvider> found = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof String slug && !slug.isEmpty()) {
                found.add(new OidcProvider(slug, slug));
            } else if (entry instanceof Map) {
                Map<String, Object> provider = (Map<String, Object>) entry;
                Object slug = provider.get("slug");
                if (slug instanceof String text && !text.isEmpty()) {
                    Object label = provider.get("displayName");
                    found.add(new OidcProvider(text,
                        label instanceof String name && !name.isEmpty() ? name : text));
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * Starts a sign-in with an external identity provider.
     *
     * <p>Returns where to send the user and the one-time {@code state} the gateway issued. Open
     * the URL in a browser; the provider sends the user back to the redirect URI configured for
     * it in the portal, carrying {@code code} and {@code state}. Hand all of it to
     * {@link #completeOidcLogin}.
     *
     * <p>Only the authorization-code flow exists - there is no route that accepts a provider's
     * own id_token - so even a native button has to make this browser hop.
     */
    public OidcStart startOidcLogin(String providerSlug) {
        if (providerSlug == null || providerSlug.isBlank()) {
            throw new PraxValidationError("INVALID_ARGUMENT", "providerSlug is required.");
        }

        String url = Routes.auth(client.baseUrl(), client.workspaceId(),
            "oidc/" + URLEncoder.encode(providerSlug.strip(), StandardCharsets.UTF_8));
        Map<String, Object> payload = Responses.unwrapEnvelope(client.transport()
            .requestJson("GET", url, client.anonymousHeaders(), null, true, null));

        Object authorizationUrl = payload.get("authorizationUrl");
        if (!(authorizationUrl instanceof String text) || text.isEmpty()) {
            authorizationUrl = payload.get("url");
        }
        if (!(authorizationUrl instanceof String link) || link.isEmpty()) {
            throw new PraxError("OIDC_NO_URL",
                "The gateway returned no authorization URL for provider \"" + providerSlug
                + "\". Check that it is configured and enabled for this workspace.");
        }

        Object state = payload.get("state");
        return new OidcStart(link, state instanceof String value ? value : "");
    }

    /**
     * Exchanges the provider's code for a Praxsuite session.
     *
     * <p>All four values are required by the gateway, and three of them are why this call fails
     * when it fails. {@code providerSlug} scopes the one-time state, so omitting it makes every
     * callback look expired. {@code state} is consumed once; reusing or skipping it is rejected.
     * {@code redirectUri} is compared against the value configured for that provider and must
     * match exactly - pass the URI you were actually redirected to rather than rebuilding it,
     * which is how it ends up differing by a trailing slash and failing with a message about
     * redirect URIs that nobody can act on.
     *
     * <p>The session is stored exactly as a password login stores it, so refresh, sign-out and
     * every authenticated call behave identically afterwards.
     *
     * <p>An email already registered as a local password account comes back as a 400 rather than
     * a session; the user's fix is to sign in with their password.
     */
    public Session completeOidcLogin(String providerSlug, String code, String state,
                                     String redirectUri) {
        require(providerSlug, "providerSlug");
        require(code, "code");
        require(state, "state");
        require(redirectUri, "redirectUri");

        return adoptInternal(Session.fromPayload(post("oidc/callback", Map.of(
            "providerSlug", providerSlug,
            "code", code,
            "state", state,
            "redirectUri", redirectUri)), null));
    }

    // ── server-asserted (platform) identity ──────────────────────────────────

    /**
     * Opens a session for one of your game's players, on your own server's word - no login screen
     * shown to them. This is how a Roblox experience or a Minecraft server in {@code online-mode}
     * signs a player in: there is no browser to redirect through, so {@link #startOidcLogin} does
     * not apply.
     *
     * <p><b>Requires a secret key ({@code sk_live_})</b> that the portal has marked for {@code
     * providerSlug} under Settings &gt; API Gateway - never a publishable key. The gateway trusts
     * whoever holds that key, not {@code platformPlayerId} itself, so read the id from a source the
     * player cannot forge: the platform's own server-side API (Bukkit's {@code Player.getUniqueId()}
     * under {@code online-mode:true}, Roblox's {@code player.UserId}, etc.), never a client-supplied
     * claim.
     *
     * <p><b>Does not become the client's ambient session.</b> {@link #login} and {@link
     * #completeOidcLogin} install their result as {@code auth.session()} because an app normally
     * signs in one user at a time. A game server is not that: it asserts many players concurrently
     * on one shared {@link Praxsuite} instance, and installing the last-asserted player's session as
     * "the" session would let one player's requests run - and row-filter - as another. Keep the
     * returned {@link Session} yourself, keyed by player, and pass its {@link Session#accessToken()}
     * explicitly wherever a call needs to act as that specific player (e.g. via {@code
     * prax.endpoints().call}).
     *
     * <p>Sessions from this path are marked "server asserted" by the gateway - enough to carry roles
     * and own data, deliberately a lower trust tier than a player who completed the platform's own
     * interactive login. A new account gets whatever default roles the provider is configured with;
     * many workspaces prefer to assign roles explicitly afterwards (via an endpoint that validates
     * this session's access token) instead, since a provider's static default applies to every
     * platform-asserted signup the same way.
     *
     * @param providerSlug     the identity provider's slug, e.g. {@code "minecraft"}
     * @param platformPlayerId the player's id on that platform, server-side, never client-supplied
     */
    public Session assertPlayer(String providerSlug, String platformPlayerId) {
        return assertPlayer(providerSlug, platformPlayerId, null, null, null);
    }

    /** Like {@link #assertPlayer(String, String)}, with a cosmetic display name. */
    public Session assertPlayer(String providerSlug, String platformPlayerId, String displayName) {
        return assertPlayer(providerSlug, platformPlayerId, displayName, null, null);
    }

    /**
     * Full form of {@link #assertPlayer(String, String)}. {@code displayName} and {@code avatarUrl}
     * are cosmetic only - never used for matching or authorization. {@code metadata} is free-form and
     * stored as given.
     */
    public Session assertPlayer(String providerSlug, String platformPlayerId, String displayName,
                                String avatarUrl, Map<String, Object> metadata) {
        require(providerSlug, "providerSlug");
        require(platformPlayerId, "platformPlayerId");
        KeyGuard.requireServerKey(client.credential(), "Praxsuite.auth().assertPlayer()");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("platformPlayerId", platformPlayerId);
        if (displayName != null && !displayName.isBlank()) body.put("displayName", displayName);
        if (avatarUrl != null && !avatarUrl.isBlank()) body.put("avatarUrl", avatarUrl);
        if (metadata != null && !metadata.isEmpty()) body.put("metadata", metadata);

        String action = URLEncoder.encode(providerSlug.strip(), StandardCharsets.UTF_8) + "/assert";
        return requireValid(Session.fromPayload(post(action, body), null));
    }

    /**
     * Validates a parsed session without installing it as the client's ambient session - see
     * {@link #assertPlayer(String, String)} for why that distinction matters here.
     */
    private static Session requireValid(Session candidate) {
        if (!candidate.isValid()) {
            throw new PraxError("MALFORMED_RESPONSE", "The gateway returned no access token.");
        }
        return candidate;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new PraxValidationError("INVALID_ARGUMENT", name + " is required.");
        }
    }

    /**
     * Installs an already-obtained session (typically from {@link #assertPlayer}) as this
     * client's ambient session - the one {@code prax.bus()} and every request without an explicit
     * override authenticates as.
     *
     * <p>{@code assertPlayer} deliberately does not do this for you: a game server asserts many
     * players concurrently on one shared {@link Praxsuite} instance, and this call replaces
     * whichever session was ambient before. Call it only on a {@link Praxsuite} instance dedicated
     * to a single identity for its whole lifetime - a bot account backing a background listener,
     * for instance - never on the instance a game server also uses to assert its players, or every
     * subsequent request there would authenticate as this one adopted identity instead of the
     * server key.
     */
    public Session adopt(Session session) {
        return adoptInternal(session);
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private Session adoptInternal(Session candidate) {
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
