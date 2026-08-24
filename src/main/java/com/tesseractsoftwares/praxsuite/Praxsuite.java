package com.tesseractsoftwares.praxsuite;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Praxsuite client.
 *
 * <pre>{@code
 * Praxsuite prax = Praxsuite.builder()
 *     .workspaceId("your-workspace-id")
 *     .credential("sk_live_...")
 *     .build();
 *
 * Page page = prax.data().table("Orders")
 *     .where(Filters.eq("Status", "paid"))
 *     .limit(50)
 *     .fetch();
 * }</pre>
 *
 * <p>Cheap to construct and safe to share between threads. Sessions and the pooled HTTP client live
 * on the instance, so one instance per workspace per process is the usual arrangement - in a
 * Minecraft plugin, one field on your {@code JavaPlugin}.
 *
 * <h2>Which credential to use</h2>
 *
 * <p>A JVM process is usually a server you control, so a secret key ({@code sk_live_}) is normally
 * correct - that is what one is for.
 *
 * <p>The exception is anything a user can read: an Android build, a desktop app, a client-side mod,
 * or a plugin you hand to server owners who are not you. Set {@link Builder#clientSide(boolean)} and
 * the SDK refuses a secret key outright rather than trusting you to remember.
 *
 * <p>Note that every credential carries BOTH halves - there is no publishable-only credential - so
 * whatever tables you scope to a credential are reachable by anyone holding the workspace id, since
 * {@code /{workspace}/auth/config} is unauthenticated. Scope narrowly.
 */
public final class Praxsuite {

    public static final String SDK_VERSION = "1.0.0";

    private final String workspaceId;
    private final String baseUrl;
    private final String credential;
    private final Http transport;
    private final PraxAuth auth;
    private final PraxData data;
    private final PraxEndpoints endpoints;
    private final PraxSchema schema;

    private Praxsuite(Builder b) {
        String ws = firstNonBlank(b.workspaceId, System.getenv("PRAXSUITE_WORKSPACE_ID"));
        String cred = firstNonBlank(b.credential, System.getenv("PRAXSUITE_API_KEY"));
        String url = firstNonBlank(b.baseUrl, System.getenv("PRAXSUITE_BASE_URL"), Routes.CLOUD_HOST);

        if (ws == null) {
            throw new PraxValidationError("MISSING_WORKSPACE",
                "A workspace id is required. Set it on the builder, or PRAXSUITE_WORKSPACE_ID.");
        }
        if (cred == null) {
            throw new PraxValidationError("MISSING_CREDENTIAL",
                "An API key is required. Set it on the builder, or PRAXSUITE_API_KEY. Create one "
                    + "in your workspace under API Gateway.");
        }
        if (b.clientSide) {
            KeyGuard.requireClientSafe(cred, "Praxsuite.builder().clientSide(true)");
        }

        this.workspaceId = ws.trim();
        this.credential = cred.trim();
        this.baseUrl = Routes.normalizeBaseUrl(url);

        if (Routes.isInsecureRemote(this.baseUrl)) {
            // Not fatal - a LAN test server is a legitimate target - but a plaintext connection puts
            // the credential on the wire for anyone on the network to read.
            PraxLog.warn(this.baseUrl + " is plaintext HTTP. Credentials and session tokens will "
                + "travel unencrypted; use https for anything but local testing.");
        }

        this.transport = b.transport != null ? b.transport : new Http(b.timeout, b.maxAttempts);
        this.auth = new PraxAuth(this);
        this.data = new PraxData(this);
        this.endpoints = new PraxEndpoints(this);
        this.schema = new PraxSchema(this);

        PraxLog.info("Configured for workspace " + this.workspaceId + " at " + this.baseUrl
            + " using " + KeyGuard.redact(this.credential) + " (SDK " + SDK_VERSION + ")");
    }

    public static Builder builder() { return new Builder(); }

    public String workspaceId() { return workspaceId; }
    public String baseUrl() { return baseUrl; }

    public PraxAuth auth() { return auth; }
    public PraxData data() { return data; }
    public PraxEndpoints endpoints() { return endpoints; }
    public PraxSchema schema() { return schema; }

    /** Shorthand for {@code data().table(name)}. */
    public Query table(String nameOrId) { return data.table(nameOrId); }

    /** Shorthand for {@code data().execute(request)}. */
    public Map<String, Object> execute(Map<String, Object> request) { return data.execute(request); }

    Http transport() { return transport; }

    /** Headers for a call that must NOT carry a user's session: sign-in, and auth/config. */
    Map<String, String> anonymousHeaders() {
        Map<String, String> h = baseHeaders();
        h.put("x-api-key", credential);
        return h;
    }

    /**
     * Headers for everything else.
     *
     * <p>A signed-in user's session takes precedence, so row filters and role scopes apply to them
     * rather than to the anonymous credential. The gateway accepts either header, never both:
     * Authorization carries a session token, x-api-key carries a key.
     */
    Map<String, String> sessionHeaders() {
        Map<String, String> h = baseHeaders();
        PraxAuth.Session s = auth.session();
        if (s != null && s.isValid()) {
            h.put("Authorization", "Bearer " + s.accessToken());
        } else {
            h.put("x-api-key", credential);
        }
        return h;
    }

    private Map<String, String> baseHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("x-praxsuite-sdk", "java/" + SDK_VERSION);
        return h;
    }

    /**
     * Sends an authorised request, refreshing the session first if it is near expiry.
     *
     * <p>A 401 on a signed-in request is retried once after a refresh: an access token can expire
     * between the check and the server reading it.
     */
    Map<String, Object> send(String method, String url, Object body, boolean retrySafe,
                             Duration timeout) {
        return send(method, url, body, retrySafe, timeout, false);
    }

    private Map<String, Object> send(String method, String url, Object body, boolean retrySafe,
                                     Duration timeout, boolean alreadyRefreshed) {
        if (auth.isSignedIn()) {
            try {
                auth.ensureFreshSession();
            } catch (PraxError e) {
                // Only fatal if it actually signed the user out. A network blip leaves the old
                // token in place, and it may still work.
                if (!auth.isSignedIn()) throw e;
                PraxLog.debug("Pre-flight refresh failed but the session survives: " + e);
            }
        }

        try {
            return transport.requestJson(method, url, sessionHeaders(), body, retrySafe, timeout);
        } catch (PraxError e) {
            if (e.isAuthFailure() && auth.isSignedIn() && !alreadyRefreshed) {
                try {
                    auth.refresh();
                } catch (PraxError ignored) {
                    throw e;
                }
                return send(method, url, body, retrySafe, timeout, true);
            }
            throw e;
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return null;
    }

    @Override
    public String toString() {
        return "Praxsuite[workspaceId=" + workspaceId + ", baseUrl=" + baseUrl
            + ", credential=" + KeyGuard.redact(credential) + "]";
    }

    /** Builder for {@link Praxsuite}. Every value falls back to an environment variable. */
    public static final class Builder {
        private String workspaceId;
        private String credential;
        private String baseUrl;
        private boolean clientSide;
        private Duration timeout = Duration.ofSeconds(20);
        private int maxAttempts = Http.MAX_ATTEMPTS;
        private Http transport;

        private Builder() {}

        /** Falls back to {@code PRAXSUITE_WORKSPACE_ID}. */
        public Builder workspaceId(String value) { this.workspaceId = value; return this; }

        /** An API key. Falls back to {@code PRAXSUITE_API_KEY}. */
        public Builder credential(String value) { this.credential = value; return this; }

        /** Gateway host. Falls back to {@code PRAXSUITE_BASE_URL}, then the cloud gateway. */
        public Builder baseUrl(String value) { this.baseUrl = value; return this; }

        /**
         * Set when this code runs somewhere a user can read it - an Android build, a desktop app, a
         * client mod, a plugin handed to server owners. Makes a secret key a hard error rather than
         * a judgement call.
         */
        public Builder clientSide(boolean value) { this.clientSide = value; return this; }

        /**
         * Per-request timeout. Defaults to 20 seconds.
         *
         * <p>Endpoint calls override this on their own, because a Sync endpoint holds the connection
         * while its automation runs.
         */
        public Builder timeout(Duration value) {
            if (value != null) this.timeout = value;
            return this;
        }

        /** Attempts for idempotent requests. 1 disables retries. */
        public Builder maxAttempts(int value) { this.maxAttempts = value; return this; }

        /** Package-private: substitutes a recording transport so the tests need no network. */
        Builder transport(Http value) { this.transport = value; return this; }

        public Praxsuite build() { return new Praxsuite(this); }
    }
}
